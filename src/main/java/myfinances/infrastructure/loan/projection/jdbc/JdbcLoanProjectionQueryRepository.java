package myfinances.infrastructure.loan.projection.jdbc;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import myfinances.domain.loan.projection.LoanPaymentProjection;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.projection.LoanSummaryFilter;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.infrastructure.loan.projection.mapper.LoanProjectionMapper;
import myfinances.infrastructure.loan.projection.model.LoanPaymentProjectionRecord;
import myfinances.infrastructure.loan.projection.model.LoanSummaryProjectionRecord;

public final class JdbcLoanProjectionQueryRepository implements LoanProjectionQueryRepository {
    private final SqliteDatabase database;
    private final LoanProjectionMapper mapper;

    public JdbcLoanProjectionQueryRepository(SqliteDatabase database) {
        this(database, new LoanProjectionMapper());
    }

    public JdbcLoanProjectionQueryRepository(SqliteDatabase database, LoanProjectionMapper mapper) {
        this.database = database;
        this.mapper = mapper;
        LoanProjectionSchemaV1.initialize(database);
        myfinances.infrastructure.loan.admin.LoanAdminStateSchemaV1.initialize(database);
    }

    @Override
    public List<LoanPaymentProjection> getPaymentProjections(String ownerId, String loanId) {
        String sql = "SELECT source_event_id, operation_id, owner_id, loan_id, account_id, transaction_id, occurred_at, amount_cents, direction, note " +
            "FROM loan_payment_projection_v1 WHERE owner_id = ? AND loan_id = ? ORDER BY occurred_at";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                List<LoanPaymentProjection> projections = new ArrayList<>();
                while (result.next()) {
                    projections.add(mapper.toDomain(readPaymentRecord(result)));
                }
                return List.copyOf(projections);
            }
        } catch (SQLException ex) {
            throw new LoanProjectionPersistenceException(ex);
        }
    }

    @Override
    public List<LoanSummaryProjection> listSummaries(String ownerId, LoanSummaryFilter filter) {
        return querySummaries(ownerId, filter, false);
    }

    @Override
    public List<LoanSummaryProjection> listActiveSummaries(String ownerId, LoanSummaryFilter filter) {
        return querySummaries(ownerId, filter, true);
    }

    private List<LoanSummaryProjection> querySummaries(String ownerId, LoanSummaryFilter filter, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT s.loan_id, s.owner_id, s.counterparty, s.loan_type, s.currency, s.default_account_id, s.notes, " +
                "s.principal_cents, s.total_paid_cents, s.pending_cents, s.overpaid_cents, s.payment_count, s.last_payment_at, s.progress_percent, " +
                "s.status, s.closed_at, s.last_activity, s.journal_fingerprint "
        );
        sql.append("FROM loan_summary_projection_v1 s");
        if (activeOnly) {
            sql.append(" LEFT JOIN loan_admin_state_v1 a ON a.owner_id = s.owner_id AND a.loan_id = s.loan_id");
        }
        sql.append(" WHERE s.owner_id = ?");
        params.add(ownerId);

        if (filter.loanType() != null) {
            sql.append(" AND s.loan_type = ?");
            params.add(filter.loanType().name());
        }
        if (filter.status() != null) {
            sql.append(" AND s.status = ?");
            params.add(filter.status().name());
        }
        if (activeOnly) {
            sql.append(" AND COALESCE(a.archived, 0) = 0 AND s.pending_cents > 0");
        }

        String orderColumn = switch (filter.sortBy()) {
            case COUNTERPARTY -> "s.counterparty";
            case PRINCIPAL_CENTS -> "s.principal_cents";
            case PENDING_CENTS -> "s.pending_cents";
            case LAST_ACTIVITY -> "s.last_activity";
        };
        sql.append(" ORDER BY ").append(orderColumn);
        sql.append(filter.ascending() ? " ASC" : " DESC");

        if (filter.limit() != null) {
            sql.append(" LIMIT ?");
            params.add(filter.limit().longValue());
        }

        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<LoanSummaryProjection> projections = new ArrayList<>();
                while (result.next()) {
                    projections.add(mapper.toDomain(readSummaryRecord(result)));
                }
                return List.copyOf(projections);
            }
        } catch (SQLException ex) {
            throw new LoanProjectionPersistenceException(ex);
        }
    }

    @Override
    public LoanSummaryProjection getSummaryProjection(String ownerId, String loanId) {
        String sql = "SELECT loan_id, owner_id, counterparty, loan_type, currency, default_account_id, notes, " +
            "principal_cents, total_paid_cents, pending_cents, overpaid_cents, payment_count, last_payment_at, progress_percent, " +
            "status, closed_at, last_activity, journal_fingerprint " +
            "FROM loan_summary_projection_v1 WHERE owner_id = ? AND loan_id = ?";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return mapper.toDomain(readSummaryRecord(result));
                }
                return null;
            }
        } catch (SQLException ex) {
            throw new LoanProjectionPersistenceException(ex);
        }
    }

    private static LoanPaymentProjectionRecord readPaymentRecord(ResultSet result) throws SQLException {
        return new LoanPaymentProjectionRecord(
            result.getString("source_event_id"),
            result.getString("operation_id"),
            result.getString("owner_id"),
            result.getString("loan_id"),
            result.getString("account_id"),
            result.getString("transaction_id"),
            result.getLong("occurred_at"),
            result.getLong("amount_cents"),
            result.getString("direction"),
            result.getString("note")
        );
    }

    private static LoanSummaryProjectionRecord readSummaryRecord(ResultSet result) throws SQLException {
        return new LoanSummaryProjectionRecord(
            result.getString("loan_id"),
            result.getString("owner_id"),
            result.getString("counterparty"),
            result.getString("loan_type"),
            result.getString("currency"),
            result.getString("default_account_id"),
            result.getString("notes"),
            result.getLong("principal_cents"),
            result.getLong("total_paid_cents"),
            result.getLong("pending_cents"),
            result.getLong("overpaid_cents"),
            result.getInt("payment_count"),
            nullableLong(result, "last_payment_at"),
            result.getInt("progress_percent"),
            result.getString("status"),
            nullableLong(result, "closed_at"),
            result.getLong("last_activity"),
            result.getString("journal_fingerprint")
        );
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
