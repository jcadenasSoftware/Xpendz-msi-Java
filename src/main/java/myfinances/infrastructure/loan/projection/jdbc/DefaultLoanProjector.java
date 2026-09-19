package myfinances.infrastructure.loan.projection.jdbc;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.journal.LoanEventType;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanProjectionChange;
import myfinances.domain.loan.projection.LoanProjectionChangeType;
import myfinances.domain.loan.projection.LoanProjector;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.reducer.LoanReducer;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.domain.loan.reducer.LoanReductionResult;
import myfinances.domain.loan.reducer.ReductionResultType;
import myfinances.domain.loan.repository.LoanRepository;
import myfinances.infrastructure.loan.projection.mapper.LoanProjectionMapper;
import myfinances.infrastructure.loan.projection.model.LoanPaymentProjectionRecord;
import myfinances.infrastructure.loan.projection.model.LoanSummaryProjectionRecord;

public final class DefaultLoanProjector implements LoanProjector {
    private final SqliteDatabase database;
    private final LoanProjectionMapper mapper;

    public DefaultLoanProjector(SqliteDatabase database) {
        this(database, new LoanProjectionMapper());
    }

    public DefaultLoanProjector(SqliteDatabase database, LoanProjectionMapper mapper) {
        this.database = database;
        this.mapper = mapper;
        LoanProjectionSchemaV1.initialize(database);
    }

    public void rebuildAll(LoanRepository repository, LoanReducer reducer) {
        List<DistinctLoan> loans = queryDistinctLoans();
        for (DistinctLoan loan : loans) {
            rebuild(loan.ownerId(), loan.loanId(), repository, reducer);
        }
    }

    private List<DistinctLoan> queryDistinctLoans() {
        String sql = "SELECT DISTINCT owner_id, loan_id FROM loan_journal_v1";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<DistinctLoan> loans = new ArrayList<>();
            while (result.next()) {
                loans.add(new DistinctLoan(
                    result.getString("owner_id"),
                    result.getString("loan_id")
                ));
            }
            return loans;
        } catch (SQLException ex) {
            throw new LoanProjectionPersistenceException(ex);
        }
    }

    private record DistinctLoan(String ownerId, String loanId) {}

    @Override
    public void project(LoanCommandResult result) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (LoanProjectionChange change : result.projectionChanges()) {
                    applyChange(connection, change, result);
                }
                connection.commit();
            } catch (SQLException ex) {
                rollback(connection);
                throw new LoanProjectionPersistenceException(ex);
            }
        } catch (SQLException ex) {
            throw new LoanProjectionPersistenceException(ex);
        }
    }

    @Override
    public void rebuild(String ownerId, String loanId, LoanRepository repository, LoanReducer reducer) {
        List<LoanMovement> journal = repository.getJournal(ownerId, loanId);
        LoanReductionResult reduction = reducer.reduceCanonical(journal);
        if (reduction.type() != ReductionResultType.VALID || reduction.snapshot() == null) {
            return;
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                deletePaymentProjections(connection, ownerId, loanId);
                deleteSummaryProjection(connection, ownerId, loanId);
                for (LoanMovement event : reduction.effectiveEvents()) {
                    if (event.eventType() == LoanEventType.PAYMENT) {
                        insertPaymentProjection(connection, mapper.toPaymentProjection(event));
                    }
                }
                PaymentSummary paymentSummary = computePaymentSummary(connection, ownerId, loanId);
                LoanSummaryProjection summary = mapper.toSummaryProjection(reduction.snapshot(), paymentSummary.count(), paymentSummary.lastPaymentAt());
                upsertSummaryProjection(connection, summary);
                connection.commit();
            } catch (SQLException ex) {
                rollback(connection);
                throw new LoanProjectionPersistenceException(ex);
            }
        } catch (SQLException ex) {
            throw new LoanProjectionPersistenceException(ex);
        }
    }

    private void applyChange(Connection connection, LoanProjectionChange change, LoanCommandResult result) throws SQLException {
        switch (change.type()) {
            case NONE -> {}
            case ADD_PAYMENT_PROJECTION -> insertPaymentProjection(connection, mapper.toPaymentProjection(result.event()));
            case REMOVE_PAYMENT_PROJECTION -> deletePaymentProjection(connection, change.sourceEventId());
            case REBUILD_LOAN_SNAPSHOT -> {
                LoanSnapshot current = result.currentSnapshot();
                PaymentSummary paymentSummary = computePaymentSummary(connection, current.ownerId(), current.loanId());
                upsertSummaryProjection(connection, mapper.toSummaryProjection(current, paymentSummary.count(), paymentSummary.lastPaymentAt()));
            }
        }
    }

    private void insertPaymentProjection(Connection connection, LoanPaymentProjection projection) throws SQLException {
        LoanPaymentProjectionRecord record = mapper.toRecord(projection);
        String sql = "INSERT INTO loan_payment_projection_v1 (source_event_id, operation_id, owner_id, loan_id, account_id, transaction_id, occurred_at, amount_cents, direction, note) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.sourceEventId());
            statement.setString(2, record.operationId());
            statement.setString(3, record.ownerId());
            statement.setString(4, record.loanId());
            statement.setString(5, record.accountId());
            statement.setString(6, record.transactionId());
            statement.setLong(7, record.occurredAt());
            statement.setLong(8, record.amountCents());
            statement.setString(9, record.direction());
            statement.setString(10, record.note());
            statement.executeUpdate();
        }
    }

    private void deletePaymentProjection(Connection connection, String sourceEventId) throws SQLException {
        String sql = "DELETE FROM loan_payment_projection_v1 WHERE source_event_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceEventId);
            statement.executeUpdate();
        }
    }

    private void deletePaymentProjections(Connection connection, String ownerId, String loanId) throws SQLException {
        String sql = "DELETE FROM loan_payment_projection_v1 WHERE owner_id = ? AND loan_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            statement.executeUpdate();
        }
    }

    private void deleteSummaryProjection(Connection connection, String ownerId, String loanId) throws SQLException {
        String sql = "DELETE FROM loan_summary_projection_v1 WHERE owner_id = ? AND loan_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            statement.executeUpdate();
        }
    }

    private void upsertSummaryProjection(Connection connection, LoanSummaryProjection summary) throws SQLException {
        LoanSummaryProjectionRecord record = mapper.toRecord(summary);
        String sql = "INSERT INTO loan_summary_projection_v1 (loan_id, owner_id, counterparty, loan_type, currency, default_account_id, notes, " +
            "principal_cents, total_paid_cents, pending_cents, overpaid_cents, payment_count, last_payment_at, progress_percent, " +
            "status, closed_at, last_activity, journal_fingerprint) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(owner_id, loan_id) DO UPDATE SET " +
            "counterparty = excluded.counterparty, loan_type = excluded.loan_type, currency = excluded.currency, " +
            "default_account_id = excluded.default_account_id, notes = excluded.notes, " +
            "principal_cents = excluded.principal_cents, total_paid_cents = excluded.total_paid_cents, " +
            "pending_cents = excluded.pending_cents, overpaid_cents = excluded.overpaid_cents, " +
            "payment_count = excluded.payment_count, last_payment_at = excluded.last_payment_at, progress_percent = excluded.progress_percent, " +
            "status = excluded.status, closed_at = excluded.closed_at, last_activity = excluded.last_activity, " +
            "journal_fingerprint = excluded.journal_fingerprint";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.loanId());
            statement.setString(2, record.ownerId());
            statement.setString(3, record.counterparty());
            statement.setString(4, record.loanType());
            statement.setString(5, record.currency());
            statement.setString(6, record.defaultAccountId());
            statement.setString(7, record.notes());
            statement.setLong(8, record.principalCents());
            statement.setLong(9, record.totalPaidCents());
            statement.setLong(10, record.pendingCents());
            statement.setLong(11, record.overpaidCents());
            statement.setInt(12, record.paymentCount());
            setNullableLong(statement, 13, record.lastPaymentAt());
            statement.setInt(14, record.progressPercent());
            statement.setString(15, record.status());
            setNullableLong(statement, 16, record.closedAt());
            statement.setLong(17, record.lastActivity());
            statement.setString(18, record.journalFingerprint());
            statement.executeUpdate();
        }
    }

    private static PaymentSummary computePaymentSummary(Connection connection, String ownerId, String loanId) throws SQLException {
        String sql = "SELECT COUNT(*) AS payment_count, MAX(occurred_at) AS last_payment_at " +
            "FROM loan_payment_projection_v1 WHERE owner_id = ? AND loan_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    int count = result.getInt("payment_count");
                    Long lastPaymentAt = nullableLong(result, "last_payment_at");
                    return new PaymentSummary(count, lastPaymentAt);
                }
                return new PaymentSummary(0, null);
            }
        }
    }

    private record PaymentSummary(int count, Long lastPaymentAt) {}

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

}
