package myfinances.infrastructure.loan.replay;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.aggregate.Outcome;
import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.AdjustPrincipalCommand;
import myfinances.domain.loan.commands.CloseLoanCommand;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.LoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.reducer.DefaultLoanReducer;
import myfinances.domain.loan.service.DefaultLoanAggregateService;
import myfinances.domain.loan.service.error.LoanAggregateException;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.domain.loan.snapshot.LoanStatus;
import myfinances.infrastructure.loan.jdbc.JdbcLoanAggregateExecutor;
import myfinances.infrastructure.loan.jdbc.JdbcLoanRepositoryAdapter;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;
import myfinances.infrastructure.loan.migration.CanonicalLoanEventIds;
import myfinances.infrastructure.loan.projection.jdbc.DefaultLoanProjector;
import myfinances.infrastructure.loan.projection.jdbc.JdbcLoanProjectionQueryRepository;

public final class HistoricalLoanReplayTool {
    private static final Comparator<LegacyEvent> LEGACY_ORDER = Comparator
        .comparingLong(LegacyEvent::occurredAt)
        .thenComparingLong(LegacyEvent::createdAt)
        .thenComparing(LegacyEvent::sourceKey);

    private final SqliteDatabase database;
    private final JdbcLoanRepositoryAdapter repository;
    private final DefaultLoanProjector projector;
    private final LoanApplicationService service;
    private final LoanProjectionQueryRepository queryRepository;

    public HistoricalLoanReplayTool(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.repository = new JdbcLoanRepositoryAdapter(database);
        this.projector = new DefaultLoanProjector(database);
        this.service = new LoanApplicationService(
            new JdbcLoanAggregateExecutor(repository, new DefaultLoanAggregateService(repository, new DefaultLoanReducer())),
            this.projector
        );
        this.queryRepository = new JdbcLoanProjectionQueryRepository(database);
    }

    public ReplayResult replay(String ownerId, String loanId) {
        long startedAt = System.currentTimeMillis();

        LegacyLoanRow loan = readLoan(ownerId, loanId);
        if (loan == null) {
            logReplayResult("REPLAY_RESULT", ownerId, loanId, null, null, null, null, "LOAN_NOT_FOUND", List.of("No existe el préstamo legacy"));
            return ReplayResult.failedResult(ownerId, loanId, "LOAN_NOT_FOUND", List.of("No existe el préstamo legacy"), System.currentTimeMillis() - startedAt);
        }

        LegacyHistory history = readHistory(loan);
        logReplayStart(loan, history);
        PlanResult planResult = buildPlan(loan, history);
        if (!planResult.ok()) {
            logReplayResult("REPLAY_RESULT", ownerId, loanId, loan, history, null, null, planResult.code(), planResult.errors());
            return ReplayResult.failedResult(ownerId, loanId, planResult.code(), planResult.errors(), System.currentTimeMillis() - startedAt);
        }

        ReplayPlan plan = planResult.plan();
        CanonicalBackup backup = captureCanonicalState(ownerId, loanId);

        try {
            deleteCanonicalState(ownerId, loanId);
            LoanSnapshot snapshot = executeReplay(loan, plan);

            if (snapshot == null) {
                throw new IllegalStateException("El replay no produjo snapshot");
            }

            validateSnapshotAgainstLegacy(loan, snapshot, plan);
            projector.rebuild(ownerId, loanId, repository, new DefaultLoanReducer());
            validateProjections(ownerId, loanId, snapshot);

            LoanSummaryProjection summary = queryRepository.getSummaryProjection(ownerId, loanId);
            logProjectionUpdated(ownerId, loanId, summary);
            logReplayResult("REPLAY_RESULT", ownerId, loanId, loan, history, plan, snapshot, "SUCCESS", List.of());
            return ReplayResult.successResult(
                ownerId,
                loanId,
                plan.timeline().size(),
                plan.expectedPaymentCount(),
                summary == null ? 0 : summary.paymentCount(),
                summary,
                snapshot,
                System.currentTimeMillis() - startedAt
            );
        } catch (Exception ex) {
            try {
                restoreCanonicalState(ownerId, loanId, backup);
            } catch (Exception restoreEx) {
                ex.addSuppressed(restoreEx);
            }
            logReplayResult("REPLAY_RESULT", ownerId, loanId, loan, history, plan, null, "REPLAY_ABORTED", List.of(message(ex)));
            return ReplayResult.failedResult(ownerId, loanId, "REPLAY_ABORTED", List.of(message(ex)), System.currentTimeMillis() - startedAt);
        }
    }

    private LoanSnapshot executeReplay(LegacyLoanRow loan, ReplayPlan plan) {
        String fingerprint = null;
        LoanSnapshot snapshot = null;
        for (LegacyEvent event : plan.timeline()) {
            LoanCommand command = buildCommand(loan, event, fingerprint);
            LoanCommandResult result = service.process(command);
            if (result.currentSnapshot() == null || result.outcome() != Outcome.APPLIED) {
                throw new IllegalStateException("El evento " + event.type() + " no se aplicó");
            }
            fingerprint = result.currentSnapshot().journalFingerprint();
            snapshot = result.currentSnapshot();
        }
        return snapshot;
    }

    private LoanCommand buildCommand(LegacyLoanRow loan, LegacyEvent event, String fingerprint) {
        LoanCommandEnvelope envelope = new LoanCommandEnvelope(
            commandType(event.type()),
            sharedOperationId(loan.loanId(), event),
            loan.loanId(),
            loan.ownerId(),
            fingerprint,
            event.occurredAt(),
            loan.ownerId(),
            loan.ownerId()
        );

        return switch (event.type()) {
            case CREATION -> new CreateLoanCommand(
                envelope,
                loan.loanType(),
                event.amountCents(),
                loan.counterpartyName(),
                loan.currency(),
                event.accountId(),
                event.transactionId(),
                loan.notes()
            );
            case TOPUP -> new AddPrincipalCommand(envelope, event.amountCents(), event.accountId(), event.transactionId(), event.note());
            case ADJUSTMENT -> new AdjustPrincipalCommand(
                envelope,
                event.amountCents(),
                event.note() == null || event.note().isBlank() ? "Corrección histórica" : event.note(),
                event.accountId(),
                event.transactionId(),
                event.note()
            );
            case PAYMENT -> new RegisterPaymentCommand(envelope, event.amountCents(), event.accountId(), event.transactionId(), event.note());
            case CLOSE -> new CloseLoanCommand(envelope, event.note() == null || event.note().isBlank() ? "Cierre histórico" : event.note(), event.note());
        };
    }

    private static LoanCommandType commandType(LegacyEventType type) {
        return switch (type) {
            case CREATION -> LoanCommandType.CREATE_LOAN;
            case TOPUP -> LoanCommandType.ADD_PRINCIPAL;
            case ADJUSTMENT -> LoanCommandType.ADJUST_PRINCIPAL;
            case PAYMENT -> LoanCommandType.REGISTER_PAYMENT;
            case CLOSE -> LoanCommandType.CLOSE_LOAN;
        };
    }

    /**
     * Pagos transportados ({@code pay:<docId>}) adoptan el docId de
     * {@code loanPayments} cuando es un UUID v4/v7 canónico: ese docId ya es el
     * eventId del dispositivo origen y preservarlo hace que la reversión elimine
     * el documento correcto en cualquier dispositivo. El resto de eventos usa el
     * id determinístico compartido (Android/Desktop producen el mismo
     * operationId).
     */
    private String sharedOperationId(String loanId, LegacyEvent event) {
        if (event.type() == LegacyEventType.PAYMENT && event.sourceKey().startsWith("pay:")) {
            return CanonicalLoanEventIds.forTransportPayment(
                loanId,
                event.sourceKey().substring("pay:".length()),
                event.occurredAt()
            );
        }
        return CanonicalLoanEventIds.deterministic(loanId, event.type().name(), event.sourceKey(), event.occurredAt());
    }

    private PlanResult buildPlan(LegacyLoanRow loan, LegacyHistory history) {
        List<LegacyEvent> timeline = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Optional<LegacyEvent> creation = findCreation(loan, history);
        if (creation.isEmpty()) {
            errors.add("No existe un evento de creación recuperable");
            return PlanResult.failed("CREATION_NOT_FOUND", errors);
        }
        timeline.add(creation.get());

        for (LegacyMovementRow movement : history.movements()) {
            if ("CREATION".equalsIgnoreCase(movement.movementType())) {
                continue;
            }
            eventFromMovement(movement, loan).ifPresent(timeline::add);
        }

        for (LegacyPaymentRow payment : history.payments()) {
            if (payment.linkedTransactionId() != null && !payment.linkedTransactionId().isBlank()) {
                boolean used = history.movements().stream().anyMatch(m -> payment.linkedTransactionId().equals(m.linkedTransactionId()));
                if (used) {
                    continue;
                }
            }
            timeline.add(LegacyEvent.payment(payment));
        }

        Map<String, LegacyEvent> preservedAdjustments = readPriorSyntheticAdjustments(loan);
        for (LegacyTransactionRow tx : history.transactions()) {
            if (history.movements().stream().anyMatch(m -> tx.id().equals(m.linkedTransactionId()))) {
                continue;
            }
            if (history.payments().stream().anyMatch(p -> tx.id().equals(p.linkedTransactionId()))) {
                continue;
            }
            LegacyEvent preserved = preservedAdjustments.get(tx.id());
            if (preserved != null) {
                timeline.add(preserved);
                continue;
            }
            // La misma operación puede llegar por dos transportes: un movimiento
            // con linked_transaction_id NULL y la transacción suelta. Emitir un
            // evento por cada uno duplica el hecho financiero en el journal;
            // cuando la firma coincide (tipo, monto, ocurrido y cuenta) la
            // transacción reclama el evento ya emitido en lugar de crear otro.
            if (attachTransactionToEmittedEvent(timeline, tx)) {
                continue;
            }
            eventFromTransaction(tx, loan.loanType()).ifPresent(timeline::add);
        }

        timeline.sort(LEGACY_ORDER);

        if (!hasCreationFirst(timeline)) {
            return PlanResult.failed("PAYMENT_BEFORE_CREATION", List.of("Un pago histórico ocurre antes de la creación"));
        }

        TimelineState initial = simulate(timeline);
        if (initial == null) {
            return validateTimeline(loan, timeline, errors);
        }

        List<LegacyEvent> synthesized = synthesizeFinalStateIfNeeded(loan, history, timeline, initial);
        if (synthesized == null) {
            return PlanResult.failed("REMOTE_STATE_UNRECONSTRUCTABLE", List.of("El snapshot remoto no puede reconstruirse con la historia local"));
        }
        return validateTimeline(loan, synthesized, errors);
    }

    private List<LegacyEvent> synthesizeFinalStateIfNeeded(
            LegacyLoanRow loan,
            LegacyHistory history,
            List<LegacyEvent> timeline,
            TimelineState state
    ) {
        List<LegacyEvent> out = new ArrayList<>(timeline);
        long nextOccurredAt = Math.max(loan.updatedAtEpochSec(), out.get(out.size() - 1).occurredAt() + 1);
        if (state.principal() != loan.principalCents()) {
            long deltaCents = loan.principalCents() - state.principal();
            Optional<LegacyTransactionRow> reusable = findReusablePrincipalTransaction(
                loan, history, timeline, deltaCents
            );
            LegacyEvent adjustment = LegacyEvent.syntheticAdjustment(loan, deltaCents, nextOccurredAt);
            out.add(reusable.map(tx -> adjustment.withTransactionId(tx.id())).orElse(adjustment));
            nextOccurredAt++;
            state = simulate(out);
            if (state == null) {
                return null;
            }
        }

        String desiredStatus = loan.status() == null ? "OPEN" : loan.status().toUpperCase(Locale.ROOT);
        if ("CLOSED".equals(desiredStatus)) {
            if (state.pending() > 0L) {
                return null;
            }
            if (!state.closed()) {
                out.add(LegacyEvent.syntheticClose(loan, nextOccurredAt));
                state = simulate(out);
                if (state == null || !state.closed()) {
                    return null;
                }
            }
        } else if (state.closed()) {
            return null;
        }

        return out;
    }

    private Map<String, LegacyEvent> readPriorSyntheticAdjustments(LegacyLoanRow loan) {
        Map<String, LegacyEvent> out = new HashMap<>();
        String sourceKey = "synth:adjust:" + loan.loanId();
        for (LoanMovement event : repository.getJournal(loan.ownerId(), loan.loanId())) {
            if (event.eventType() != LoanEventType.ADJUSTMENT
                || event.transactionId() == null
                || event.amountCents() == null) {
                continue;
            }
            String expectedEventId = CanonicalLoanEventIds.deterministic(
                loan.loanId(), "ADJUSTMENT", sourceKey, event.occurredAt()
            );
            if (!expectedEventId.equals(event.eventId())) {
                continue;
            }
            out.put(event.transactionId(), new LegacyEvent(
                LegacyEventType.ADJUSTMENT,
                sourceKey,
                loan.loanId(),
                loan.ownerId(),
                event.occurredAt(),
                event.recordedAt(),
                event.accountId(),
                event.transactionId(),
                event.amountCents(),
                event.note()
            ));
        }
        return out;
    }

    private Optional<LegacyTransactionRow> findReusablePrincipalTransaction(
            LegacyLoanRow loan,
            LegacyHistory history,
            List<LegacyEvent> timeline,
            long deltaCents
    ) {
        Set<String> usedTransactionIds = new HashSet<>();
        for (LegacyEvent event : timeline) {
            if (event.transactionId() != null) {
                usedTransactionIds.add(event.transactionId());
            }
        }

        Set<String> sameLoanReferences = new HashSet<>();
        for (LegacyMovementRow movement : history.movements()) {
            if (movement.linkedTransactionId() != null) {
                sameLoanReferences.add(movement.linkedTransactionId());
            }
        }
        for (LegacyPaymentRow payment : history.payments()) {
            if (payment.linkedTransactionId() != null && !payment.linkedTransactionId().isBlank()) {
                sameLoanReferences.add(payment.linkedTransactionId());
            }
        }

        Set<String> claimedElsewhere = readJournalTransactionIds(loan.ownerId(), loan.loanId());
        claimedElsewhere.addAll(readOtherLoanTransportTransactionIds(loan.ownerId(), loan.loanId()));

        List<LegacyTransactionRow> explicit = new ArrayList<>();
        List<LegacyTransactionRow> unreferenced = new ArrayList<>();
        for (LegacyTransactionRow tx : readLoanKindTransactions(loan.ownerId(), loan.accountId())) {
            if (usedTransactionIds.contains(tx.id()) || claimedElsewhere.contains(tx.id())) {
                continue;
            }
            if (!isCompatiblePrincipalTransaction(tx, loan, deltaCents) || !isWithinObservedLoanUpdate(tx, loan)) {
                continue;
            }
            if (sameLoanReferences.contains(tx.id())) {
                explicit.add(tx);
            } else {
                unreferenced.add(tx);
            }
        }

        if (explicit.size() == 1) {
            return Optional.of(explicit.get(0));
        }
        if (!explicit.isEmpty() || unreferenced.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(unreferenced.get(0));
    }

    private boolean isCompatiblePrincipalTransaction(LegacyTransactionRow tx, LegacyLoanRow loan, long deltaCents) {
        if (!Objects.equals(tx.accountId(), loan.accountId())) {
            return false;
        }
        String prefix = loan.loanType() == LoanType.LENT ? "LOAN_LENT_" : "LOAN_BORROWED_";
        String kind = tx.kind().toUpperCase(Locale.ROOT);
        if (!kind.startsWith(prefix) || (!kind.contains("TOPUP") && !kind.contains("CORRECTION"))) {
            return false;
        }
        return eventFromTransaction(tx, loan.loanType())
            .map(event -> event.amountCents() == deltaCents)
            .orElse(false);
    }

    private static boolean isWithinObservedLoanUpdate(LegacyTransactionRow tx, LegacyLoanRow loan) {
        long observedUpdateAt = loan.updatedAtEpochSec();
        return tx.occurredAtEpochSec() >= loan.occurredAtEpochSec()
            && tx.occurredAtEpochSec() <= observedUpdateAt
            && tx.createdAtEpochSec() >= loan.createdAtEpochSec()
            && tx.createdAtEpochSec() <= observedUpdateAt
            && tx.updatedAtEpochSec() <= observedUpdateAt;
    }

    private Set<String> readOtherLoanTransportTransactionIds(String ownerId, String excludeLoanId) {
        String sql =
            "SELECT linked_transaction_id FROM loan_movements " +
            "WHERE user_uid = ? AND loan_id <> ? AND linked_transaction_id IS NOT NULL " +
            "UNION " +
            "SELECT linked_transaction_id FROM loan_payments " +
            "WHERE user_uid = ? AND loan_id <> ? AND linked_transaction_id IS NOT NULL";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, excludeLoanId);
            statement.setString(3, ownerId);
            statement.setString(4, excludeLoanId);
            try (ResultSet result = statement.executeQuery()) {
                Set<String> ids = new HashSet<>();
                while (result.next()) {
                    ids.add(result.getString(1));
                }
                return ids;
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static TimelineState simulate(List<LegacyEvent> timeline) {
        long principal = 0L;
        long totalPaid = 0L;
        int paymentCount = 0;
        long lastPaymentAt = 0L;
        boolean creationSeen = false;
        boolean closed = false;

        for (LegacyEvent event : timeline) {
            switch (event.type()) {
                case CREATION -> {
                    if (creationSeen) {
                        return null;
                    }
                    creationSeen = true;
                    principal = event.amountCents();
                }
                case TOPUP, ADJUSTMENT -> principal = Math.addExact(principal, event.amountCents());
                case PAYMENT -> {
                    totalPaid = Math.addExact(totalPaid, event.amountCents());
                    paymentCount++;
                    lastPaymentAt = Math.max(lastPaymentAt, event.occurredAt());
                    if (principal - totalPaid < 0L) {
                        return null;
                    }
                }
                case CLOSE -> closed = true;
            }
        }

        long pending = Math.max(0L, principal - totalPaid);
        LoanStatus status = closed || pending == 0L ? LoanStatus.CLOSED : LoanStatus.OPEN;
        int progress = principal <= 0L ? 0 : (int) Math.min(100L, (totalPaid * 100L) / principal);
        return new TimelineState(principal, totalPaid, pending, paymentCount, lastPaymentAt, closed, status, progress);
    }

    private Optional<LegacyEvent> findCreation(LegacyLoanRow loan, LegacyHistory history) {
        Optional<LegacyMovementRow> explicitCreation = history.movements().stream()
            .filter(movement -> "CREATION".equalsIgnoreCase(movement.movementType()))
            .min(Comparator.comparingLong(LegacyMovementRow::occurredAtEpochSec)
                .thenComparingLong(LegacyMovementRow::createdAtEpochSec)
                .thenComparing(LegacyMovementRow::id));

        LegacyEvent chosen;
        boolean inferredTimestamp;
        if (explicitCreation.isPresent()) {
            chosen = LegacyEvent.creation(explicitCreation.get(), loan);
            inferredTimestamp = false;
        } else {
            Optional<LoanMovement> priorCreation = repository.getJournal(loan.ownerId(), loan.loanId()).stream()
                .filter(event -> event.eventType() == LoanEventType.CREATION)
                .findFirst();
            if (priorCreation.isPresent()) {
                LoanMovement event = priorCreation.get();
                chosen = LegacyEvent.creationFromJournal(loan, event.occurredAt(), event.amountCents());
            } else {
                chosen = LegacyEvent.creation(loan);
            }
            inferredTimestamp = true;
        }

        if (inferredTimestamp) {
            long earliestPaymentAt = earliestPaymentAt(history);
            if (earliestPaymentAt != Long.MAX_VALUE && chosen.occurredAt() > earliestPaymentAt) {
                chosen = chosen.withOccurredAt(earliestPaymentAt - 1L);
            }
        }
        if (chosen.transactionId() == null) {
            String linked = firstCreationTransactionId(history, chosen.occurredAt());
            if (linked != null) {
                chosen = chosen.withTransactionId(linked);
            }
        }
        return Optional.of(chosen);
    }

    private static long earliestPaymentAt(LegacyHistory history) {
        long earliest = Long.MAX_VALUE;
        for (LegacyMovementRow movement : history.movements()) {
            String type = movement.movementType().toUpperCase(Locale.ROOT);
            if ("PAYMENT".equals(type) || "PAYMENT_IN".equals(type) || "PAYMENT_OUT".equals(type)) {
                earliest = Math.min(earliest, movement.occurredAtEpochSec());
            }
        }
        for (LegacyPaymentRow payment : history.payments()) {
            earliest = Math.min(earliest, payment.occurredAtEpochSec());
        }
        return earliest;
    }

    private static String firstCreationTransactionId(LegacyHistory history, long occurredAt) {
        return history.transactions().stream()
            .filter(tx -> {
                String kind = tx.kind().toUpperCase(Locale.ROOT);
                return ("LOAN_LENT_OUT".equals(kind) || "LOAN_BORROWED_IN".equals(kind)) && tx.occurredAtEpochSec() == occurredAt;
            })
            .min(Comparator.comparingLong(LegacyTransactionRow::createdAtEpochSec)
                .thenComparing(LegacyTransactionRow::id))
            .map(LegacyTransactionRow::id)
            .orElse(null);
    }

    private Optional<LegacyEvent> eventFromMovement(LegacyMovementRow movement, LegacyLoanRow loan) {
        return switch (movement.movementType().toUpperCase(Locale.ROOT)) {
            case "TOPUP" -> Optional.of(LegacyEvent.topup(movement, loan));
            case "PAYMENT_IN", "PAYMENT_OUT" -> Optional.of(LegacyEvent.payment(movement, loan));
            case "CLOSE" -> Optional.of(LegacyEvent.close(movement));
            default -> Optional.empty();
        };
    }

    /**
     * Reclama la transacción para un evento ya emitido con la misma firma
     * financiera exacta (tipo equivalente, monto absoluto, {@code occurred_at}
     * y cuenta). Devuelve {@code true} cuando la fusión se aplicó; con
     * cualquier divergencia la transacción sigue su camino normal de emisión.
     */
    private static boolean attachTransactionToEmittedEvent(List<LegacyEvent> timeline, LegacyTransactionRow tx) {
        LegacyEventType emittedType = switch (tx.kind().toUpperCase(Locale.ROOT)) {
            case "LOAN_LENT_TOPUP", "LOAN_BORROWED_TOPUP" -> LegacyEventType.TOPUP;
            case "LOAN_REPAYMENT_PRINCIPAL_IN", "LOAN_REPAYMENT_PRINCIPAL_OUT" -> LegacyEventType.PAYMENT;
            default -> tx.kind().toUpperCase(Locale.ROOT).contains("CORRECTION")
                ? LegacyEventType.ADJUSTMENT
                : null;
        };
        if (emittedType == null) {
            return false;
        }
        for (int i = 0; i < timeline.size(); i++) {
            LegacyEvent event = timeline.get(i);
            if (event.type() == emittedType
                && event.transactionId() == null
                && Math.abs(event.amountCents()) == tx.amountCents()
                && event.occurredAt() == tx.occurredAtEpochSec()
                && (event.accountId() == null || event.accountId().equals(tx.accountId()))) {
                timeline.set(i, event.withTransactionId(tx.id()));
                return true;
            }
        }
        return false;
    }

    private Optional<LegacyEvent> eventFromTransaction(LegacyTransactionRow tx, LoanType loanType) {
        String kind = tx.kind().toUpperCase(Locale.ROOT);
        if ("LOAN_LENT_TOPUP".equals(kind) || "LOAN_BORROWED_TOPUP".equals(kind)) {
            return Optional.of(LegacyEvent.topup(tx, loanType));
        }
        if (kind.contains("CORRECTION")) {
            return Optional.of(LegacyEvent.adjustment(tx, loanType));
        }
        if ("LOAN_REPAYMENT_PRINCIPAL_IN".equals(kind) || "LOAN_REPAYMENT_PRINCIPAL_OUT".equals(kind)) {
            return Optional.of(LegacyEvent.payment(tx, loanType));
        }
        return Optional.empty();
    }

    private boolean hasCreationFirst(List<LegacyEvent> timeline) {
        long firstCreation = Long.MAX_VALUE;
        long firstPayment = Long.MAX_VALUE;
        for (LegacyEvent event : timeline) {
            if (event.type() == LegacyEventType.CREATION) {
                firstCreation = Math.min(firstCreation, event.occurredAt());
            }
            if (event.type() == LegacyEventType.PAYMENT) {
                firstPayment = Math.min(firstPayment, event.occurredAt());
            }
        }
        return firstCreation != Long.MAX_VALUE && (firstPayment == Long.MAX_VALUE || firstPayment >= firstCreation);
    }

    private PlanResult validateTimeline(LegacyLoanRow loan, List<LegacyEvent> timeline, List<String> errors) {
        long principal = 0L;
        long totalPaid = 0L;
        int paymentCount = 0;
        long lastPaymentAt = 0L;
        boolean creationSeen = false;

        for (LegacyEvent event : timeline) {
            switch (event.type()) {
                case CREATION -> {
                    if (creationSeen) {
                        return PlanResult.failed("MULTIPLE_CREATIONS", List.of("Se encontraron múltiples creaciones"));
                    }
                    creationSeen = true;
                    principal = event.amountCents();
                }
                case TOPUP, ADJUSTMENT -> principal = Math.addExact(principal, event.amountCents());
                case PAYMENT -> {
                    totalPaid = Math.addExact(totalPaid, event.amountCents());
                    paymentCount++;
                    lastPaymentAt = Math.max(lastPaymentAt, event.occurredAt());
                    long pending = principal - totalPaid;
                    if (pending < 0L) {
                        return PlanResult.failed("PAYMENT_EXCEEDS_PENDING", List.of("Pago histórico excede el saldo pendiente cronológico"));
                    }
                }
                case CLOSE -> {
                }
            }
        }

        if (principal != loan.principalCents()) {
            return PlanResult.failed(
                "PRINCIPAL_MISMATCH",
                List.of("Principal derivado " + principal + " no coincide con legacy " + loan.principalCents())
            );
        }

        long pending = Math.max(0L, principal - totalPaid);
        LoanStatus expectedStatus = pending == 0L ? LoanStatus.CLOSED : LoanStatus.OPEN;
        int expectedProgress = principal <= 0L ? 0 : (int) Math.min(100L, (totalPaid * 100L) / principal);

        return PlanResult.valid(new ReplayPlan(timeline, principal, totalPaid, pending, paymentCount, lastPaymentAt, expectedStatus, expectedProgress));
    }

    private void validateSnapshotAgainstLegacy(LegacyLoanRow loan, LoanSnapshot snapshot, ReplayPlan plan) {
        if (snapshot.principalCents() != loan.principalCents()) {
            throw new IllegalStateException("Principal " + snapshot.principalCents() + " no coincide con legacy " + loan.principalCents());
        }
        if (snapshot.totalPaidCents() != plan.expectedTotalPaid()) {
            throw new IllegalStateException("totalPaid no coincide");
        }
        if (snapshot.pendingCents() != plan.expectedPending()) {
            throw new IllegalStateException("pending no coincide");
        }
        if (snapshot.status() != plan.expectedStatus()) {
            throw new IllegalStateException("status no coincide");
        }
    }

    private void validateProjections(String ownerId, String loanId, LoanSnapshot snapshot) {
        LoanSummaryProjection summary = queryRepository.getSummaryProjection(ownerId, loanId);
        if (summary == null) {
            throw new IllegalStateException("Summary projection faltante");
        }
        if (summary.principalCents() != snapshot.principalCents()) {
            throw new IllegalStateException("principal entre snapshot y summary no coincide");
        }
        if (summary.totalPaidCents() != snapshot.totalPaidCents()) {
            throw new IllegalStateException("totalPaid entre snapshot y summary no coincide");
        }
        if (summary.pendingCents() != snapshot.pendingCents()) {
            throw new IllegalStateException("pending entre snapshot y summary no coincide");
        }
        if (summary.status() != snapshot.status()) {
            throw new IllegalStateException("status entre snapshot y summary no coincide");
        }
        if (!snapshot.journalFingerprint().equals(summary.journalFingerprint())) {
            throw new IllegalStateException("journalFingerprint no coincide");
        }
        List<LoanPaymentProjection> payments = queryRepository.getPaymentProjections(ownerId, loanId);
        long total = payments.stream().mapToLong(LoanPaymentProjection::amountCents).sum();
        if (payments.size() != summary.paymentCount()) {
            throw new IllegalStateException("paymentCount no coincide con proyecciones");
        }
        if (total != summary.totalPaidCents()) {
            throw new IllegalStateException("suma de proyecciones no coincide con totalPaid");
        }
    }

    private LegacyLoanRow readLoan(String ownerId, String loanId) {
        String sql =
            "SELECT id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, " +
            "occurred_at_epoch_sec, account_id, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loans WHERE user_uid = ? AND id = ?";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new LegacyLoanRow(
                    result.getString("id"),
                    result.getString("user_uid"),
                    result.getString("type"),
                    result.getString("counterparty_name"),
                    result.getLong("principal_cents"),
                    result.getString("currency"),
                    result.getString("status"),
                    result.getString("notes"),
                    result.getLong("occurred_at_epoch_sec"),
                    result.getString("account_id"),
                    result.getLong("created_at_epoch_sec"),
                    result.getLong("updated_at_epoch_sec"),
                    result.getString("updated_by")
                );
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private LegacyHistory readHistory(LegacyLoanRow loan) {
        List<LegacyMovementRow> movements = readMovements(loan.ownerId(), loan.loanId());
        List<LegacyPaymentRow> payments = readPayments(loan.ownerId(), loan.loanId());
        return new LegacyHistory(loan, movements, payments, readTransactions(loan, movements, payments));
    }

    private List<LegacyMovementRow> readMovements(String ownerId, String loanId) {
        return queryRows(
            "SELECT id, loan_id, user_uid, movement_type, amount_cents, account_id, linked_transaction_id, note, " +
            "occurred_at_epoch_sec, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_movements WHERE user_uid = ? AND loan_id = ? ORDER BY occurred_at_epoch_sec, created_at_epoch_sec, id",
            ownerId, loanId, LegacyMovementRow.class
        );
    }

    private List<LegacyPaymentRow> readPayments(String ownerId, String loanId) {
        return queryRows(
            "SELECT id, loan_id, user_uid, account_id, principal_cents, occurred_at_epoch_sec, linked_transaction_id, " +
            "note, created_at_epoch_sec, updated_at_epoch_sec, updated_by " +
            "FROM loan_payments WHERE user_uid = ? AND loan_id = ? ORDER BY occurred_at_epoch_sec, created_at_epoch_sec, id",
            ownerId, loanId, LegacyPaymentRow.class
        );
    }

    /**
     * Las transacciones LOAN_* no llevan loan_id: solo se admiten las que el
     * préstamo referencia explícitamente desde sus movements/payments o desde
     * el journal canónico previo, más una transacción de creación no reclamada
     * que coincida en tipo, cuenta y fecha (la misma regla que usa
     * {@code CanonicalLoanTransactionBackfill}). Cualquier otra transacción de
     * la misma cuenta pertenece a otros préstamos y no puede atribuirse.
     */
    private List<LegacyTransactionRow> readTransactions(
            LegacyLoanRow loan, List<LegacyMovementRow> movements, List<LegacyPaymentRow> payments) {
        Set<String> referencedTxIds = new HashSet<>();
        for (LegacyMovementRow movement : movements) {
            if (movement.linkedTransactionId() != null) {
                referencedTxIds.add(movement.linkedTransactionId());
            }
        }
        for (LegacyPaymentRow payment : payments) {
            if (payment.linkedTransactionId() != null && !payment.linkedTransactionId().isBlank()) {
                referencedTxIds.add(payment.linkedTransactionId());
            }
        }
        Long priorCreationOccurredAt = null;
        for (LoanMovement event : repository.getJournal(loan.ownerId(), loan.loanId())) {
            if (event.transactionId() != null) {
                referencedTxIds.add(event.transactionId());
            }
            if (event.eventType() == LoanEventType.CREATION && priorCreationOccurredAt == null) {
                priorCreationOccurredAt = Long.valueOf(event.occurredAt());
            }
        }
        final Long creationAnchor = priorCreationOccurredAt;
        Set<String> claimedByOtherLoans = readJournalTransactionIds(loan.ownerId(), loan.loanId());

        List<LegacyTransactionRow> candidates = readLoanKindTransactions(loan.ownerId(), loan.accountId());
        String creationKind = loan.loanType() == LoanType.LENT ? "LOAN_LENT_OUT" : "LOAN_BORROWED_IN";
        List<LegacyTransactionRow> scoped = new ArrayList<>();
        for (LegacyTransactionRow tx : candidates) {
            if (referencedTxIds.contains(tx.id())) {
                scoped.add(tx);
            }
        }
        List<LegacyTransactionRow> creationCandidates = new ArrayList<>();
        for (LegacyTransactionRow tx : candidates) {
            if (!referencedTxIds.contains(tx.id())
                && !claimedByOtherLoans.contains(tx.id())
                && creationKind.equalsIgnoreCase(tx.kind())) {
                creationCandidates.add(tx);
            }
        }
        // El monto no se exige: un ajuste remoto queda plegado en el principal
        // del evento de creación mientras la transacción conserva el original.
        // Primero se intenta el occurredAt exacto del CREATION previo (regla del
        // backfill); si no hay journal previo, el más cercano al occurredAt del
        // préstamo.
        Optional<LegacyTransactionRow> creationTx = Optional.empty();
        if (creationAnchor != null) {
            creationTx = creationCandidates.stream()
                .filter(tx -> tx.occurredAtEpochSec() == creationAnchor.longValue())
                .min(Comparator.comparingLong(LegacyTransactionRow::createdAtEpochSec)
                    .thenComparing(LegacyTransactionRow::id));
        }
        if (creationTx.isEmpty()) {
            creationTx = creationCandidates.stream()
                .min(Comparator.comparingLong((LegacyTransactionRow tx) ->
                        Math.abs(tx.occurredAtEpochSec() - loan.occurredAtEpochSec()))
                    .thenComparingLong(LegacyTransactionRow::createdAtEpochSec)
                    .thenComparing(LegacyTransactionRow::id));
        }
        creationTx.ifPresent(scoped::add);
        scoped.sort(Comparator.comparingLong(LegacyTransactionRow::occurredAtEpochSec)
            .thenComparingLong(LegacyTransactionRow::createdAtEpochSec)
            .thenComparing(LegacyTransactionRow::id));
        return scoped;
    }

    private Set<String> readJournalTransactionIds(String ownerId, String excludeLoanId) {
        String sql =
            "SELECT DISTINCT transaction_id FROM loan_journal_v1 " +
            "WHERE owner_id = ? AND loan_id <> ? AND transaction_id IS NOT NULL";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, excludeLoanId);
            try (ResultSet result = statement.executeQuery()) {
                Set<String> ids = new HashSet<>();
                while (result.next()) {
                    ids.add(result.getString(1));
                }
                return ids;
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private List<LegacyTransactionRow> readLoanKindTransactions(String ownerId, String accountId) {
        String sql = "SELECT id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, note, " +
            "created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transactions WHERE user_uid = ? AND kind LIKE 'LOAN_%' " +
            "ORDER BY occurred_at_epoch_sec, created_at_epoch_sec, id";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet result = statement.executeQuery()) {
                List<LegacyTransactionRow> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(new LegacyTransactionRow(
                        result.getString("id"),
                        result.getString("user_uid"),
                        result.getString("account_id"),
                        result.getString("category_id"),
                        result.getString("kind"),
                        result.getLong("amount_cents"),
                        result.getLong("occurred_at_epoch_sec"),
                        result.getString("note"),
                        result.getLong("created_at_epoch_sec"),
                        result.getLong("updated_at_epoch_sec")
                    ));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private <T> List<T> queryRows(String sql, String p1, String p2, Class<T> type) {
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, p1);
            statement.setString(2, p2);
            try (ResultSet result = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (result.next()) {
                    if (type == LegacyMovementRow.class) {
                        rows.add(type.cast(new LegacyMovementRow(
                            result.getString("id"),
                            result.getString("loan_id"),
                            result.getString("user_uid"),
                            result.getString("movement_type"),
                            result.getLong("amount_cents"),
                            result.getString("account_id"),
                            result.getString("linked_transaction_id"),
                            result.getString("note"),
                            result.getLong("occurred_at_epoch_sec"),
                            result.getLong("created_at_epoch_sec"),
                            result.getLong("updated_at_epoch_sec"),
                            result.getString("updated_by")
                        )));
                    } else if (type == LegacyPaymentRow.class) {
                        rows.add(type.cast(new LegacyPaymentRow(
                            result.getString("id"),
                            result.getString("loan_id"),
                            result.getString("user_uid"),
                            result.getString("account_id"),
                            result.getLong("principal_cents"),
                            result.getLong("occurred_at_epoch_sec"),
                            result.getString("linked_transaction_id"),
                            result.getString("note"),
                            result.getLong("created_at_epoch_sec"),
                            result.getLong("updated_at_epoch_sec"),
                            result.getString("updated_by")
                        )));
                    } else if (type == LegacyTransactionRow.class) {
                        rows.add(type.cast(new LegacyTransactionRow(
                            result.getString("id"),
                            result.getString("user_uid"),
                            result.getString("account_id"),
                            result.getString("category_id"),
                            result.getString("kind"),
                            result.getLong("amount_cents"),
                            result.getLong("occurred_at_epoch_sec"),
                            result.getString("note"),
                            result.getLong("created_at_epoch_sec"),
                            result.getLong("updated_at_epoch_sec")
                        )));
                    }
                }
                return List.copyOf(rows);
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private CanonicalBackup captureCanonicalState(String ownerId, String loanId) {
        return new CanonicalBackup(repository.getJournal(ownerId, loanId), repository.loadSnapshot(ownerId, loanId));
    }

    private void deleteCanonicalState(String ownerId, String loanId) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement p1 = connection.prepareStatement("DELETE FROM loan_payment_projection_v1 WHERE owner_id = ? AND loan_id = ?");
                 PreparedStatement p2 = connection.prepareStatement("DELETE FROM loan_summary_projection_v1 WHERE owner_id = ? AND loan_id = ?");
                 PreparedStatement p3 = connection.prepareStatement("DELETE FROM loan_snapshots_v1 WHERE owner_id = ? AND loan_id = ?");
                 PreparedStatement p4 = connection.prepareStatement("DELETE FROM loan_journal_v1 WHERE owner_id = ? AND loan_id = ?")) {
                for (PreparedStatement ps : List.of(p1, p2, p3, p4)) {
                    ps.setString(1, ownerId);
                    ps.setString(2, loanId);
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw new LoanPersistenceException(ex);
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private void restoreCanonicalState(String ownerId, String loanId, CanonicalBackup backup) {
        deleteCanonicalState(ownerId, loanId);
        repository.inTransaction(() -> {
            for (LoanMovement event : backup.journal()) {
                repository.appendEvent(event);
            }
            backup.snapshot().ifPresent(repository::replaceSnapshot);
            return null;
        });
        projector.rebuild(ownerId, loanId, repository, new DefaultLoanReducer());
    }

    private void logReplayStart(LegacyLoanRow loan, LegacyHistory history) {
        System.out.println(
            "[LoanPaymentTrace] REPLAY_START"
                + " loanId=" + valueOrDash(loan.loanId())
                + " paymentId=" + history.payments().stream().map(LegacyPaymentRow::id).toList()
                + " transactionId=" + history.payments().stream().map(LegacyPaymentRow::linkedTransactionId).filter(Objects::nonNull).toList()
                + " operationId=- eventId=-"
                + " updatedAt=" + loan.updatedAtEpochSec()
                + " updatedBy=" + valueOrDash(loan.updatedBy())
                + " paymentsFound=" + history.payments().size()
                + " movementRows=" + history.movements().size()
                + " transactionRows=" + history.transactions().size()
        );
    }

    private void logReplayResult(
        String label,
        String ownerId,
        String loanId,
        LegacyLoanRow loan,
        LegacyHistory history,
        ReplayPlan plan,
        LoanSnapshot snapshot,
        String status,
        List<String> errors
    ) {
        int paymentsFound = history == null ? 0 : history.payments().size();
        int timelineEvents = plan == null ? 0 : plan.timeline().size();
        int journalEvents = snapshot == null ? 0 : snapshot.journalEventCount();
        long pending = snapshot == null ? -1L : snapshot.pendingCents();
        long totalPaid = snapshot == null ? -1L : snapshot.totalPaidCents();
        String paymentIds = history == null ? "[]" : history.payments().stream().map(LegacyPaymentRow::id).toList().toString();
        String transactionIds = history == null ? "[]" : history.payments().stream().map(LegacyPaymentRow::linkedTransactionId).filter(Objects::nonNull).toList().toString();
        System.out.println(
            "[LoanPaymentTrace] " + label
                + " loanId=" + valueOrDash(loanId)
                + " paymentId=" + paymentIds
                + " transactionId=" + transactionIds
                + " operationId=- eventId=-"
                + " updatedAt=" + (loan == null ? "-" : Long.toString(loan.updatedAtEpochSec()))
                + " updatedBy=" + (loan == null ? "-" : valueOrDash(loan.updatedBy()))
                + " ownerId=" + valueOrDash(ownerId)
                + " paymentsFound=" + paymentsFound
                + " timelineEvents=" + timelineEvents
                + " journalEvents=" + journalEvents
                + " pending=" + (pending < 0L ? "-" : Long.toString(pending))
                + " totalPaid=" + (totalPaid < 0L ? "-" : Long.toString(totalPaid))
                + " status=" + valueOrDash(status)
                + (errors == null || errors.isEmpty() ? "" : " errors=" + errors)
        );
    }

    private void logProjectionUpdated(String ownerId, String loanId, LoanSummaryProjection summary) {
        if (summary == null) {
            System.out.println(
                "[LoanPaymentTrace] PROJECTION_UPDATED"
                    + " loanId=" + valueOrDash(loanId)
                    + " paymentId=- transactionId=- operationId=- eventId=-"
                    + " updatedAt=- updatedBy=- ownerId=" + valueOrDash(ownerId)
                    + " principal=- paid=- pending=- paymentCount=- reason=missingSummary"
            );
            return;
        }
        System.out.println(
            "[LoanPaymentTrace] PROJECTION_UPDATED"
                + " loanId=" + valueOrDash(summary.loanId())
                + " paymentId=- transactionId=- operationId=- eventId=-"
                + " updatedAt=- updatedBy=- ownerId=" + valueOrDash(summary.ownerId())
                + " principal=" + summary.principalCents()
                + " paid=" + summary.totalPaidCents()
                + " pending=" + summary.pendingCents()
                + " paymentCount=" + summary.paymentCount()
                + " journalFingerprint=" + valueOrDash(summary.journalFingerprint())
        );
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record LegacyLoanRow(
        String loanId,
        String ownerId,
        String type,
        String counterpartyName,
        long principalCents,
        String currency,
        String status,
        String notes,
        long occurredAtEpochSec,
        String accountId,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {
        LoanType loanType() {
            return LoanType.valueOf(type.toUpperCase(Locale.ROOT));
        }
    }

    private record LegacyMovementRow(
        String id,
        String loanId,
        String userUid,
        String movementType,
        long amountCents,
        String accountId,
        String linkedTransactionId,
        String note,
        long occurredAtEpochSec,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {}

    private record LegacyPaymentRow(
        String id,
        String loanId,
        String userUid,
        String accountId,
        long principalCents,
        long occurredAtEpochSec,
        String linkedTransactionId,
        String note,
        long createdAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {}

    private record LegacyTransactionRow(
        String id,
        String userUid,
        String accountId,
        String categoryId,
        String kind,
        long amountCents,
        long occurredAtEpochSec,
        String note,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {}

    private record LegacyHistory(
        LegacyLoanRow loan,
        List<LegacyMovementRow> movements,
        List<LegacyPaymentRow> payments,
        List<LegacyTransactionRow> transactions
    ) {}

    private enum LegacyEventType { CREATION, TOPUP, ADJUSTMENT, PAYMENT, CLOSE }

    private record LegacyEvent(
        LegacyEventType type,
        String sourceKey,
        String loanId,
        String ownerId,
        long occurredAt,
        long createdAt,
        String accountId,
        String transactionId,
        long amountCents,
        String note
    ) {
        LegacyEvent withTransactionId(String txId) {
            return new LegacyEvent(type, sourceKey, loanId, ownerId, occurredAt, createdAt, accountId, txId, amountCents, note);
        }

        LegacyEvent withOccurredAt(long value) {
            return new LegacyEvent(type, sourceKey, loanId, ownerId, value, value, accountId, transactionId, amountCents, note);
        }

        static LegacyEvent syntheticAdjustment(LegacyLoanRow loan, long deltaCents, long occurredAt) {
            return new LegacyEvent(
                LegacyEventType.ADJUSTMENT,
                "synth:adjust:" + loan.loanId(),
                loan.loanId(),
                loan.ownerId(),
                occurredAt,
                occurredAt,
                loan.accountId(),
                null,
                deltaCents,
                "Ajuste incremental remoto"
            );
        }

        static LegacyEvent syntheticClose(LegacyLoanRow loan, long occurredAt) {
            return new LegacyEvent(
                LegacyEventType.CLOSE,
                "synth:close:" + loan.loanId(),
                loan.loanId(),
                loan.ownerId(),
                occurredAt,
                occurredAt,
                loan.accountId(),
                null,
                0L,
                "Cierre incremental remoto"
            );
        }

        static LegacyEvent creation(LegacyLoanRow loan) {
            return new LegacyEvent(
                LegacyEventType.CREATION, "loan:" + loan.loanId(), loan.loanId(), loan.ownerId(),
                loan.occurredAtEpochSec(), loan.createdAtEpochSec(), loan.accountId(), null,
                loan.principalCents(), loan.notes()
            );
        }

        static LegacyEvent creationFromJournal(LegacyLoanRow loan, long occurredAt, long amountCents) {
            return new LegacyEvent(
                LegacyEventType.CREATION, "journal:" + loan.loanId() + ":creation", loan.loanId(), loan.ownerId(),
                occurredAt, occurredAt, loan.accountId(), null,
                amountCents, loan.notes()
            );
        }

        static LegacyEvent creation(LegacyMovementRow movement, LegacyLoanRow loan) {
            return new LegacyEvent(
                LegacyEventType.CREATION, "mov:" + movement.id(), loan.loanId(), loan.ownerId(),
                movement.occurredAtEpochSec(), movement.createdAtEpochSec(),
                movement.accountId() == null ? loan.accountId() : movement.accountId(),
                movement.linkedTransactionId(), movement.amountCents(), movement.note()
            );
        }

        static LegacyEvent creation(LegacyTransactionRow tx, LegacyLoanRow loan) {
            return new LegacyEvent(
                LegacyEventType.CREATION, "tx:" + tx.id(), loan.loanId(), loan.ownerId(),
                tx.occurredAtEpochSec(), tx.createdAtEpochSec(), tx.accountId(), tx.id(),
                tx.amountCents(), tx.note()
            );
        }

        static LegacyEvent topup(LegacyMovementRow movement, LegacyLoanRow loan) {
            return new LegacyEvent(
                LegacyEventType.TOPUP, "mov:" + movement.id(), loan.loanId(), loan.ownerId(),
                movement.occurredAtEpochSec(), movement.createdAtEpochSec(),
                movement.accountId() == null ? loan.accountId() : movement.accountId(),
                movement.linkedTransactionId(), movement.amountCents(), movement.note()
            );
        }

        static LegacyEvent topup(LegacyTransactionRow tx, LoanType loanType) {
            return new LegacyEvent(
                LegacyEventType.TOPUP, "tx:" + tx.id(), null, tx.userUid(),
                tx.occurredAtEpochSec(), tx.createdAtEpochSec(), tx.accountId(), tx.id(),
                tx.amountCents(), tx.note()
            );
        }

        static LegacyEvent adjustment(LegacyTransactionRow tx, LoanType loanType) {
            long delta;
            if (loanType == LoanType.LENT) {
                delta = tx.kind().toUpperCase(Locale.ROOT).endsWith("OUT") ? tx.amountCents() : -tx.amountCents();
            } else {
                delta = tx.kind().toUpperCase(Locale.ROOT).endsWith("IN") ? tx.amountCents() : -tx.amountCents();
            }
            return new LegacyEvent(
                LegacyEventType.ADJUSTMENT, "tx:" + tx.id(), null, tx.userUid(),
                tx.occurredAtEpochSec(), tx.createdAtEpochSec(), tx.accountId(), tx.id(),
                delta, tx.note()
            );
        }

        static LegacyEvent payment(LegacyMovementRow movement, LegacyLoanRow loan) {
            return new LegacyEvent(
                LegacyEventType.PAYMENT, "mov:" + movement.id(), loan.loanId(), loan.ownerId(),
                movement.occurredAtEpochSec(), movement.createdAtEpochSec(),
                movement.accountId() == null ? loan.accountId() : movement.accountId(),
                movement.linkedTransactionId(), movement.amountCents(), movement.note()
            );
        }

        static LegacyEvent payment(LegacyPaymentRow payment) {
            return new LegacyEvent(
                LegacyEventType.PAYMENT, "pay:" + payment.id(), payment.loanId(), payment.userUid(),
                payment.occurredAtEpochSec(), payment.createdAtEpochSec(),
                payment.accountId(), payment.linkedTransactionId(), payment.principalCents(), payment.note()
            );
        }

        static LegacyEvent payment(LegacyTransactionRow tx, LoanType loanType) {
            return new LegacyEvent(
                LegacyEventType.PAYMENT, "tx:" + tx.id(), null, tx.userUid(),
                tx.occurredAtEpochSec(), tx.createdAtEpochSec(), tx.accountId(), tx.id(),
                tx.amountCents(), tx.note()
            );
        }

        static LegacyEvent close(LegacyMovementRow movement) {
            return new LegacyEvent(
                LegacyEventType.CLOSE, "mov:" + movement.id(), movement.loanId(), movement.userUid(),
                movement.occurredAtEpochSec(), movement.createdAtEpochSec(),
                movement.accountId(), movement.linkedTransactionId(), 0L, movement.note()
            );
        }
    }

    private record ReplayPlan(
        List<LegacyEvent> timeline,
        long expectedPrincipal,
        long expectedTotalPaid,
        long expectedPending,
        int expectedPaymentCount,
        long lastPaymentAt,
        LoanStatus expectedStatus,
        int expectedProgress
    ) {}

    private record TimelineState(
        long principal,
        long totalPaid,
        long pending,
        int paymentCount,
        long lastPaymentAt,
        boolean closed,
        LoanStatus status,
        int progress
    ) {}

    private record PlanResult(boolean ok, String code, List<String> errors, ReplayPlan plan) {
        static PlanResult valid(ReplayPlan plan) {
            return new PlanResult(true, null, List.of(), plan);
        }

        static PlanResult failed(String code, List<String> errors) {
            return new PlanResult(false, code, List.copyOf(errors), null);
        }
    }

    private record CanonicalBackup(List<LoanMovement> journal, Optional<LoanSnapshot> snapshot) {}

    public record ReplayResult(
        String ownerId,
        String loanId,
        boolean success,
        String status,
        int eventsApplied,
        int expectedPaymentCount,
        int actualPaymentCount,
        LoanSummaryProjection summary,
        LoanSnapshot snapshot,
        List<String> errors,
        long durationMs
    ) {
        static ReplayResult successResult(String ownerId, String loanId, int eventsApplied, int expectedPaymentCount, int actualPaymentCount, LoanSummaryProjection summary, LoanSnapshot snapshot, long durationMs) {
            return new ReplayResult(ownerId, loanId, true, "SUCCESS", eventsApplied, expectedPaymentCount, actualPaymentCount, summary, snapshot, List.of(), durationMs);
        }

        static ReplayResult failedResult(String ownerId, String loanId, String status, List<String> errors, long durationMs) {
            return new ReplayResult(ownerId, loanId, false, status, 0, 0, 0, null, null, List.copyOf(errors), durationMs);
        }
    }
}
