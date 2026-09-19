package myfinances.domain.loan.reducer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import myfinances.domain.loan.diagnostics.LoanDiagnostic;
import myfinances.domain.loan.diagnostics.LoanDiagnosticCategory;
import myfinances.domain.loan.diagnostics.LoanDiagnosticCode;
import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanJournalCanonicalizer;
import myfinances.domain.loan.journal.LoanJournalEntry;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.domain.loan.snapshot.LoanStatus;

public final class DefaultLoanReducer implements LoanReducer {
    private static final Comparator<LoanMovement> CANONICAL_ORDER = Comparator
        .comparingLong(LoanMovement::occurredAt)
        .thenComparingLong(LoanMovement::recordedAt)
        .thenComparing(LoanMovement::eventId);

    private static final Comparator<LoanDiagnostic> DIAGNOSTIC_ORDER = Comparator
        .comparing((LoanDiagnostic diagnostic) -> diagnostic.category().ordinal())
        .thenComparing(diagnostic -> diagnostic.code().name())
        .thenComparing(LoanDiagnostic::primaryEventId, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(LoanDiagnostic::relatedEventId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Set<LoanEventType> REVERSIBLE_TYPES = Set.of(
        LoanEventType.TOPUP,
        LoanEventType.PAYMENT,
        LoanEventType.ADJUSTMENT,
        LoanEventType.CLOSE
    );

    private final LoanJournalCanonicalizer canonicalizer;

    public DefaultLoanReducer() {
        this(new LoanJournalCanonicalizer());
    }

    DefaultLoanReducer(LoanJournalCanonicalizer canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
    }

    @Override
    public LoanReductionResult reduce(Collection<LoanJournalEntry> entries) {
        if (entries == null) {
            return result(
                ReductionResultType.INVALID,
                List.of(),
                List.of(new LoanDiagnostic(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD, null, null)),
                List.of(),
                List.of(),
                List.of()
            );
        }

        List<LoanMovement> canonicalized = new ArrayList<>();
        List<LoanDiagnostic> diagnostics = new ArrayList<>();
        for (LoanJournalEntry entry : entries) {
            LoanJournalCanonicalizer.CanonicalizationResult canonical = canonicalizer.canonicalize(entry);
            if (canonical.event() != null) {
                canonicalized.add(canonical.event());
            }
            diagnostics.addAll(canonical.diagnostics());
        }

        return reduceCanonicalized(canonicalized, diagnostics);
    }

    @Override
    public LoanReductionResult reduceCanonical(Collection<LoanMovement> events) {
        if (events == null) {
            return result(
                ReductionResultType.INVALID,
                List.of(),
                List.of(new LoanDiagnostic(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD, null, null)),
                List.of(),
                List.of(),
                List.of()
            );
        }
        List<LoanMovement> canonicalized = new ArrayList<>();
        List<LoanDiagnostic> diagnostics = new ArrayList<>();
        for (LoanMovement event : events) {
            LoanDiagnostic diagnostic = validateCanonicalEvent(event);
            if (diagnostic == null) {
                canonicalized.add(event);
            } else {
                diagnostics.add(diagnostic);
            }
        }
        return reduceCanonicalized(canonicalized, diagnostics);
    }

    private static LoanDiagnostic validateCanonicalEvent(LoanMovement event) {
        if (event == null) {
            return new LoanDiagnostic(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD, null, null);
        }
        if (event.eventSchemaVersion() != 1) {
            return new LoanDiagnostic(LoanDiagnosticCode.UNSUPPORTED_EVENT_VERSION, event.eventId(), null);
        }
        if (event.eventType() == null
            || isBlank(event.eventId())
            || isBlank(event.operationId())
            || isBlank(event.loanId())
            || isBlank(event.ownerId())
            || event.payload() == null) {
            return new LoanDiagnostic(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD, event.eventId(), null);
        }

        boolean valid = switch (event.eventType()) {
            case CREATION -> event.amountCents() != null
                && event.payload() instanceof LoanEventPayload.CreationPayload payload
                && payload.loanType() != null
                && !isBlank(payload.counterpartyName())
                && !isBlank(payload.currency());
            case TOPUP -> event.amountCents() != null
                && event.payload() instanceof LoanEventPayload.EmptyPayload;
            case PAYMENT -> event.amountCents() != null
                && event.payload() instanceof LoanEventPayload.PaymentPayload;
            case ADJUSTMENT -> event.amountCents() != null
                && event.amountCents() != 0L
                && event.payload() instanceof LoanEventPayload.AdjustmentPayload payload
                && !isBlank(payload.reason());
            case METADATA_CHANGED -> event.amountCents() == null
                && event.payload() instanceof LoanEventPayload.MetadataChangedPayload payload
                && validMetadataChanges(payload.changes());
            case REVERSAL -> event.amountCents() == null
                && event.payload() instanceof LoanEventPayload.ReversalPayload payload
                && !isBlank(payload.targetEventId())
                && !isBlank(payload.reason());
            case CLOSE -> event.amountCents() == null
                && event.payload() instanceof LoanEventPayload.ClosePayload;
        };
        return valid
            ? null
            : new LoanDiagnostic(LoanDiagnosticCode.INVALID_EVENT_PAYLOAD, event.eventId(), null);
    }

    private static boolean validMetadataChanges(LoanEventPayload.MetadataChanges changes) {
        if (changes == null
            || changes.counterpartyName() == null
            || changes.defaultAccountId() == null
            || changes.notes() == null) {
            return false;
        }
        boolean anyPresent = changes.counterpartyName().present()
            || changes.defaultAccountId().present()
            || changes.notes().present();
        return anyPresent
            && (!changes.counterpartyName().present() || !isBlank(changes.counterpartyName().value()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static LoanReductionResult reduceCanonicalized(
        List<LoanMovement> canonicalized,
        List<LoanDiagnostic> diagnostics
    ) {
        if (hasBlockingDiagnostics(diagnostics)) {
            canonicalized.sort(CANONICAL_ORDER);
            return result(resultType(diagnostics), canonicalized, diagnostics, List.of(), List.of(), List.of());
        }

        CollapseResult byEventId = collapseByEventId(canonicalized);
        diagnostics.addAll(byEventId.diagnostics());
        if (hasBlockingDiagnostics(diagnostics)) {
            return result(ReductionResultType.INVALID, byEventId.events(), diagnostics, List.of(), List.of(), byEventId.duplicates());
        }

        CollapseResult byOperationId = collapseByOperationId(byEventId.events());
        diagnostics.addAll(byOperationId.diagnostics());
        validateAggregateIdentity(byEventId.events(), diagnostics);
        List<LoanMovement> duplicates = new ArrayList<>(byEventId.duplicates());
        duplicates.addAll(byOperationId.duplicates());
        duplicates.sort(CANONICAL_ORDER);
        if (!byOperationId.diagnostics().isEmpty()) {
            return result(ReductionResultType.INVALID, byOperationId.events(), diagnostics, List.of(), List.of(), duplicates);
        }

        List<LoanMovement> normalized = new ArrayList<>(byOperationId.events());
        normalized.sort(CANONICAL_ORDER);
        validateCreation(normalized, diagnostics);

        ReversalResolution reversals = resolveReversals(normalized, diagnostics);
        ReductionResultType structuralType = resultType(diagnostics);
        if (structuralType != ReductionResultType.VALID) {
            return result(
                structuralType,
                normalized,
                diagnostics,
                reversals.effectiveEvents(),
                reversals.revertedEvents(),
                duplicates
            );
        }
        return foldFinancial(normalized, reversals, duplicates, diagnostics);
    }

    private static LoanReductionResult foldFinancial(
        List<LoanMovement> normalized,
        ReversalResolution reversals,
        List<LoanMovement> duplicates,
        List<LoanDiagnostic> structuralDiagnostics
    ) {
        List<LoanDiagnostic> diagnostics = new ArrayList<>(structuralDiagnostics);
        List<LoanMovement> effective = reversals.effectiveEvents();
        LoanMovement creation = effective.stream()
            .filter(event -> event.eventType() == LoanEventType.CREATION)
            .findFirst()
            .orElseThrow();
        LoanEventPayload.CreationPayload creationPayload =
            (LoanEventPayload.CreationPayload) creation.payload();

        long principal = 0L;
        long totalPaid = 0L;
        Long previousPending = null;
        Long closedAt = null;
        String counterpartyName = creationPayload.counterpartyName();
        String currency = creationPayload.currency();
        String defaultAccountId = creationPayload.defaultAccountId();
        String notes = creationPayload.notes();

        try {
            for (LoanMovement event : effective) {
                switch (event.eventType()) {
                    case CREATION -> {
                        if (event.amountCents() <= 0L) {
                            return financialFailure(
                                LoanDiagnosticCode.NON_POSITIVE_PRINCIPAL,
                                event,
                                normalized,
                                diagnostics,
                                reversals,
                                duplicates
                            );
                        }
                        principal = Math.addExact(principal, event.amountCents());
                    }
                    case TOPUP -> {
                        if (event.amountCents() <= 0L) {
                            return financialFailure(
                                LoanDiagnosticCode.INVALID_EVENT_PAYLOAD,
                                event,
                                normalized,
                                diagnostics,
                                reversals,
                                duplicates
                            );
                        }
                        principal = Math.addExact(principal, event.amountCents());
                    }
                    case PAYMENT -> {
                        if (event.amountCents() <= 0L) {
                            return financialFailure(
                                LoanDiagnosticCode.INVALID_EVENT_PAYLOAD,
                                event,
                                normalized,
                                diagnostics,
                                reversals,
                                duplicates
                            );
                        }
                        totalPaid = Math.addExact(totalPaid, event.amountCents());
                    }
                    case ADJUSTMENT -> {
                        if (event.amountCents() == 0L) {
                            return financialFailure(
                                LoanDiagnosticCode.INVALID_EVENT_PAYLOAD,
                                event,
                                normalized,
                                diagnostics,
                                reversals,
                                duplicates
                            );
                        }
                        principal = Math.addExact(principal, event.amountCents());
                        if (principal <= 0L) {
                            return financialFailure(
                                LoanDiagnosticCode.NON_POSITIVE_PRINCIPAL,
                                event,
                                normalized,
                                diagnostics,
                                reversals,
                                duplicates
                            );
                        }
                    }
                    case METADATA_CHANGED -> {
                        LoanEventPayload.MetadataChanges changes =
                            ((LoanEventPayload.MetadataChangedPayload) event.payload()).changes();
                        if (changes.counterpartyName().present()) {
                            counterpartyName = changes.counterpartyName().value();
                        }
                        if (changes.defaultAccountId().present()) {
                            defaultAccountId = changes.defaultAccountId().value();
                        }
                        if (changes.notes().present()) {
                            notes = changes.notes().value();
                        }
                    }
                    case CLOSE -> {
                        long pendingAtClose = Math.max(Math.subtractExact(principal, totalPaid), 0L);
                        if (pendingAtClose > 0L) {
                            diagnostics.add(new LoanDiagnostic(
                                LoanDiagnosticCode.PREMATURE_CLOSE,
                                event.eventId(),
                                null
                            ));
                        }
                    }
                    case REVERSAL -> {
                    }
                }

                long netBalance = Math.subtractExact(principal, totalPaid);
                long currentPending = Math.max(netBalance, 0L);
                if (previousPending != null && previousPending > 0L && currentPending == 0L) {
                    closedAt = event.occurredAt();
                }
                if (currentPending > 0L) {
                    closedAt = null;
                }
                previousPending = currentPending;
            }

            long netBalance = Math.subtractExact(principal, totalPaid);
            long pending = Math.max(netBalance, 0L);
            long overpaid = netBalance < 0L ? Math.subtractExact(0L, netBalance) : 0L;
            if (overpaid > 0L) {
                diagnostics.add(new LoanDiagnostic(LoanDiagnosticCode.OVERPAYMENT, null, null));
            }
            LoanStatus status = pending == 0L ? LoanStatus.CLOSED : LoanStatus.OPEN;
            long lastActivityAt = normalized.stream().mapToLong(LoanMovement::occurredAt).max().orElseThrow();
            String fingerprint = LoanJournalFingerprint.compute(normalized);
            LoanSnapshot snapshot = new LoanSnapshot(
                creation.loanId(),
                creation.ownerId(),
                creationPayload.loanType(),
                counterpartyName,
                currency,
                defaultAccountId,
                notes,
                principal,
                totalPaid,
                netBalance,
                pending,
                overpaid,
                status,
                closedAt,
                lastActivityAt,
                normalized.size(),
                fingerprint,
                1
            );
            return resultWithSnapshot(
                snapshot,
                normalized,
                diagnostics,
                effective,
                reversals.revertedEvents(),
                duplicates
            );
        } catch (ArithmeticException ex) {
            return financialFailure(
                LoanDiagnosticCode.ARITHMETIC_OVERFLOW,
                null,
                normalized,
                diagnostics,
                reversals,
                duplicates
            );
        } catch (IllegalArgumentException ex) {
            return financialFailure(
                LoanDiagnosticCode.INVALID_EVENT_PAYLOAD,
                null,
                normalized,
                diagnostics,
                reversals,
                duplicates
            );
        }
    }

    private static LoanReductionResult financialFailure(
        LoanDiagnosticCode code,
        LoanMovement event,
        List<LoanMovement> normalized,
        List<LoanDiagnostic> diagnostics,
        ReversalResolution reversals,
        List<LoanMovement> duplicates
    ) {
        List<LoanDiagnostic> failedDiagnostics = new ArrayList<>(diagnostics);
        failedDiagnostics.add(new LoanDiagnostic(code, event == null ? null : event.eventId(), null));
        return result(
            ReductionResultType.INVALID,
            normalized,
            failedDiagnostics,
            reversals.effectiveEvents(),
            reversals.revertedEvents(),
            duplicates
        );
    }

    private static CollapseResult collapseByEventId(List<LoanMovement> events) {
        Map<String, List<LoanMovement>> groups = new TreeMap<>();
        for (LoanMovement event : events) {
            groups.computeIfAbsent(event.eventId(), ignored -> new ArrayList<>()).add(event);
        }

        List<LoanMovement> unique = new ArrayList<>();
        List<LoanMovement> duplicates = new ArrayList<>();
        List<LoanDiagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, List<LoanMovement>> group : groups.entrySet()) {
            LoanMovement first = group.getValue().getFirst();
            boolean equivalent = group.getValue().stream().allMatch(first::equals);
            if (!equivalent) {
                diagnostics.add(new LoanDiagnostic(LoanDiagnosticCode.EVENT_ID_CONFLICT, group.getKey(), null));
                continue;
            }
            unique.add(first);
            for (int i = 1; i < group.getValue().size(); i++) {
                duplicates.add(first);
            }
        }
        unique.sort(CANONICAL_ORDER);
        duplicates.sort(CANONICAL_ORDER);
        return new CollapseResult(unique, duplicates, diagnostics);
    }

    private static CollapseResult collapseByOperationId(List<LoanMovement> events) {
        Map<String, List<LoanMovement>> groups = new TreeMap<>();
        for (LoanMovement event : events) {
            groups.computeIfAbsent(event.operationId(), ignored -> new ArrayList<>()).add(event);
        }

        List<LoanMovement> unique = new ArrayList<>();
        List<LoanMovement> duplicates = new ArrayList<>();
        List<LoanDiagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, List<LoanMovement>> group : groups.entrySet()) {
            List<LoanMovement> orderedGroup = new ArrayList<>(group.getValue());
            orderedGroup.sort(CANONICAL_ORDER);
            LoanMovement first = orderedGroup.getFirst();
            boolean equivalent = orderedGroup.stream().allMatch(event -> sameOperationEvent(first, event));
            if (!equivalent) {
                diagnostics.add(new LoanDiagnostic(LoanDiagnosticCode.OPERATION_CONFLICT, first.eventId(), null));
                continue;
            }
            unique.add(first);
            duplicates.addAll(orderedGroup.subList(1, orderedGroup.size()));
        }
        unique.sort(CANONICAL_ORDER);
        duplicates.sort(CANONICAL_ORDER);
        return new CollapseResult(unique, duplicates, diagnostics);
    }

    private static boolean sameOperationEvent(LoanMovement left, LoanMovement right) {
        return Objects.equals(left.operationId(), right.operationId())
            && Objects.equals(left.loanId(), right.loanId())
            && Objects.equals(left.ownerId(), right.ownerId())
            && left.eventType() == right.eventType()
            && left.eventSchemaVersion() == right.eventSchemaVersion()
            && Objects.equals(left.amountCents(), right.amountCents())
            && Objects.equals(left.accountId(), right.accountId())
            && Objects.equals(left.transactionId(), right.transactionId())
            && Objects.equals(left.note(), right.note())
            && left.occurredAt() == right.occurredAt()
            && left.recordedAt() == right.recordedAt()
            && Objects.equals(left.actorId(), right.actorId())
            && Objects.equals(left.originId(), right.originId())
            && Objects.equals(left.payload(), right.payload());
    }

    private static void validateAggregateIdentity(List<LoanMovement> events, List<LoanDiagnostic> diagnostics) {
        if (events.isEmpty()) {
            return;
        }
        LoanMovement first = events.getFirst();
        LoanMovement mismatch = events.stream()
            .filter(event -> !event.loanId().equals(first.loanId()) || !event.ownerId().equals(first.ownerId()))
            .findFirst()
            .orElse(null);
        if (mismatch != null) {
            diagnostics.add(new LoanDiagnostic(LoanDiagnosticCode.MIXED_AGGREGATES, first.eventId(), mismatch.eventId()));
        }
    }

    private static void validateCreation(List<LoanMovement> events, List<LoanDiagnostic> diagnostics) {
        List<LoanMovement> creations = events.stream()
            .filter(event -> event.eventType() == LoanEventType.CREATION)
            .toList();
        if (creations.isEmpty()) {
            diagnostics.add(new LoanDiagnostic(LoanDiagnosticCode.MISSING_CREATION, null, null));
            return;
        }
        if (creations.size() > 1) {
            diagnostics.add(new LoanDiagnostic(
                LoanDiagnosticCode.MULTIPLE_CREATIONS,
                creations.get(0).eventId(),
                creations.get(1).eventId()
            ));
            return;
        }
        LoanMovement creation = creations.getFirst();
        LoanMovement preceding = events.stream()
            .filter(event -> event.eventType() != LoanEventType.CREATION)
            .filter(event -> CANONICAL_ORDER.compare(event, creation) < 0)
            .findFirst()
            .orElse(null);
        if (preceding != null) {
            diagnostics.add(new LoanDiagnostic(LoanDiagnosticCode.EVENT_BEFORE_CREATION, preceding.eventId(), creation.eventId()));
        }
    }

    private static ReversalResolution resolveReversals(
        List<LoanMovement> normalized,
        List<LoanDiagnostic> diagnostics
    ) {
        Map<String, LoanMovement> eventsById = new LinkedHashMap<>();
        for (LoanMovement event : normalized) {
            eventsById.put(event.eventId(), event);
        }

        Map<String, List<LoanMovement>> reversalsByTarget = new TreeMap<>();
        for (LoanMovement reversal : normalized) {
            if (reversal.eventType() != LoanEventType.REVERSAL) {
                continue;
            }
            LoanEventPayload.ReversalPayload payload = (LoanEventPayload.ReversalPayload) reversal.payload();
            LoanMovement target = eventsById.get(payload.targetEventId());
            if (target == null) {
                diagnostics.add(new LoanDiagnostic(
                    LoanDiagnosticCode.UNRESOLVED_REVERSAL,
                    reversal.eventId(),
                    payload.targetEventId()
                ));
                continue;
            }
            if (!target.loanId().equals(reversal.loanId()) || !target.ownerId().equals(reversal.ownerId())) {
                diagnostics.add(new LoanDiagnostic(
                    LoanDiagnosticCode.CROSS_LOAN_REVERSAL,
                    reversal.eventId(),
                    target.eventId()
                ));
                continue;
            }
            if (!REVERSIBLE_TYPES.contains(target.eventType())) {
                diagnostics.add(new LoanDiagnostic(
                    LoanDiagnosticCode.NON_REVERSIBLE_TARGET,
                    reversal.eventId(),
                    target.eventId()
                ));
                continue;
            }
            reversalsByTarget.computeIfAbsent(target.eventId(), ignored -> new ArrayList<>()).add(reversal);
        }

        Set<String> reversedIds = new HashSet<>();
        for (Map.Entry<String, List<LoanMovement>> entry : reversalsByTarget.entrySet()) {
            List<LoanMovement> reversals = entry.getValue();
            reversals.sort(CANONICAL_ORDER);
            if (reversals.size() > 1) {
                diagnostics.add(new LoanDiagnostic(
                    LoanDiagnosticCode.MULTIPLE_REVERSALS,
                    reversals.get(0).eventId(),
                    reversals.get(1).eventId()
                ));
            } else {
                reversedIds.add(entry.getKey());
            }
        }

        List<LoanMovement> reverted = normalized.stream()
            .filter(event -> reversedIds.contains(event.eventId()))
            .sorted(CANONICAL_ORDER)
            .toList();
        List<LoanMovement> effective = normalized.stream()
            .filter(event -> event.eventType() != LoanEventType.REVERSAL)
            .filter(event -> !reversedIds.contains(event.eventId()))
            .sorted(CANONICAL_ORDER)
            .toList();
        return new ReversalResolution(effective, reverted);
    }

    private static boolean hasBlockingDiagnostics(List<LoanDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic ->
            diagnostic.category() == LoanDiagnosticCategory.INVALIDATING_ERROR
                || diagnostic.category() == LoanDiagnosticCategory.INCOMPLETE_STATE
        );
    }

    private static ReductionResultType resultType(List<LoanDiagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.category() == LoanDiagnosticCategory.INVALIDATING_ERROR)) {
            return ReductionResultType.INVALID;
        }
        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.category() == LoanDiagnosticCategory.INCOMPLETE_STATE)) {
            return ReductionResultType.INCOMPLETE;
        }
        return ReductionResultType.VALID;
    }

    private static LoanReductionResult result(
        ReductionResultType type,
        List<LoanMovement> normalized,
        List<LoanDiagnostic> diagnostics,
        List<LoanMovement> effective,
        List<LoanMovement> reverted,
        List<LoanMovement> duplicates
    ) {
        List<LoanDiagnostic> orderedDiagnostics = new ArrayList<>(diagnostics);
        orderedDiagnostics.sort(DIAGNOSTIC_ORDER);
        return new LoanReductionResult(
            type,
            null,
            List.copyOf(normalized),
            List.copyOf(orderedDiagnostics),
            List.copyOf(effective),
            List.copyOf(reverted),
            List.copyOf(duplicates)
        );
    }

    private static LoanReductionResult resultWithSnapshot(
        LoanSnapshot snapshot,
        List<LoanMovement> normalized,
        List<LoanDiagnostic> diagnostics,
        List<LoanMovement> effective,
        List<LoanMovement> reverted,
        List<LoanMovement> duplicates
    ) {
        List<LoanDiagnostic> orderedDiagnostics = new ArrayList<>(diagnostics);
        orderedDiagnostics.sort(DIAGNOSTIC_ORDER);
        return new LoanReductionResult(
            ReductionResultType.VALID,
            snapshot,
            List.copyOf(normalized),
            List.copyOf(orderedDiagnostics),
            List.copyOf(effective),
            List.copyOf(reverted),
            List.copyOf(duplicates)
        );
    }

    private record CollapseResult(
        List<LoanMovement> events,
        List<LoanMovement> duplicates,
        List<LoanDiagnostic> diagnostics
    ) {}

    private record ReversalResolution(
        List<LoanMovement> effectiveEvents,
        List<LoanMovement> revertedEvents
    ) {}
}
