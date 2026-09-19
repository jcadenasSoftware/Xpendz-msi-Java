package myfinances.infrastructure.loan.admin;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import myfinances.infrastructure.loan.sync.LoanMergePolicy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import myfinances.domain.loan.admin.LoanAdminState;
import myfinances.infrastructure.loan.jdbc.LoanPersistenceException;

public final class JdbcLoanAdminStateRepository {
    private final SqliteDatabase database;

    public JdbcLoanAdminStateRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        LoanAdminStateSchemaV1.initialize(database);
    }

    public LoanAdminState archive(String ownerId, String loanId, String updatedBy) {
        return setArchived(ownerId, loanId, true, updatedBy);
    }

    public LoanAdminState unarchive(String ownerId, String loanId, String updatedBy) {
        return setArchived(ownerId, loanId, false, updatedBy);
    }

    public LoanAdminState upsertFromRemote(
        String ownerId,
        String loanId,
        boolean archived,
        Long archivedAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy
    ) {
        LoanAdminState local = getByLoan(ownerId, loanId);
        LoanAdminState remote = new LoanAdminState(loanId, ownerId, archived, archivedAtEpochSec, updatedAtEpochSec, updatedBy);
        if (!LoanMergePolicy.shouldAcceptRemote(local, remote)) {
            return local == null ? remote : local;
        }
        return upsert(ownerId, loanId, archived, archivedAtEpochSec, updatedAtEpochSec, updatedBy, false);
    }

    public java.util.List<LoanAdminState> listPendingForSync(String ownerId) {
        String sql =
            "SELECT loan_id, owner_id, archived, archived_at_epoch_sec, updated_at_epoch_sec, updated_by " +
                "FROM loan_admin_state_v1 WHERE owner_id = ? AND pending_sync = 1";
        java.util.List<LoanAdminState> states = new java.util.ArrayList<>();
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    states.add(readState(result));
                }
            }
            return states;
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    public void markSynced(String ownerId, String loanId) {
        String sql = "UPDATE loan_admin_state_v1 SET pending_sync = 0 WHERE owner_id = ? AND loan_id = ?";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    public LoanAdminState getByLoan(String ownerId, String loanId) {
        String sql =
            "SELECT loan_id, owner_id, archived, archived_at_epoch_sec, updated_at_epoch_sec, updated_by " +
                "FROM loan_admin_state_v1 WHERE owner_id = ? AND loan_id = ? LIMIT 1";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, loanId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return readState(result);
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private LoanAdminState setArchived(String ownerId, String loanId, boolean archived, String updatedBy) {
        long now = System.currentTimeMillis() / 1000;
        Long archivedAt = archived ? now : null;
        return upsert(ownerId, loanId, archived, archivedAt, now, updatedBy, true);
    }

    private LoanAdminState upsert(
        String ownerId,
        String loanId,
        boolean archived,
        Long archivedAtEpochSec,
        long updatedAtEpochSec,
        String updatedBy,
        boolean pendingSync
    ) {
        String sql =
            "INSERT INTO loan_admin_state_v1 (loan_id, owner_id, archived, archived_at_epoch_sec, updated_at_epoch_sec, updated_by, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(owner_id, loan_id) DO UPDATE SET " +
                "archived = excluded.archived, archived_at_epoch_sec = excluded.archived_at_epoch_sec, " +
                "updated_at_epoch_sec = excluded.updated_at_epoch_sec, updated_by = excluded.updated_by, " +
                "pending_sync = excluded.pending_sync";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, loanId);
            statement.setString(2, ownerId);
            statement.setInt(3, archived ? 1 : 0);
            setNullableLong(statement, 4, archivedAtEpochSec);
            statement.setLong(5, updatedAtEpochSec);
            if (updatedBy == null || updatedBy.isBlank()) {
                statement.setObject(6, null);
            } else {
                statement.setString(6, updatedBy);
            }
            statement.setInt(7, pendingSync ? 1 : 0);
            statement.executeUpdate();
            return new LoanAdminState(loanId, ownerId, archived, archivedAtEpochSec, updatedAtEpochSec, updatedBy);
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static LoanAdminState readState(ResultSet result) throws SQLException {
        return new LoanAdminState(
            result.getString("loan_id"),
            result.getString("owner_id"),
            result.getInt("archived") != 0,
            nullableLong(result, "archived_at_epoch_sec"),
            result.getLong("updated_at_epoch_sec"),
            result.getString("updated_by")
        );
    }

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
}
