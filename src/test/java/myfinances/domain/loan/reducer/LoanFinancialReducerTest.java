package myfinances.domain.loan.reducer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import myfinances.domain.loan.diagnostics.LoanDiagnosticCode;
import myfinances.domain.loan.journal.LoanJournalEntry;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.domain.loan.snapshot.LoanStatus;
import org.junit.jupiter.api.Test;

class LoanFinancialReducerTest {
    private static final String EXPECTED_CREATION_FINGERPRINT = "aaf32e6a3309ec64bef0cb7a4db46e6a1cc414845ad410bc42a7bed5726632d1";
    private final DefaultLoanReducer reducer = new DefaultLoanReducer();

    @Test
    void v1BuildsSnapshotFromCreation() {
        LoanReductionResult result = reducer.reduce(List.of(creation("e1", 1_000, 100_000, "Ana", "A1", "Inicial")));

        assertEquals(ReductionResultType.VALID, result.type());
        LoanSnapshot snapshot = requireSnapshot(result);
        assertEquals("loan-1", snapshot.loanId());
        assertEquals("owner-1", snapshot.ownerId());
        assertEquals("Ana", snapshot.counterpartyName());
        assertEquals("USD", snapshot.currency());
        assertEquals("A1", snapshot.defaultAccountId());
        assertEquals("Inicial", snapshot.notes());
        assertFinancial(snapshot, 100_000, 0, 100_000, 100_000, 0, LoanStatus.OPEN, null);
        assertEquals(1_000, snapshot.lastActivityAt());
        assertEquals(1, snapshot.journalEventCount());
        assertEquals(1, snapshot.reducerVersion());
        assertEquals(EXPECTED_CREATION_FINGERPRINT, snapshot.journalFingerprint());
    }

    @Test
    void v2FoldsTopupAndPartialPayment() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            payment("e3", 3_000, 30_000),
            creation("e1", 1_000, 100_000, "Ana", "A1", null),
            topup("e2", 2_000, 25_000)
        )));

        assertFinancial(snapshot, 125_000, 30_000, 95_000, 95_000, 0, LoanStatus.OPEN, null);
    }

    @Test
    void v3ClosesAtExactPayment() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 40_000),
            payment("e3", 3_000, 60_000)
        )));

        assertFinancial(snapshot, 100_000, 100_000, 0, 0, 0, LoanStatus.CLOSED, 3_000L);
    }

    @Test
    void v4ExcludesReversedPayment() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 40_000),
            reversal("e3", 3_000, "e2")
        ));

        LoanSnapshot snapshot = requireSnapshot(result);
        assertFinancial(snapshot, 100_000, 0, 100_000, 100_000, 0, LoanStatus.OPEN, null);
        assertEquals(3_000, snapshot.lastActivityAt());
        assertEquals(3, snapshot.journalEventCount());
        assertEquals(List.of("e2"), result.revertedEvents().stream().map(event -> event.eventId()).toList());
    }

    @Test
    void v7ProducesSameSnapshotForEveryArrivalOrder() {
        List<LoanJournalEntry> events = List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            topup("e2", 2_000, 10_000),
            payment("e3", 3_000, 20_000)
        );
        LoanSnapshot expected = requireSnapshot(reducer.reduce(events));

        assertEquals(expected, requireSnapshot(reducer.reduce(List.of(events.get(2), events.get(0), events.get(1)))));
        assertEquals(expected, requireSnapshot(reducer.reduce(List.of(events.get(1), events.get(2), events.get(0)))));
        assertFinancial(expected, 110_000, 20_000, 90_000, 90_000, 0, LoanStatus.OPEN, null);
    }

    @Test
    void v8AppliesSignedAdjustment() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            topup("e2", 2_000, 20_000),
            adjustment("e3", 3_000, -15_000),
            payment("e4", 4_000, 25_000)
        )));

        assertFinancial(snapshot, 105_000, 25_000, 80_000, 80_000, 0, LoanStatus.OPEN, null);
    }

    @Test
    void v9RepresentsOverpaymentWithoutTruncatingPaid() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 60_000),
            payment("e3", 3_000, 50_000)
        ));

        LoanSnapshot snapshot = requireSnapshot(result);
        assertFinancial(snapshot, 100_000, 110_000, -10_000, 0, 10_000, LoanStatus.CLOSED, 3_000L);
        assertTrue(codes(result).contains(LoanDiagnosticCode.OVERPAYMENT));
    }

    @Test
    void v10AppliesMetadataCumulatively() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", "A1", null),
            metadata("e2", 2_000, value("Ana Pérez"), absent(), absent()),
            metadata("e3", 3_000, absent(), value("A2"), value("Acuerdo actualizado"))
        )));

        assertEquals("Ana Pérez", snapshot.counterpartyName());
        assertEquals("A2", snapshot.defaultAccountId());
        assertEquals("Acuerdo actualizado", snapshot.notes());
        assertFinancial(snapshot, 100_000, 0, 100_000, 100_000, 0, LoanStatus.OPEN, null);
    }

    @Test
    void v11CombinesMetadataChangesOnDifferentFields() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            metadata("e3", 3_000, absent(), value("A2"), absent()),
            creation("e1", 1_000, 100_000, "Ana", "A1", null),
            metadata("e2", 2_000, value("Ana Pérez"), absent(), absent())
        )));

        assertEquals("Ana Pérez", snapshot.counterpartyName());
        assertEquals("A2", snapshot.defaultAccountId());
        assertNull(snapshot.notes());
    }

    @Test
    void v13FoldsEveryLegacyPaymentAlias() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 500_000, "Ana", null, null),
            paymentAlias("e2", 2_000, 212_600, "PAYMENT_IN"),
            paymentAlias("e3", 3_000, 66_000, "PAYMENT_IN"),
            paymentAlias("e4", 4_000, 50_000, "PAYMENT_IN"),
            paymentAlias("e5", 5_000, 15_000, "PAYMENT_IN"),
            paymentAlias("e6", 6_000, 10_000, "PAYMENT_IN")
        )));

        assertFinancial(snapshot, 500_000, 353_600, 146_400, 146_400, 0, LoanStatus.OPEN, null);
        assertEquals(6, snapshot.journalEventCount());
    }

    @Test
    void v15ReportsPrematureCloseWithoutChangingState() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 20_000),
            close("e3", 3_000)
        ));

        LoanSnapshot snapshot = requireSnapshot(result);
        assertFinancial(snapshot, 100_000, 20_000, 80_000, 80_000, 0, LoanStatus.OPEN, null);
        assertTrue(codes(result).contains(LoanDiagnosticCode.PREMATURE_CLOSE));
    }

    @Test
    void v16TopupReopensClosedLoan() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 100_000),
            topup("e3", 3_000, 20_000)
        )));

        assertFinancial(snapshot, 120_000, 100_000, 20_000, 20_000, 0, LoanStatus.OPEN, null);
    }

    @Test
    void positiveAdjustmentReopensClosedLoan() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 100_000),
            adjustment("e3", 3_000, 20_000)
        )));

        assertFinancial(snapshot, 120_000, 100_000, 20_000, 20_000, 0, LoanStatus.OPEN, null);
    }

    @Test
    void reversalRemovesClosedAtFromRevertedClosingPayment() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 100_000),
            reversal("e3", 3_000, "e2")
        )));

        assertEquals(LoanStatus.OPEN, snapshot.status());
        assertNull(snapshot.closedAt());
        assertEquals(100_000, snapshot.pendingCents());
    }

    @Test
    void detectsCheckedArithmeticOverflow() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, Long.MAX_VALUE, "Ana", null, null),
            topup("e2", 2_000, 1)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertNull(result.snapshot());
        assertTrue(codes(result).contains(LoanDiagnosticCode.ARITHMETIC_OVERFLOW));
    }

    @Test
    void rejectsAdjustmentThatMakesPrincipalNonPositive() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            adjustment("e2", 2_000, -100_000)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertNull(result.snapshot());
        assertTrue(codes(result).contains(LoanDiagnosticCode.NON_POSITIVE_PRINCIPAL));
    }

    @Test
    void rejectsZeroAdjustmentEvent() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            adjustment("e2", 2_000, 0)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertTrue(codes(result).contains(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD));
        assertNull(result.snapshot());
    }

    @Test
    void fingerprintAndEventCountIgnoreDiscardedDuplicates() {
        LoanJournalEntry creation = creation("e1", 1_000, 100_000, "Ana", null, null);
        LoanJournalEntry payment = payment("e2", 2_000, 20_000);
        LoanSnapshot withoutDuplicate = requireSnapshot(reducer.reduce(List.of(creation, payment)));
        LoanReductionResult withDuplicateResult = reducer.reduce(List.of(payment, creation, payment));
        LoanSnapshot withDuplicate = requireSnapshot(withDuplicateResult);

        assertEquals(withoutDuplicate.journalFingerprint(), withDuplicate.journalFingerprint());
        assertEquals(2, withDuplicate.journalEventCount());
        assertEquals(1, withDuplicateResult.discardedDuplicates().size());
    }

    @Test
    void lastActivityIncludesCloseReversalAndRevertedEvents() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 7_000, 20_000),
            close("e3", 8_000),
            reversal("e4", 9_000, "e2")
        )));

        assertEquals(9_000, snapshot.lastActivityAt());
        assertEquals(4, snapshot.journalEventCount());
    }

    @Test
    void reducerIsDeterministicAndFingerprintHandlesCanonicalStrings() {
        List<LoanJournalEntry> events = List.of(
            creation("e1", 1_000, 100_000, "A\"na\n😀", "A1", "línea\\dos"),
            metadata("e2", 2_000, absent(), clear(), value("cambio"))
        );
        LoanReductionResult expected = reducer.reduce(events);

        for (int i = 0; i < 20; i++) {
            List<LoanJournalEntry> shuffled = new ArrayList<>(events);
            if (i % 2 == 0) {
                java.util.Collections.reverse(shuffled);
            }
            assertEquals(expected, reducer.reduce(shuffled));
        }
        assertNotNull(requireSnapshot(expected).journalFingerprint());
        assertFalse(requireSnapshot(expected).journalFingerprint().isBlank());
    }

    @Test
    void closeAfterFinancialClosureDoesNotMoveClosedAt() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 100_000),
            close("e3", 3_000)
        )));

        assertEquals(2_000L, snapshot.closedAt());
        assertEquals(3_000L, snapshot.lastActivityAt());
        assertEquals(LoanStatus.CLOSED, snapshot.status());
    }

    @Test
    void metadataCanClearNullableFields() {
        LoanSnapshot snapshot = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", "A1", "nota"),
            metadata("e2", 2_000, absent(), clear(), clear())
        )));

        assertNull(snapshot.defaultAccountId());
        assertNull(snapshot.notes());
        assertEquals("Ana", snapshot.counterpartyName());
    }

    @Test
    void rejectsNonPositiveCreationTopupAndPaymentAmounts() {
        LoanReductionResult creationResult = reducer.reduce(List.of(
            creation("e1", 1_000, 0, "Ana", null, null)
        ));
        LoanReductionResult topupResult = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            topup("e2", 2_000, 0)
        ));
        LoanReductionResult paymentResult = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", null, null),
            payment("e2", 2_000, 0)
        ));

        assertTrue(codes(creationResult).contains(LoanDiagnosticCode.NON_POSITIVE_PRINCIPAL));
        assertTrue(codes(topupResult).contains(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD));
        assertTrue(codes(paymentResult).contains(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD));
        assertNull(creationResult.snapshot());
        assertNull(topupResult.snapshot());
        assertNull(paymentResult.snapshot());
    }

    @Test
    void detectsPaymentAndAdjustmentOverflow() {
        LoanReductionResult paymentOverflow = reducer.reduce(List.of(
            creation("e1", 1_000, Long.MAX_VALUE, "Ana", null, null),
            payment("e2", 2_000, Long.MAX_VALUE),
            payment("e3", 3_000, 1)
        ));
        LoanReductionResult adjustmentOverflow = reducer.reduce(List.of(
            creation("e1", 1_000, Long.MAX_VALUE, "Ana", null, null),
            adjustment("e2", 2_000, 1)
        ));

        assertTrue(codes(paymentOverflow).contains(LoanDiagnosticCode.ARITHMETIC_OVERFLOW));
        assertTrue(codes(adjustmentOverflow).contains(LoanDiagnosticCode.ARITHMETIC_OVERFLOW));
        assertNull(paymentOverflow.snapshot());
        assertNull(adjustmentOverflow.snapshot());
    }

    @Test
    void rejectsInvalidUnicodeDuringCanonicalFingerprinting() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "\uD800", null, null)
        ));

        assertEquals(ReductionResultType.INVALID, result.type());
        assertTrue(codes(result).contains(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD));
        assertNull(result.snapshot());
    }

    @Test
    void fingerprintPreservesDistinctInt64ValuesWithoutIeee754Loss() {
        LoanSnapshot maximum = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, Long.MAX_VALUE, "Ana", null, null)
        )));
        LoanSnapshot previous = requireSnapshot(reducer.reduce(List.of(
            creation("e1", 1_000, Long.MAX_VALUE - 1, "Ana", null, null)
        )));

        assertFalse(maximum.journalFingerprint().equals(previous.journalFingerprint()));
    }

    @Test
    void fingerprintUsesNormativeCanonicalJsonShape() {
        LoanReductionResult result = reducer.reduce(List.of(
            creation("e1", 1_000, 100_000, "Ana", "A1", "Inicial")
        ));

        assertEquals(
            "[{\"account_id\":null,\"actor_id\":null,\"amount_cents\":\"100000\",\"event_id\":\"e1\",\"event_schema_version\":\"1\",\"event_type\":\"CREATION\",\"loan_id\":\"loan-1\",\"note\":null,\"occurred_at\":\"1000\",\"operation_id\":\"e1\",\"origin_id\":null,\"owner_id\":\"owner-1\",\"payload\":{\"counterparty_name\":\"Ana\",\"currency\":\"USD\",\"default_account_id\":\"A1\",\"loan_type\":\"LENT\",\"notes\":\"Inicial\"},\"recorded_at\":\"1000\",\"transaction_id\":null}]",
            LoanJournalFingerprint.canonicalJson(result.normalizedJournal())
        );
    }

    private static LoanSnapshot requireSnapshot(LoanReductionResult result) {
        assertEquals(ReductionResultType.VALID, result.type());
        assertNotNull(result.snapshot());
        return result.snapshot();
    }

    private static void assertFinancial(
        LoanSnapshot snapshot,
        long principal,
        long paid,
        long net,
        long pending,
        long overpaid,
        LoanStatus status,
        Long closedAt
    ) {
        assertEquals(principal, snapshot.principalCents());
        assertEquals(paid, snapshot.totalPaidCents());
        assertEquals(net, snapshot.netBalanceCents());
        assertEquals(pending, snapshot.pendingCents());
        assertEquals(overpaid, snapshot.overpaidCents());
        assertEquals(status, snapshot.status());
        assertEquals(closedAt, snapshot.closedAt());
    }

    private static List<LoanDiagnosticCode> codes(LoanReductionResult result) {
        return result.diagnostics().stream().map(diagnostic -> diagnostic.code()).toList();
    }

    private static LoanJournalEntry creation(
        String id,
        long occurredAt,
        long amount,
        String counterparty,
        String accountId,
        String notes
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("loanType", "LENT");
        payload.put("counterpartyName", counterparty);
        payload.put("currency", "USD");
        if (accountId != null) payload.put("defaultAccountId", accountId);
        if (notes != null) payload.put("notes", notes);
        return entry(id, occurredAt, "CREATION", amount, payload);
    }

    private static LoanJournalEntry topup(String id, long occurredAt, long amount) {
        return entry(id, occurredAt, "TOPUP", amount, Map.of());
    }

    private static LoanJournalEntry payment(String id, long occurredAt, long amount) {
        return entry(id, occurredAt, "PAYMENT", amount, Map.of());
    }

    private static LoanJournalEntry paymentAlias(String id, long occurredAt, long amount, String type) {
        return entry(id, occurredAt, type, amount, Map.of());
    }

    private static LoanJournalEntry adjustment(String id, long occurredAt, long amount) {
        return entry(id, occurredAt, "ADJUSTMENT", amount, Map.of("reason", "correction"));
    }

    private static LoanJournalEntry close(String id, long occurredAt) {
        return entry(id, occurredAt, "CLOSE", null, Map.of());
    }

    private static LoanJournalEntry reversal(String id, long occurredAt, String targetId) {
        return entry(
            id,
            occurredAt,
            "REVERSAL",
            null,
            Map.of("targetEventId", targetId, "reason", "mistake")
        );
    }

    private static LoanJournalEntry metadata(
        String id,
        long occurredAt,
        Change counterparty,
        Change account,
        Change notes
    ) {
        Map<String, Object> changes = new HashMap<>();
        if (counterparty.present()) changes.put("counterpartyName", counterparty.value());
        if (account.present()) changes.put("defaultAccountId", account.value());
        if (notes.present()) changes.put("notes", notes.value());
        return entry(id, occurredAt, "METADATA_CHANGED", null, Map.of("changes", changes));
    }

    private static LoanJournalEntry entry(
        String id,
        long occurredAt,
        String type,
        Long amount,
        Map<String, Object> payload
    ) {
        return new LoanJournalEntry(
            id,
            id,
            "loan-1",
            "owner-1",
            type,
            1,
            amount,
            null,
            null,
            null,
            occurredAt,
            occurredAt,
            null,
            null,
            payload
        );
    }

    private static Change absent() {
        return new Change(false, null);
    }

    private static Change clear() {
        return new Change(true, null);
    }

    private static Change value(String value) {
        return new Change(true, value);
    }

    private record Change(boolean present, String value) {}
}
