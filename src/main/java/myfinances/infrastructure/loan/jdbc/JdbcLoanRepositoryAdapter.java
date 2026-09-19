package myfinances.infrastructure.loan.jdbc;

import com.myfinaces.db.SqliteDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.repository.LoanRepository;
import myfinances.domain.loan.snapshot.LoanSnapshot;
import myfinances.infrastructure.loan.mapper.LoanInfrastructureMapper;
import myfinances.infrastructure.loan.model.LoanEventRecord;
import myfinances.infrastructure.loan.model.LoanSnapshotRecord;

public final class JdbcLoanRepositoryAdapter implements LoanRepository {
    private static final String EVENT_COLUMNS =
        "event_id, operation_id, loan_id, owner_id, event_type, event_schema_version, " +
        "amount_cents, account_id, transaction_id, note, occurred_at, recorded_at, actor_id, origin_id, " +
        "payload_loan_type, payload_counterparty_name, payload_currency, payload_default_account_id, " +
        "payload_notes, payload_legacy_direction, payload_legacy_source, payload_reason, payload_target_event_id, " +
        "metadata_counterparty_present, metadata_counterparty_value, metadata_account_present, " +
        "metadata_account_value, metadata_notes_present, metadata_notes_value";

    private final SqliteDatabase database;
    private final LoanInfrastructureMapper mapper;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();

    public JdbcLoanRepositoryAdapter(SqliteDatabase database) {
        this(database, new LoanInfrastructureMapper());
    }

    public JdbcLoanRepositoryAdapter(SqliteDatabase database, LoanInfrastructureMapper mapper) {
        this.database = database;
        this.mapper = mapper;
        LoanSchemaV1.initialize(database);
    }

    @Override
    public List<LoanMovement> getJournal(String ownerId, String loanId) {
        return queryEvents(
            "SELECT " + EVENT_COLUMNS + " FROM loan_journal_v1 " +
                "WHERE owner_id = ? AND loan_id = ? ORDER BY occurred_at, recorded_at, event_id",
            ownerId,
            loanId
        );
    }

    @Override
    public void appendEvent(LoanMovement event) {
        Connection connection = requireTransaction();
        LoanEventRecord record = mapper.toRecord(event);
        String sql = "INSERT INTO loan_journal_v1 (" + EVENT_COLUMNS + ") VALUES (" +
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindEvent(statement, record);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    @Override
    public Optional<LoanMovement> findByOperationId(String ownerId, String operationId) {
        return querySingleEvent(
            "SELECT " + EVENT_COLUMNS + " FROM loan_journal_v1 WHERE owner_id = ? AND operation_id = ?",
            ownerId,
            operationId
        );
    }

    @Override
    public Optional<LoanMovement> findByEventId(String ownerId, String eventId) {
        return querySingleEvent(
            "SELECT " + EVENT_COLUMNS + " FROM loan_journal_v1 WHERE owner_id = ? AND event_id = ?",
            ownerId,
            eventId
        );
    }

    @Override
    public Optional<LoanSnapshot> loadSnapshot(String ownerId, String loanId) {
        String sql = "SELECT loan_id, owner_id, loan_type, counterparty_name, currency, default_account_id, notes, " +
            "principal_cents, total_paid_cents, net_balance_cents, pending_cents, overpaid_cents, status, closed_at, " +
            "last_activity_at, journal_event_count, journal_fingerprint, reducer_version " +
            "FROM loan_snapshots_v1 WHERE owner_id = ? AND loan_id = ?";
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ownerId);
                statement.setString(2, loanId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(mapper.toDomain(readSnapshot(result))) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void replaceSnapshot(LoanSnapshot snapshot) {
        Connection connection = requireTransaction();
        LoanSnapshotRecord record = mapper.toRecord(snapshot);
        String sql = "INSERT INTO loan_snapshots_v1 (" +
            "loan_id, owner_id, loan_type, counterparty_name, currency, default_account_id, notes, principal_cents, " +
            "total_paid_cents, net_balance_cents, pending_cents, overpaid_cents, status, closed_at, last_activity_at, " +
            "journal_event_count, journal_fingerprint, reducer_version" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(owner_id, loan_id) DO UPDATE SET " +
            "loan_type = excluded.loan_type, counterparty_name = excluded.counterparty_name, " +
            "currency = excluded.currency, default_account_id = excluded.default_account_id, notes = excluded.notes, " +
            "principal_cents = excluded.principal_cents, total_paid_cents = excluded.total_paid_cents, " +
            "net_balance_cents = excluded.net_balance_cents, pending_cents = excluded.pending_cents, " +
            "overpaid_cents = excluded.overpaid_cents, status = excluded.status, closed_at = excluded.closed_at, " +
            "last_activity_at = excluded.last_activity_at, journal_event_count = excluded.journal_event_count, " +
            "journal_fingerprint = excluded.journal_fingerprint, reducer_version = excluded.reducer_version";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindSnapshot(statement, record);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    public <T> T inTransaction(Supplier<T> operation) {
        if (transactionConnection.get() != null) {
            return operation.get();
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            transactionConnection.set(connection);
            try {
                T result = operation.get();
                connection.commit();
                return result;
            } catch (RuntimeException ex) {
                rollback(connection, ex);
                throw ex;
            } finally {
                transactionConnection.remove();
            }
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private List<LoanMovement> queryEvents(String sql, String first, String second) {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, first);
                statement.setString(2, second);
                try (ResultSet result = statement.executeQuery()) {
                    List<LoanMovement> events = new ArrayList<>();
                    while (result.next()) {
                        events.add(mapper.toDomain(readEvent(result)));
                    }
                    return List.copyOf(events);
                }
            }
        });
    }

    private Optional<LoanMovement> querySingleEvent(String sql, String first, String second) {
        List<LoanMovement> events = queryEvents(sql, first, second);
        return events.isEmpty() ? Optional.empty() : Optional.of(events.getFirst());
    }

    private Connection requireTransaction() {
        Connection connection = transactionConnection.get();
        if (connection == null) {
            throw new LoanPersistenceException("Loan writes require a transaction executor");
        }
        return connection;
    }

    private <T> T withConnection(SqlOperation<T> operation) {
        Connection current = transactionConnection.get();
        if (current != null) {
            try {
                return operation.run(current);
            } catch (SQLException ex) {
                throw new LoanPersistenceException(ex);
            }
        }
        try (Connection connection = database.openConnection()) {
            return operation.run(connection);
        } catch (SQLException ex) {
            throw new LoanPersistenceException(ex);
        }
    }

    private static void bindEvent(PreparedStatement statement, LoanEventRecord record) throws SQLException {
        statement.setString(1, record.eventId());
        statement.setString(2, record.operationId());
        statement.setString(3, record.loanId());
        statement.setString(4, record.ownerId());
        statement.setString(5, record.eventType());
        statement.setInt(6, record.eventSchemaVersion());
        setNullableLong(statement, 7, record.amountCents());
        statement.setString(8, record.accountId());
        statement.setString(9, record.transactionId());
        statement.setString(10, record.note());
        statement.setLong(11, record.occurredAt());
        statement.setLong(12, record.recordedAt());
        statement.setString(13, record.actorId());
        statement.setString(14, record.originId());
        statement.setString(15, record.payloadLoanType());
        statement.setString(16, record.payloadCounterpartyName());
        statement.setString(17, record.payloadCurrency());
        statement.setString(18, record.payloadDefaultAccountId());
        statement.setString(19, record.payloadNotes());
        statement.setString(20, record.payloadLegacyDirection());
        statement.setString(21, record.payloadLegacySource());
        statement.setString(22, record.payloadReason());
        statement.setString(23, record.payloadTargetEventId());
        statement.setInt(24, record.metadataCounterpartyPresent() ? 1 : 0);
        statement.setString(25, record.metadataCounterpartyValue());
        statement.setInt(26, record.metadataAccountPresent() ? 1 : 0);
        statement.setString(27, record.metadataAccountValue());
        statement.setInt(28, record.metadataNotesPresent() ? 1 : 0);
        statement.setString(29, record.metadataNotesValue());
    }

    private static void bindSnapshot(PreparedStatement statement, LoanSnapshotRecord record) throws SQLException {
        statement.setString(1, record.loanId());
        statement.setString(2, record.ownerId());
        statement.setString(3, record.loanType());
        statement.setString(4, record.counterpartyName());
        statement.setString(5, record.currency());
        statement.setString(6, record.defaultAccountId());
        statement.setString(7, record.notes());
        statement.setLong(8, record.principalCents());
        statement.setLong(9, record.totalPaidCents());
        statement.setLong(10, record.netBalanceCents());
        statement.setLong(11, record.pendingCents());
        statement.setLong(12, record.overpaidCents());
        statement.setString(13, record.status());
        setNullableLong(statement, 14, record.closedAt());
        statement.setLong(15, record.lastActivityAt());
        statement.setInt(16, record.journalEventCount());
        statement.setString(17, record.journalFingerprint());
        statement.setInt(18, record.reducerVersion());
    }

    private static LoanEventRecord readEvent(ResultSet result) throws SQLException {
        return new LoanEventRecord(
            result.getString("event_id"),
            result.getString("operation_id"),
            result.getString("loan_id"),
            result.getString("owner_id"),
            result.getString("event_type"),
            result.getInt("event_schema_version"),
            nullableLong(result, "amount_cents"),
            result.getString("account_id"),
            result.getString("transaction_id"),
            result.getString("note"),
            result.getLong("occurred_at"),
            result.getLong("recorded_at"),
            result.getString("actor_id"),
            result.getString("origin_id"),
            result.getString("payload_loan_type"),
            result.getString("payload_counterparty_name"),
            result.getString("payload_currency"),
            result.getString("payload_default_account_id"),
            result.getString("payload_notes"),
            result.getString("payload_legacy_direction"),
            result.getString("payload_legacy_source"),
            result.getString("payload_reason"),
            result.getString("payload_target_event_id"),
            result.getInt("metadata_counterparty_present") != 0,
            result.getString("metadata_counterparty_value"),
            result.getInt("metadata_account_present") != 0,
            result.getString("metadata_account_value"),
            result.getInt("metadata_notes_present") != 0,
            result.getString("metadata_notes_value")
        );
    }

    private static LoanSnapshotRecord readSnapshot(ResultSet result) throws SQLException {
        return new LoanSnapshotRecord(
            result.getString("loan_id"),
            result.getString("owner_id"),
            result.getString("loan_type"),
            result.getString("counterparty_name"),
            result.getString("currency"),
            result.getString("default_account_id"),
            result.getString("notes"),
            result.getLong("principal_cents"),
            result.getLong("total_paid_cents"),
            result.getLong("net_balance_cents"),
            result.getLong("pending_cents"),
            result.getLong("overpaid_cents"),
            result.getString("status"),
            nullableLong(result, "closed_at"),
            result.getLong("last_activity_at"),
            result.getInt("journal_event_count"),
            result.getString("journal_fingerprint"),
            result.getInt("reducer_version")
        );
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void rollback(Connection connection, RuntimeException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(Connection connection) throws SQLException;
    }
}
