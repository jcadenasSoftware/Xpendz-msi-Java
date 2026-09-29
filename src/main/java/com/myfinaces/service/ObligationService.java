package com.myfinaces.service;

import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.DeviceId;
import com.myfinaces.sync.ObligationPublishQueue;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

public final class ObligationService {

    private final SqliteDatabase database;
    private final ObligationRepository obligationRepository;
    private final ObligationSettlementRepository settlementRepository;
    private final TransactionRepository transactionRepository;
    private Runnable afterTransactionMutationHook = () -> {};

    public ObligationService(
        SqliteDatabase database,
        ObligationRepository obligationRepository,
        ObligationSettlementRepository settlementRepository,
        TransactionRepository transactionRepository
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.obligationRepository = Objects.requireNonNull(obligationRepository, "obligationRepository");
        this.settlementRepository = Objects.requireNonNull(settlementRepository, "settlementRepository");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "transactionRepository");
    }

    void setAfterTransactionMutationHook(Runnable afterTransactionMutationHook) {
        this.afterTransactionMutationHook = afterTransactionMutationHook == null ? () -> {} : afterTransactionMutationHook;
    }

    public ObligationRepository.Obligation createObligation(
        String userUid,
        String type,
        String title,
        String counterpartyName,
        String currency,
        long originalAmountCents,
        long issuedAtEpochSec,
        Long dueAtEpochSec,
        String obligationCategoryId,
        String reference,
        String notes
    ) throws SQLException {
        String obligationId = obligationRepository.create(
            userUid,
            type,
            title,
            counterpartyName,
            currency,
            originalAmountCents,
            issuedAtEpochSec,
            dueAtEpochSec,
            obligationCategoryId,
            reference,
            notes
        );
        return requireObligation(userUid, obligationId);
    }

    public ObligationRepository.Obligation updateObligationMetadata(
        String userUid,
        String obligationId,
        String type,
        String title,
        String counterpartyName,
        String currency,
        long originalAmountCents,
        long issuedAtEpochSec,
        Long dueAtEpochSec,
        String obligationCategoryId,
        String reference,
        String notes
    ) throws SQLException {
        ObligationRepository.Obligation obligation = requireObligation(userUid, obligationId);
        long totalSettled = settlementRepository.sumSettledCentsByObligation(userUid, obligationId);
        if (originalAmountCents <= 0L) {
            throw new IllegalArgumentException("originalAmountCents");
        }
        if (originalAmountCents < totalSettled) {
            throw new IllegalStateException("obligation_amount_below_settled");
        }
        obligationRepository.update(
            userUid,
            obligation.id(),
            type,
            title,
            counterpartyName,
            currency,
            originalAmountCents,
            issuedAtEpochSec,
            dueAtEpochSec,
            obligation.cancelledAtEpochSec(),
            obligationCategoryId,
            reference,
            notes
        );
        return requireObligation(userUid, obligation.id());
    }

    public ObligationRepository.Obligation cancelObligation(
        String userUid,
        String obligationId,
        long cancelledAtEpochSec
    ) throws SQLException {
        ObligationRepository.Obligation obligation = requireObligation(userUid, obligationId);
        if (obligation.cancelledAtEpochSec() != null) {
            return obligation;
        }
        obligationRepository.cancel(userUid, obligationId, cancelledAtEpochSec);
        return requireObligation(userUid, obligationId);
    }

    public SettlementMutationResult registerSettlement(
        String userUid,
        String obligationId,
        String accountId,
        String financialCategoryId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        ObligationRepository.Obligation obligation = requireObligation(userUid, obligationId);
        ResolvedObligationState currentState = getResolvedState(userUid, obligationId, currentEpochSec());
        validateSettlementAllowed(currentState);
        validateSettlementAmount(amountCents, currentState.pendingAmountCents());
        String kind = settlementTransactionKind(obligation.type());
        String normalizedAccountId = requireNonBlank(accountId, "accountId");
        String normalizedCategoryId = requireNonBlank(financialCategoryId, "financialCategoryId");
        String normalizedNote = normalizeOptionalText(note);

        return inTransaction(connection -> {
            requireCompatibleFinancialCategory(connection, userUid, normalizedCategoryId, kind);
            requireNonNegativeBalanceAfterCreate(connection, userUid, normalizedAccountId, kind, amountCents);
            TransactionRepository.TransactionSyncRow transaction = transactionRepository.createDirect(
                connection,
                userUid,
                normalizedAccountId,
                normalizedCategoryId,
                kind,
                amountCents,
                occurredAtEpochSec,
                normalizedNote
            );
            afterTransactionMutationHook.run();
            ObligationSettlementRepository.ObligationSettlement settlement = settlementRepository.createDirect(
                connection,
                userUid,
                obligation.id(),
                normalizedAccountId,
                amountCents,
                occurredAtEpochSec,
                transaction.id(),
                normalizedNote,
                DeviceId.get()
            );
            return new SettlementMutationResult(
                obligation,
                settlement,
                transaction,
                resolveState(obligation, currentState.totalSettledCents() + amountCents, currentEpochSec())
            );
        });
    }

    public SettlementMutationResult updateSettlement(
        String userUid,
        String settlementId,
        String accountId,
        String financialCategoryId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        ObligationSettlementRepository.ObligationSettlement settlement = requireSettlement(userUid, settlementId);
        ObligationRepository.Obligation obligation = requireObligation(userUid, settlement.obligationId());
        if (obligation.cancelledAtEpochSec() != null) {
            throw new IllegalStateException("obligation_cancelled");
        }
        TransactionRepository.TransactionSyncRow existingTransaction = transactionRepository.getForSyncByIdOrNull(userUid, settlement.linkedTransactionId());
        if (existingTransaction == null) {
            throw new IllegalStateException("linked_transaction_missing");
        }
        long totalSettled = settlementRepository.sumSettledCentsByObligation(userUid, obligation.id());
        long settledExcludingCurrent = totalSettled - settlement.amountCents();
        long allowedAmount = obligation.originalAmountCents() - settledExcludingCurrent;
        validateSettlementAmount(amountCents, allowedAmount);
        String kind = settlementTransactionKind(obligation.type());
        String normalizedAccountId = requireNonBlank(accountId, "accountId");
        String normalizedCategoryId = requireNonBlank(financialCategoryId, "financialCategoryId");
        String normalizedNote = normalizeOptionalText(note);

        return inTransaction(connection -> {
            requireCompatibleFinancialCategory(connection, userUid, normalizedCategoryId, kind);
            requireNonNegativeBalanceAfterUpdate(connection, userUid, existingTransaction, normalizedAccountId, kind, amountCents);
            TransactionRepository.TransactionSyncRow updatedTransaction = transactionRepository.updateDirect(
                connection,
                existingTransaction,
                normalizedAccountId,
                normalizedCategoryId,
                kind,
                amountCents,
                occurredAtEpochSec,
                normalizedNote
            );
            afterTransactionMutationHook.run();
            ObligationSettlementRepository.ObligationSettlement updatedSettlement = settlementRepository.updateDirect(
                connection,
                settlement,
                normalizedAccountId,
                amountCents,
                occurredAtEpochSec,
                normalizedNote,
                DeviceId.get()
            );
            return new SettlementMutationResult(
                obligation,
                updatedSettlement,
                updatedTransaction,
                resolveState(obligation, settledExcludingCurrent + amountCents, currentEpochSec())
            );
        });
    }

    public DeleteSettlementResult deleteSettlement(String userUid, String settlementId) throws SQLException {
        ObligationSettlementRepository.ObligationSettlement settlement = requireSettlement(userUid, settlementId);
        ObligationRepository.Obligation obligation = requireObligation(userUid, settlement.obligationId());
        TransactionRepository.TransactionSyncRow existingTransaction = transactionRepository.getForSyncByIdOrNull(userUid, settlement.linkedTransactionId());
        if (existingTransaction == null) {
            throw new IllegalStateException("linked_transaction_missing");
        }
        long totalSettled = settlementRepository.sumSettledCentsByObligation(userUid, obligation.id());

        return inTransaction(connection -> {
            settlementRepository.deleteDirect(connection, userUid, settlement.id());
            afterTransactionMutationHook.run();
            transactionRepository.deleteDirect(connection, userUid, existingTransaction.id());
            ObligationPublishQueue.markPendingDelete(connection, userUid, settlement.id(), existingTransaction.id());
            return new DeleteSettlementResult(
                settlement.id(),
                existingTransaction.id(),
                resolveState(obligation, totalSettled - settlement.amountCents(), currentEpochSec())
            );
        });
    }

    public ResolvedObligationState getResolvedState(
        String userUid,
        String obligationId,
        long nowEpochSec
    ) throws SQLException {
        ObligationRepository.Obligation obligation = requireObligation(userUid, obligationId);
        long totalSettled = settlementRepository.sumSettledCentsByObligation(userUid, obligationId);
        return resolveState(obligation, totalSettled, nowEpochSec);
    }

    private ObligationRepository.Obligation requireObligation(String userUid, String obligationId) throws SQLException {
        ObligationRepository.Obligation obligation = obligationRepository.getByIdOrNull(userUid, obligationId);
        if (obligation == null) {
            throw new IllegalArgumentException("obligation_not_found");
        }
        return obligation;
    }

    private ObligationSettlementRepository.ObligationSettlement requireSettlement(String userUid, String settlementId) throws SQLException {
        ObligationSettlementRepository.ObligationSettlement settlement = settlementRepository.getByIdOrNull(userUid, settlementId);
        if (settlement == null) {
            throw new IllegalArgumentException("settlement_not_found");
        }
        return settlement;
    }

    private void validateSettlementAllowed(ResolvedObligationState state) {
        if (state.status() == ObligationResolvedStatus.CANCELADA) {
            throw new IllegalStateException("obligation_cancelled");
        }
        if (state.status() == ObligationResolvedStatus.PAGADA) {
            throw new IllegalStateException("obligation_paid");
        }
    }

    private void validateSettlementAmount(long amountCents, long maxAmountCents) {
        if (amountCents <= 0L) {
            throw new IllegalArgumentException("amountCents");
        }
        if (amountCents > maxAmountCents) {
            throw new IllegalStateException("settlement_exceeds_pending");
        }
    }

    private ResolvedObligationState resolveState(
        ObligationRepository.Obligation obligation,
        long totalSettledCents,
        long nowEpochSec
    ) {
        if (totalSettledCents > obligation.originalAmountCents()) {
            throw new IllegalStateException("obligation_overpaid");
        }
        long pendingAmountCents = obligation.originalAmountCents() - totalSettledCents;
        ObligationResolvedStatus status;
        if (obligation.cancelledAtEpochSec() != null) {
            status = ObligationResolvedStatus.CANCELADA;
        } else if (pendingAmountCents == 0L) {
            status = ObligationResolvedStatus.PAGADA;
        } else if (obligation.dueAtEpochSec() != null && obligation.dueAtEpochSec() < nowEpochSec) {
            status = ObligationResolvedStatus.VENCIDA;
        } else if (totalSettledCents == 0L) {
            status = ObligationResolvedStatus.PENDIENTE;
        } else {
            status = ObligationResolvedStatus.PARCIAL;
        }
        return new ResolvedObligationState(
            obligation.id(),
            status,
            obligation.originalAmountCents(),
            totalSettledCents,
            pendingAmountCents,
            obligation.dueAtEpochSec(),
            obligation.cancelledAtEpochSec()
        );
    }

    private void requireNonNegativeBalanceAfterCreate(
        Connection connection,
        String userUid,
        String accountId,
        String kind,
        long amountCents
    ) throws SQLException {
        long delta = signedAmountDeltaCents(kind, amountCents);
        if (delta >= 0L) {
            return;
        }
        long currentBalance = computeBalanceCents(connection, userUid, accountId);
        if (currentBalance + delta < 0L) {
            throw new IllegalArgumentException("Saldo insuficiente");
        }
    }

    private void requireNonNegativeBalanceAfterUpdate(
        Connection connection,
        String userUid,
        TransactionRepository.TransactionSyncRow existing,
        String newAccountId,
        String newKind,
        long newAmountCents
    ) throws SQLException {
        String[] affectedAccounts = existing.accountId().equals(newAccountId)
            ? new String[] { existing.accountId() }
            : new String[] { existing.accountId(), newAccountId };
        for (String accountId : affectedAccounts) {
            long currentBalance = computeBalanceCents(connection, userUid, accountId);
            long revertOld = accountId.equals(existing.accountId())
                ? -signedAmountDeltaCents(existing.kind(), existing.amountCents())
                : 0L;
            long applyNew = accountId.equals(newAccountId)
                ? signedAmountDeltaCents(newKind, newAmountCents)
                : 0L;
            if (currentBalance + revertOld + applyNew < 0L) {
                throw new IllegalArgumentException("Saldo insuficiente");
            }
        }
    }

    private long computeBalanceCents(Connection connection, String userUid, String accountId) throws SQLException {
        String sql =
            "SELECT (" +
            "  COALESCE((SELECT SUM(CASE " +
            "    WHEN kind = 'INCOME' THEN amount_cents " +
            "    WHEN kind = 'LOAN_BORROWED_IN' THEN amount_cents " +
            "    WHEN kind = 'LOAN_BORROWED_TOPUP' THEN amount_cents " +
            "    WHEN kind = 'LOAN_BORROWED_CORRECTION' THEN amount_cents " +
            "    WHEN kind = 'LOAN_REPAYMENT_PRINCIPAL_IN' THEN amount_cents " +
            "    WHEN kind = 'EXPENSE' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_LENT_OUT' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_LENT_TOPUP' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_LENT_CORRECTION' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_LENT_CORRECTION_IN' THEN amount_cents " +
            "    WHEN kind = 'LOAN_LENT_CORRECTION_OUT' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_BORROWED_CORRECTION_IN' THEN amount_cents " +
            "    WHEN kind = 'LOAN_BORROWED_CORRECTION_OUT' THEN -amount_cents " +
            "    WHEN kind = 'LOAN_REPAYMENT_PRINCIPAL_OUT' THEN -amount_cents " +
            "    ELSE 0 END) FROM transactions WHERE user_uid = ? AND account_id = ?), 0)" +
            "  + COALESCE((SELECT SUM(amount_cents) FROM transfers WHERE user_uid = ? AND to_account_id = ?), 0)" +
            "  - COALESCE((SELECT SUM(amount_cents) FROM transfers WHERE user_uid = ? AND from_account_id = ?), 0)" +
            ") AS balance";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, accountId);
            ps.setString(3, userUid);
            ps.setString(4, accountId);
            ps.setString(5, userUid);
            ps.setString(6, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : 0L;
            }
        }
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = database.openConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    // La categoría financiera del abono vive en la Transaction: su kind debe ser
    // compatible con el tipo de movimiento (cobro → INCOME, pago → EXPENSE).
    // "BOTH" o kind vacío (legado, hereda del padre) son compatibles.
    private void requireCompatibleFinancialCategory(
        Connection connection,
        String userUid,
        String categoryId,
        String txKind
    ) throws SQLException {
        String kind = null;
        String parentId = null;
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT kind, parent_id FROM categories WHERE user_uid = ? AND id = ?")) {
            ps.setString(1, userUid);
            ps.setString(2, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("settlement_category_not_found");
                }
                kind = rs.getString("kind");
                parentId = rs.getString("parent_id");
            }
        }
        if ((kind == null || kind.isBlank()) && parentId != null && !parentId.isBlank()) {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT kind FROM categories WHERE user_uid = ? AND id = ?")) {
                ps.setString(1, userUid);
                ps.setString(2, parentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        kind = rs.getString("kind");
                    }
                }
            }
        }
        if (kind == null || kind.isBlank()
            || "BOTH".equalsIgnoreCase(kind.trim())
            || kind.trim().equalsIgnoreCase(txKind)) {
            return;
        }
        throw new IllegalStateException("settlement_category_kind_mismatch");
    }

    private String settlementTransactionKind(String obligationType) {
        return switch (ObligationRepository.normalizeType(obligationType)) {
            case ObligationRepository.TYPE_RECEIVABLE -> TransactionKind.INCOME.name();
            case ObligationRepository.TYPE_PAYABLE -> TransactionKind.EXPENSE.name();
            default -> throw new IllegalArgumentException("type");
        };
    }

    private long signedAmountDeltaCents(String kind, long amountCents) {
        return switch (kind == null ? "" : kind.trim().toUpperCase()) {
            case "INCOME" -> amountCents;
            case "EXPENSE" -> -amountCents;
            default -> 0L;
        };
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private long currentEpochSec() {
        return Instant.now().getEpochSecond();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    public enum ObligationResolvedStatus {
        CANCELADA,
        PAGADA,
        VENCIDA,
        PENDIENTE,
        PARCIAL
    }

    public record ResolvedObligationState(
        String obligationId,
        ObligationResolvedStatus status,
        long originalAmountCents,
        long totalSettledCents,
        long pendingAmountCents,
        Long dueAtEpochSec,
        Long cancelledAtEpochSec
    ) {
    }

    public record SettlementMutationResult(
        ObligationRepository.Obligation obligation,
        ObligationSettlementRepository.ObligationSettlement settlement,
        TransactionRepository.TransactionSyncRow transaction,
        ResolvedObligationState resolvedState
    ) {
    }

    public record DeleteSettlementResult(
        String deletedSettlementId,
        String deletedTransactionId,
        ResolvedObligationState resolvedState
    ) {
    }
}
