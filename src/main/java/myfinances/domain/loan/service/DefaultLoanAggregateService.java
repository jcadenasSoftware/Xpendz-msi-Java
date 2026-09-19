package myfinances.domain.loan.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.AdjustPrincipalCommand;
import myfinances.domain.loan.commands.CloseLoanCommand;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.FieldChange;
import myfinances.domain.loan.commands.LoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.commands.ReversePaymentCommand;
import myfinances.domain.loan.commands.UpdateMetadataCommand;
import myfinances.domain.loan.diagnostics.LoanDiagnosticCode;
import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.projection.LoanProjectionChange;
import myfinances.domain.loan.projection.LoanProjectionChangeType;
import myfinances.domain.loan.reducer.LoanReducer;
import myfinances.domain.loan.reducer.LoanReductionResult;
import myfinances.domain.loan.reducer.ReductionResultType;
import myfinances.domain.loan.repository.LoanRepository;
import myfinances.domain.loan.service.error.BusinessRuleViolation;
import myfinances.domain.loan.service.error.InvariantViolation;
import myfinances.domain.loan.service.error.LoanAggregateErrorCode;
import myfinances.domain.loan.service.error.LoanAggregateException;
import myfinances.domain.loan.service.error.UnexpectedFailure;
import myfinances.domain.loan.service.error.ValidationError;
import myfinances.domain.loan.snapshot.LoanSnapshot;

public final class DefaultLoanAggregateService implements LoanAggregateService {
    private final LoanRepository repository;
    private final LoanReducer reducer;

    public DefaultLoanAggregateService(LoanRepository repository, LoanReducer reducer) {
        this.repository = Objects.requireNonNull(repository);
        this.reducer = Objects.requireNonNull(reducer);
    }

    @Override
    public LoanCommandResult process(LoanCommand command) {
        try {
            return processCommand(command);
        } catch (LoanAggregateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new UnexpectedFailure(ex);
        }
    }

    private LoanCommandResult processCommand(LoanCommand command) {
        validateEnvelope(command);
        LoanCommandEnvelope envelope = command.envelope();

        Optional<LoanMovement> replayEvent = repository.findByOperationId(
            envelope.ownerId(),
            envelope.operationId()
        );
        if (replayEvent.isPresent()) {
            return replay(command, replayEvent.get());
        }

        List<LoanMovement> currentJournal = List.copyOf(repository.getJournal(
            envelope.ownerId(),
            envelope.loanId()
        ));
        LoanReductionResult currentReduction = null;
        LoanSnapshot previousSnapshot = null;

        if (!currentJournal.isEmpty()) {
            currentReduction = reducer.reduceCanonical(currentJournal);
            requireCurrentJournalValid(currentReduction);
            previousSnapshot = currentReduction.snapshot();
        } else if (!(command instanceof CreateLoanCommand)) {
            reducer.reduceCanonical(currentJournal);
            throw new BusinessRuleViolation(LoanAggregateErrorCode.LOAN_NOT_FOUND);
        }

        validateExpectedFingerprint(command, previousSnapshot);
        validateCommandRules(command, currentJournal, currentReduction, previousSnapshot);
        LoanMovement generatedEvent = buildEvent(command);

        List<LoanMovement> candidateJournal = new ArrayList<>(currentJournal);
        candidateJournal.add(generatedEvent);
        LoanReductionResult candidateReduction = reducer.reduceCanonical(candidateJournal);
        requireCandidateJournalValid(candidateReduction);

        List<LoanProjectionChange> projectionChanges = projectionChanges(command, generatedEvent);
        repository.appendEvent(generatedEvent);
        repository.replaceSnapshot(candidateReduction.snapshot());

        return new LoanCommandResult(
            Outcome.APPLIED,
            envelope.operationId(),
            envelope.commandType(),
            generatedEvent,
            previousSnapshot,
            candidateReduction.snapshot(),
            projectionChanges,
            candidateReduction.diagnostics()
        );
    }

    private LoanCommandResult replay(LoanCommand command, LoanMovement existingEvent) {
        LoanMovement candidate = buildEvent(command);
        if (!existingEvent.equals(candidate)) {
            throw new InvariantViolation(LoanAggregateErrorCode.OPERATION_CONFLICT);
        }

        List<LoanMovement> currentJournal = List.copyOf(repository.getJournal(
            existingEvent.ownerId(),
            existingEvent.loanId()
        ));
        LoanReductionResult currentReduction = reducer.reduceCanonical(currentJournal);
        requireCurrentJournalValid(currentReduction);
        LoanSnapshot currentSnapshot = currentReduction.snapshot();

        return new LoanCommandResult(
            Outcome.REPLAYED,
            existingEvent.operationId(),
            command.envelope().commandType(),
            existingEvent,
            currentSnapshot,
            currentSnapshot,
            List.of(new LoanProjectionChange(LoanProjectionChangeType.NONE, null)),
            currentReduction.diagnostics()
        );
    }

    private static void validateEnvelope(LoanCommand command) {
        if (command == null || command.envelope() == null) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_COMMAND);
        }
        LoanCommandEnvelope envelope = command.envelope();
        LoanCommandType expectedType = commandType(command);
        if (envelope.commandType() != expectedType) {
            throw new ValidationError(LoanAggregateErrorCode.COMMAND_TYPE_MISMATCH);
        }
        if (!isCanonicalUuid(envelope.operationId())) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_OPERATION_ID);
        }
        if (isBlank(envelope.loanId())) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_LOAN_ID);
        }
        if (isBlank(envelope.ownerId())) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_OWNER_ID);
        }
    }

    private static void validateExpectedFingerprint(LoanCommand command, LoanSnapshot previousSnapshot) {
        String expected = command.envelope().expectedJournalFingerprint();
        if (command instanceof CreateLoanCommand) {
            if (expected != null) {
                throw new ValidationError(LoanAggregateErrorCode.EXPECTED_FINGERPRINT_NOT_ALLOWED);
            }
            return;
        }
        if (expected == null || expected.isBlank()) {
            throw new ValidationError(LoanAggregateErrorCode.EXPECTED_FINGERPRINT_REQUIRED);
        }
        if (!expected.equals(previousSnapshot.journalFingerprint())) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.STALE_AGGREGATE_VERSION);
        }
    }

    private void validateCommandRules(
        LoanCommand command,
        List<LoanMovement> currentJournal,
        LoanReductionResult currentReduction,
        LoanSnapshot currentSnapshot
    ) {
        switch (command) {
            case CreateLoanCommand create -> validateCreate(create, currentJournal);
            case RegisterPaymentCommand payment -> validatePayment(payment, currentSnapshot);
            case AddPrincipalCommand topup -> validateTopup(topup);
            case AdjustPrincipalCommand adjustment -> validateAdjustment(adjustment, currentSnapshot);
            case UpdateMetadataCommand metadata -> validateMetadata(metadata, currentSnapshot);
            case ReversePaymentCommand reversal -> validateReversePayment(reversal, currentReduction);
            case CloseLoanCommand close -> validateClose(close, currentSnapshot, currentReduction);
        }
    }

    private static void validateCreate(CreateLoanCommand command, List<LoanMovement> currentJournal) {
        if (!currentJournal.isEmpty()) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.LOAN_ALREADY_EXISTS);
        }
        if (command.initialPrincipalCents() <= 0L) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_INITIAL_PRINCIPAL);
        }
        if (command.loanType() == null) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_LOAN_TYPE);
        }
        if (isBlank(command.counterpartyName())) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_COUNTERPARTY);
        }
        String currency = normalizedCurrency(command.currency());
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_CURRENCY);
        }
        validateOptionalIdentifier(command.defaultAccountId());
        validateOptionalIdentifier(command.transactionId());
    }

    private static void validatePayment(RegisterPaymentCommand command, LoanSnapshot snapshot) {
        if (command.amountCents() <= 0L) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_PAYMENT_AMOUNT);
        }
        if (snapshot.pendingCents() <= 0L) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.LOAN_HAS_NO_PENDING_BALANCE);
        }
        if (command.amountCents() > snapshot.pendingCents()) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.PAYMENT_EXCEEDS_PENDING);
        }
        validateOptionalIdentifier(command.accountId());
        validateOptionalIdentifier(command.transactionId());
    }

    private static void validateTopup(AddPrincipalCommand command) {
        if (command.amountCents() <= 0L) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_TOPUP_AMOUNT);
        }
        validateOptionalIdentifier(command.accountId());
        validateOptionalIdentifier(command.transactionId());
    }

    private static void validateAdjustment(AdjustPrincipalCommand command, LoanSnapshot snapshot) {
        if (command.deltaCents() == 0L) {
            throw new ValidationError(LoanAggregateErrorCode.ZERO_ADJUSTMENT);
        }
        if (isBlank(command.reason())) {
            throw new ValidationError(LoanAggregateErrorCode.ADJUSTMENT_REASON_REQUIRED);
        }
        long resultingPrincipal;
        try {
            resultingPrincipal = Math.addExact(snapshot.principalCents(), command.deltaCents());
        } catch (ArithmeticException ex) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.ARITHMETIC_OVERFLOW);
        }
        if (resultingPrincipal <= 0L) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.NON_POSITIVE_RESULTING_PRINCIPAL);
        }
        if (resultingPrincipal < snapshot.totalPaidCents()) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.PRINCIPAL_BELOW_TOTAL_PAID);
        }
        validateOptionalIdentifier(command.accountId());
        validateOptionalIdentifier(command.transactionId());
    }

    private static void validateMetadata(UpdateMetadataCommand command, LoanSnapshot snapshot) {
        FieldChange<String> counterparty = field(command.counterpartyName());
        FieldChange<String> account = field(command.defaultAccountId());
        FieldChange<String> notes = field(command.notes());
        if (!counterparty.present() && !account.present() && !notes.present()) {
            throw new ValidationError(LoanAggregateErrorCode.EMPTY_METADATA_CHANGE);
        }
        if (counterparty.present() && isBlank(counterparty.value())) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_COUNTERPARTY);
        }
        if (account.present()) {
            validateOptionalIdentifier(account.value());
        }

        boolean counterpartyChanged = counterparty.present()
            && !Objects.equals(normalizeRequired(counterparty.value()), snapshot.counterpartyName());
        boolean accountChanged = account.present()
            && !Objects.equals(account.value(), snapshot.defaultAccountId());
        boolean notesChanged = notes.present()
            && !Objects.equals(normalizeNullable(notes.value()), snapshot.notes());
        if (!counterpartyChanged && !accountChanged && !notesChanged) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.NO_EFFECTIVE_CHANGE);
        }
    }

    private void validateReversePayment(
        ReversePaymentCommand command,
        LoanReductionResult currentReduction
    ) {
        if (isBlank(command.targetPaymentEventId())) {
            throw new ValidationError(LoanAggregateErrorCode.TARGET_EVENT_NOT_FOUND);
        }
        if (isBlank(command.reason())) {
            throw new ValidationError(LoanAggregateErrorCode.REVERSAL_REASON_REQUIRED);
        }
        LoanMovement target = repository.findByEventId(
            command.envelope().ownerId(),
            command.targetPaymentEventId()
        ).orElse(null);
        if (target == null) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.TARGET_EVENT_NOT_FOUND);
        }
        if (!target.loanId().equals(command.envelope().loanId())
            || !target.ownerId().equals(command.envelope().ownerId())) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.CROSS_LOAN_TARGET);
        }
        if (target.eventType() != LoanEventType.PAYMENT) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.TARGET_NOT_PAYMENT);
        }
        boolean effective = currentReduction.effectiveEvents().stream()
            .anyMatch(event -> event.eventId().equals(target.eventId()));
        if (!effective) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.PAYMENT_ALREADY_REVERSED);
        }
    }

    private static void validateClose(
        CloseLoanCommand command,
        LoanSnapshot snapshot,
        LoanReductionResult currentReduction
    ) {
        if (snapshot.pendingCents() > 0L) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.LOAN_HAS_PENDING_BALANCE);
        }
        boolean alreadyClosed = currentReduction.effectiveEvents().stream()
            .anyMatch(event -> event.eventType() == LoanEventType.CLOSE);
        if (alreadyClosed) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.LOAN_ALREADY_EXPLICITLY_CLOSED);
        }
    }

    private static LoanMovement buildEvent(LoanCommand command) {
        LoanCommandEnvelope envelope = command.envelope();
        LoanEventType eventType;
        Long amountCents;
        String accountId;
        String transactionId;
        String note;
        LoanEventPayload payload;

        switch (command) {
            case CreateLoanCommand create -> {
                eventType = LoanEventType.CREATION;
                amountCents = create.initialPrincipalCents();
                accountId = create.defaultAccountId();
                transactionId = create.transactionId();
                note = normalizeNullable(create.notes());
                payload = new LoanEventPayload.CreationPayload(
                    create.loanType(),
                    normalizeRequired(create.counterpartyName()),
                    normalizedCurrency(create.currency()),
                    create.defaultAccountId(),
                    normalizeNullable(create.notes())
                );
            }
            case RegisterPaymentCommand payment -> {
                eventType = LoanEventType.PAYMENT;
                amountCents = payment.amountCents();
                accountId = payment.accountId();
                transactionId = payment.transactionId();
                note = normalizeNullable(payment.note());
                payload = new LoanEventPayload.PaymentPayload(null, null);
            }
            case AddPrincipalCommand topup -> {
                eventType = LoanEventType.TOPUP;
                amountCents = topup.amountCents();
                accountId = topup.accountId();
                transactionId = topup.transactionId();
                note = normalizeNullable(topup.note());
                payload = new LoanEventPayload.EmptyPayload();
            }
            case AdjustPrincipalCommand adjustment -> {
                eventType = LoanEventType.ADJUSTMENT;
                amountCents = adjustment.deltaCents();
                accountId = adjustment.accountId();
                transactionId = adjustment.transactionId();
                note = normalizeNullable(adjustment.note());
                payload = new LoanEventPayload.AdjustmentPayload(
                    normalizeRequired(adjustment.reason()),
                    null
                );
            }
            case UpdateMetadataCommand metadata -> {
                eventType = LoanEventType.METADATA_CHANGED;
                amountCents = null;
                accountId = null;
                transactionId = null;
                note = null;
                payload = new LoanEventPayload.MetadataChangedPayload(
                    new LoanEventPayload.MetadataChanges(
                        eventField(metadata.counterpartyName(), true, true),
                        eventField(metadata.defaultAccountId(), false, false),
                        eventField(metadata.notes(), true, false)
                    ),
                    null
                );
            }
            case ReversePaymentCommand reversal -> {
                eventType = LoanEventType.REVERSAL;
                amountCents = null;
                accountId = null;
                transactionId = null;
                note = normalizeNullable(reversal.note());
                payload = new LoanEventPayload.ReversalPayload(
                    reversal.targetPaymentEventId(),
                    normalizeRequired(reversal.reason())
                );
            }
            case CloseLoanCommand close -> {
                eventType = LoanEventType.CLOSE;
                amountCents = null;
                accountId = null;
                transactionId = null;
                note = normalizeNullable(close.note());
                payload = new LoanEventPayload.ClosePayload(normalizeNullable(close.reason()));
            }
        }

        return new LoanMovement(
            envelope.operationId(),
            envelope.operationId(),
            envelope.loanId(),
            envelope.ownerId(),
            eventType,
            1,
            amountCents,
            accountId,
            transactionId,
            note,
            envelope.occurredAt(),
            envelope.occurredAt(),
            envelope.actorId(),
            envelope.originId(),
            payload
        );
    }

    private static List<LoanProjectionChange> projectionChanges(
        LoanCommand command,
        LoanMovement event
    ) {
        LoanProjectionChange rebuild = new LoanProjectionChange(
            LoanProjectionChangeType.REBUILD_LOAN_SNAPSHOT,
            event.eventId()
        );
        if (command instanceof RegisterPaymentCommand) {
            return List.of(
                new LoanProjectionChange(LoanProjectionChangeType.ADD_PAYMENT_PROJECTION, event.eventId()),
                rebuild
            );
        }
        if (command instanceof ReversePaymentCommand reversal) {
            return List.of(
                new LoanProjectionChange(
                    LoanProjectionChangeType.REMOVE_PAYMENT_PROJECTION,
                    reversal.targetPaymentEventId()
                ),
                rebuild
            );
        }
        return List.of(rebuild);
    }

    private static void requireCurrentJournalValid(LoanReductionResult result) {
        if (result.type() == ReductionResultType.INVALID) {
            throw new InvariantViolation(
                LoanAggregateErrorCode.CURRENT_JOURNAL_INVALID,
                result.diagnostics()
            );
        }
        if (result.type() == ReductionResultType.INCOMPLETE || result.snapshot() == null) {
            throw new InvariantViolation(
                LoanAggregateErrorCode.CURRENT_JOURNAL_INCOMPLETE,
                result.diagnostics()
            );
        }
    }

    private static void requireCandidateJournalValid(LoanReductionResult result) {
        if (result.diagnostics().stream().anyMatch(diagnostic ->
            diagnostic.code() == LoanDiagnosticCode.ARITHMETIC_OVERFLOW
        )) {
            throw new BusinessRuleViolation(LoanAggregateErrorCode.ARITHMETIC_OVERFLOW);
        }
        if (result.type() == ReductionResultType.INVALID) {
            throw new InvariantViolation(
                LoanAggregateErrorCode.CANDIDATE_JOURNAL_INVALID,
                result.diagnostics()
            );
        }
        if (result.type() == ReductionResultType.INCOMPLETE || result.snapshot() == null) {
            throw new InvariantViolation(
                LoanAggregateErrorCode.CANDIDATE_JOURNAL_INCOMPLETE,
                result.diagnostics()
            );
        }
    }

    private static LoanCommandType commandType(LoanCommand command) {
        return switch (command) {
            case CreateLoanCommand ignored -> LoanCommandType.CREATE_LOAN;
            case RegisterPaymentCommand ignored -> LoanCommandType.REGISTER_PAYMENT;
            case AddPrincipalCommand ignored -> LoanCommandType.ADD_PRINCIPAL;
            case AdjustPrincipalCommand ignored -> LoanCommandType.ADJUST_PRINCIPAL;
            case UpdateMetadataCommand ignored -> LoanCommandType.UPDATE_METADATA;
            case ReversePaymentCommand ignored -> LoanCommandType.REVERSE_PAYMENT;
            case CloseLoanCommand ignored -> LoanCommandType.CLOSE_LOAN;
        };
    }

    private static <T> FieldChange<T> field(FieldChange<T> value) {
        return value == null ? new FieldChange<>(false, null) : value;
    }

    private static LoanEventPayload.FieldValue<String> eventField(
        FieldChange<String> change,
        boolean normalize,
        boolean trim
    ) {
        FieldChange<String> safe = field(change);
        String value = safe.value();
        if (normalize && value != null) {
            value = Normalizer.normalize(trim ? value.trim() : value, Normalizer.Form.NFC);
        }
        return new LoanEventPayload.FieldValue<>(safe.present(), value);
    }

    private static void validateOptionalIdentifier(String value) {
        if (value != null && value.isBlank()) {
            throw new ValidationError(LoanAggregateErrorCode.INVALID_IDENTIFIER);
        }
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) && (parsed.version() == 4 || parsed.version() == 7);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String normalizeRequired(String value) {
        return value == null ? null : Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static String normalizedCurrency(String value) {
        return value == null
            ? null
            : Normalizer.normalize(value.trim(), Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
