package myfinances.domain.loan.reducer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import myfinances.domain.loan.diagnostics.LoanDiagnosticCode;
import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanJournalEntry;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.journal.LoanType;
import org.junit.jupiter.api.Test;

class DefaultLoanReducerTest {
    private final DefaultLoanReducer reducer = new DefaultLoanReducer();

    @Test
    void ordersJournalCanonicallyRegardlessOfArrivalOrder() {
        LoanJournalEntry creation = creation("e1", "o1", "loan-1", "owner-1", 100, 100);
        LoanJournalEntry topup = entry("e2", "o2", "loan-1", "owner-1", "TOPUP", 200, 200, 10L, Map.of());
        LoanJournalEntry payment = entry("e3", "o3", "loan-1", "owner-1", "PAYMENT", 300, 300, 5L, Map.of());

        LoanReductionResult first = reducer.reduce(List.of(payment, creation, topup));
        LoanReductionResult second = reducer.reduce(List.of(topup, payment, creation));

        assertEquals(ReductionResultType.VALID, first.type());
        assertEquals(List.of("e1", "e2", "e3"), ids(first.normalizedJournal()));
        assertEquals(first, second);
        assertNotNull(first.snapshot());
    }

    @Test
    void canonicalizesLegacyPaymentAliases() {
        LoanJournalEntry creation = creation("e1", "o1", "loan-1", "owner-1", 100, 100);
        LoanJournalEntry paymentIn = entry("e2", "o2", "loan-1", "owner-1", "PAYMENT_IN", 200, 200, 10L, Map.of());
        LoanJournalEntry paymentOut = entry("e3", "o3", "loan-1", "owner-1", "PAYMENT_OUT", 300, 300, 20L, Map.of());

        LoanReductionResult result = reducer.reduce(List.of(paymentOut, creation, paymentIn));

        assertEquals(ReductionResultType.VALID, result.type());
        assertEquals(LoanEventType.PAYMENT, result.normalizedJournal().get(1).eventType());
        assertEquals(LoanEventType.PAYMENT, result.normalizedJournal().get(2).eventType());
        assertEquals(
            LoanEventPayload.LegacyPaymentDirection.IN,
            ((LoanEventPayload.PaymentPayload) result.normalizedJournal().get(1).payload()).legacyDirection()
        );
        assertEquals(
            LoanEventPayload.LegacyPaymentDirection.OUT,
            ((LoanEventPayload.PaymentPayload) result.normalizedJournal().get(2).payload()).legacyDirection()
        );
    }

    @Test
    void collapsesEquivalentDuplicateEventIds() {
        LoanJournalEntry creation = creation("e1", "o1", "loan-1", "owner-1", 100, 100);
        LoanJournalEntry payment = entry("e2", "o2", "loan-1", "owner-1", "PAYMENT", 200, 200, 10L, Map.of());

        LoanReductionResult result = reducer.reduce(List.of(payment, creation, payment));

        assertEquals(ReductionResultType.VALID, result.type());
        assertEquals(List.of("e1", "e2"), ids(result.normalizedJournal()));
        assertEquals(List.of("e2"), ids(result.discardedDuplicates()));
    }

    @Test
    void rejectsConflictingEventIds() {
        LoanJournalEntry creation = creation("e1", "o1", "loan-1", "owner-1", 100, 100);
        LoanJournalEntry first = entry("e2", "o2", "loan-1", "owner-1", "PAYMENT", 200, 200, 10L, Map.of());
        LoanJournalEntry second = entry("e2", "o2", "loan-1", "owner-1", "PAYMENT", 200, 200, 20L, Map.of());

        LoanReductionResult result = reducer.reduce(List.of(creation, second, first));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.EVENT_ID_CONFLICT);
    }

    @Test
    void rejectsConflictingOperationIds() {
        LoanJournalEntry creation = creation("e1", "o1", "loan-1", "owner-1", 100, 100);
        LoanJournalEntry first = entry("e2", "shared", "loan-1", "owner-1", "PAYMENT", 200, 200, 10L, Map.of());
        LoanJournalEntry second = entry("e3", "shared", "loan-1", "owner-1", "PAYMENT", 200, 200, 20L, Map.of());

        LoanReductionResult result = reducer.reduce(List.of(creation, first, second));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.OPERATION_CONFLICT);
    }

    @Test
    void resolvesEveryNormativeReversalTarget() {
        for (String targetType : List.of("TOPUP", "PAYMENT", "ADJUSTMENT", "CLOSE")) {
            LoanJournalEntry creation = creation("e1", "o1", "loan-1", "owner-1", 100, 100);
            LoanJournalEntry target = target("e2", "o2", targetType);
            LoanJournalEntry reversal = reversal("e3", "o3", "loan-1", "owner-1", "e2", 300);

            LoanReductionResult result = reducer.reduce(List.of(reversal, target, creation));

            assertEquals(ReductionResultType.VALID, result.type(), targetType);
            assertEquals(List.of("e2"), ids(result.revertedEvents()), targetType);
            assertEquals(List.of("e1"), ids(result.effectiveEvents()), targetType);
        }
    }

    @Test
    void reportsUnresolvedReversalAsIncomplete() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            reversal("e2", "o2", "loan-1", "owner-1", "missing", 200)
        ));

        assertEquals(ReductionResultType.INCOMPLETE, result.type());
        assertCodes(result, LoanDiagnosticCode.UNRESOLVED_REVERSAL);
    }

    @Test
    void rejectsNonReversibleTarget() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            reversal("e2", "o2", "loan-1", "owner-1", "e1", 200)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.NON_REVERSIBLE_TARGET);
    }

    @Test
    void rejectsCrossLoanReversal() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            entry("e2", "o2", "loan-2", "owner-1", "PAYMENT", 200, 200, 10L, Map.of()),
            reversal("e3", "o3", "loan-1", "owner-1", "e2", 300)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertTrue(codes(result).contains(LoanDiagnosticCode.MIXED_AGGREGATES));
        assertTrue(codes(result).contains(LoanDiagnosticCode.CROSS_LOAN_REVERSAL));
    }

    @Test
    void rejectsMultipleReversalsOfSameTarget() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            entry("e2", "o2", "loan-1", "owner-1", "PAYMENT", 200, 200, 10L, Map.of()),
            reversal("e3", "o3", "loan-1", "owner-1", "e2", 300),
            reversal("e4", "o4", "loan-1", "owner-1", "e2", 400)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.MULTIPLE_REVERSALS);
    }

    @Test
    void rejectsEventBeforeCreation() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 200, 200),
            entry("e2", "o2", "loan-1", "owner-1", "PAYMENT", 100, 100, 10L, Map.of())
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.EVENT_BEFORE_CREATION);
    }

    @Test
    void rejectsMultipleCreations() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            creation("e2", "o2", "loan-1", "owner-1", 200, 200)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.MULTIPLE_CREATIONS);
    }

    @Test
    void rejectsMixedAggregates() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            entry("e2", "o2", "loan-2", "owner-1", "TOPUP", 200, 200, 10L, Map.of())
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.MIXED_AGGREGATES);
    }

    @Test
    void reportsMissingCreationAsInvalid() {
        LoanReductionResult result = reducer.reduce(List.of(
            entry("e1", "o1", "loan-1", "owner-1", "PAYMENT", 100, 100, 10L, Map.of())
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.MISSING_CREATION);
    }

    @Test
    void reportsUnsupportedTypeAsIncomplete() {
        LoanReductionResult result = reducer.reduce(List.of(
            entry("e1", "o1", "loan-1", "owner-1", "UNKNOWN", 100, 100, null, Map.of())
        ));

        assertEquals(ReductionResultType.INCOMPLETE, result.type());
        assertCodes(result, LoanDiagnosticCode.UNSUPPORTED_EVENT_TYPE);
    }

    @Test
    void reportsUnsupportedVersionAsIncomplete() {
        LoanJournalEntry unsupported = new LoanJournalEntry(
            "e1", "o1", "loan-1", "owner-1", "CREATION", 2, 100L, null, null,
            null, 100, 100, null, null, creationPayload()
        );

        LoanReductionResult result = reducer.reduce(List.of(unsupported));

        assertEquals(ReductionResultType.INCOMPLETE, result.type());
        assertCodes(result, LoanDiagnosticCode.UNSUPPORTED_EVENT_VERSION);
    }

    @Test
    void rejectsInvalidPayload() {
        LoanReductionResult result = reducer.reduce(List.of(
            entry("e1", "o1", "loan-1", "owner-1", "CREATION", 100, 100, 100L, Map.of())
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.INVALID_EVENT_PAYLOAD);
    }

    @Test
    void collapsesEquivalentOperationIdsWithDifferentEventIds() {
        LoanJournalEntry creation = creation("e1", "o1", "loan-1", "owner-1", 100, 100);
        LoanJournalEntry first = entry("e2", "shared", "loan-1", "owner-1", "PAYMENT", 200, 200, 10L, Map.of());
        LoanJournalEntry second = entry("e3", "shared", "loan-1", "owner-1", "PAYMENT", 200, 200, 10L, Map.of());

        LoanReductionResult result = reducer.reduce(List.of(second, creation, first));

        assertEquals(ReductionResultType.VALID, result.type());
        assertEquals(List.of("e1", "e2"), ids(result.normalizedJournal()));
        assertEquals(List.of("e3"), ids(result.discardedDuplicates()));
    }

    @Test
    void reportsLegacyDirectionMismatchWithoutInvalidatingJournal() {
        LoanJournalEntry payment = entry(
            "e2", "o2", "loan-1", "owner-1", "PAYMENT_IN", 200, 200, 10L,
            Map.of("legacyDirection", "OUT")
        );

        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            payment
        ));

        assertEquals(ReductionResultType.VALID, result.type());
        assertCodes(result, LoanDiagnosticCode.LEGACY_DIRECTION_MISMATCH);
        assertEquals(
            LoanEventPayload.LegacyPaymentDirection.IN,
            ((LoanEventPayload.PaymentPayload) result.normalizedJournal().get(1).payload()).legacyDirection()
        );
    }

    @Test
    void canonicalizesMetadataChangesStructurally() {
        LoanJournalEntry metadata = entry(
            "e2", "o2", "loan-1", "owner-1", "METADATA_CHANGED", 200, 200, null,
            Map.of("changes", Map.of("counterpartyName", "Ana Pérez"))
        );

        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            metadata
        ));

        assertEquals(ReductionResultType.VALID, result.type());
        LoanEventPayload.MetadataChangedPayload payload =
            (LoanEventPayload.MetadataChangedPayload) result.normalizedJournal().get(1).payload();
        assertTrue(payload.changes().counterpartyName().present());
        assertEquals("Ana Pérez", payload.changes().counterpartyName().value());
        assertTrue(!payload.changes().notes().present());
    }

    @Test
    void rejectsMissingOrUnexpectedAmountByEventShape() {
        LoanJournalEntry paymentWithoutAmount = entry(
            "e2", "o2", "loan-1", "owner-1", "PAYMENT", 200, 200, null, Map.of()
        );
        LoanJournalEntry closeWithAmount = entry(
            "e3", "o3", "loan-1", "owner-1", "CLOSE", 300, 300, 1L, Map.of()
        );

        LoanReductionResult first = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            paymentWithoutAmount
        ));
        LoanReductionResult second = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            closeWithAmount
        ));

        assertEquals(ReductionResultType.INVALID, first.type());
        assertCodes(first, LoanDiagnosticCode.INVALID_EVENT_PAYLOAD);
        assertEquals(ReductionResultType.INVALID, second.type());
        assertCodes(second, LoanDiagnosticCode.INVALID_EVENT_PAYLOAD);
    }

    @Test
    void rejectsReversalOfReversal() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", "o1", "loan-1", "owner-1", 100, 100),
            entry("e2", "o2", "loan-1", "owner-1", "PAYMENT", 200, 200, 10L, Map.of()),
            reversal("e3", "o3", "loan-1", "owner-1", "e2", 300),
            reversal("e4", "o4", "loan-1", "owner-1", "e3", 400)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertTrue(codes(result).contains(LoanDiagnosticCode.NON_REVERSIBLE_TARGET));
    }

    @Test
    void canonicalEntryPointRejectsMalformedCanonicalEvent() {
        LoanMovement malformed = new LoanMovement(
            "e1", "o1", "loan-1", "owner-1", LoanEventType.CREATION, 1,
            null, null, null, null, 100, 100, null, null,
            new LoanEventPayload.CreationPayload(LoanType.LENT, "Ana", "USD", null, null)
        );

        LoanReductionResult result = reducer.reduceCanonical(List.of(malformed));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertCodes(result, LoanDiagnosticCode.INVALID_EVENT_PAYLOAD);
        assertNull(result.snapshot());
    }

    private static LoanJournalEntry creation(
        String eventId,
        String operationId,
        String loanId,
        String ownerId,
        long occurredAt,
        long recordedAt
    ) {
        return entry(eventId, operationId, loanId, ownerId, "CREATION", occurredAt, recordedAt, 100L, creationPayload());
    }

    private static LoanJournalEntry target(String eventId, String operationId, String type) {
        Map<String, Object> payload = switch (type) {
            case "ADJUSTMENT" -> Map.of("reason", "correction");
            case "CLOSE" -> Map.of();
            default -> Map.of();
        };
        Long amount = "CLOSE".equals(type) ? null : 10L;
        return entry(eventId, operationId, "loan-1", "owner-1", type, 200, 200, amount, payload);
    }

    private static LoanJournalEntry reversal(
        String eventId,
        String operationId,
        String loanId,
        String ownerId,
        String targetEventId,
        long occurredAt
    ) {
        return entry(
            eventId,
            operationId,
            loanId,
            ownerId,
            "REVERSAL",
            occurredAt,
            occurredAt,
            null,
            Map.of("targetEventId", targetEventId, "reason", "mistake")
        );
    }

    private static LoanJournalEntry entry(
        String eventId,
        String operationId,
        String loanId,
        String ownerId,
        String type,
        long occurredAt,
        long recordedAt,
        Long amount,
        Map<String, Object> payload
    ) {
        return new LoanJournalEntry(
            eventId,
            operationId,
            loanId,
            ownerId,
            type,
            1,
            amount,
            null,
            null,
            null,
            occurredAt,
            recordedAt,
            null,
            null,
            payload
        );
    }

    private static Map<String, Object> creationPayload() {
        return Map.of(
            "loanType", "LENT",
            "counterpartyName", "Ana",
            "currency", "USD"
        );
    }

    private static List<String> ids(List<myfinances.domain.loan.journal.LoanMovement> events) {
        return events.stream().map(myfinances.domain.loan.journal.LoanMovement::eventId).toList();
    }

    private static List<LoanDiagnosticCode> codes(LoanReductionResult result) {
        return result.diagnostics().stream().map(diagnostic -> diagnostic.code()).toList();
    }

    private static void assertCodes(LoanReductionResult result, LoanDiagnosticCode... expected) {
        assertEquals(List.of(expected), codes(result));
    }
}
