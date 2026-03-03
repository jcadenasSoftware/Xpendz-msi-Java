package com.myfinaces.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class LoanRepository {

    public static final String TYPE_LENT = "LENT";
    public static final String TYPE_BORROWED = "BORROWED";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private final SqliteDatabase db;

    public LoanRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record Loan(
        String id,
        String userUid,
        String type,
        String counterpartyName,
        long principalCents,
        String currency,
        String status,
        String notes,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {
    }

    public String create(String userUid, String type, String counterpartyName, long principalCents, String currency, String notes) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(counterpartyName, "counterpartyName");
        Objects.requireNonNull(currency, "currency");

        if (!TYPE_LENT.equals(type) && !TYPE_BORROWED.equals(type)) {
            throw new IllegalArgumentException("type");
        }
        if (principalCents < 0) {
            throw new IllegalArgumentException("principalCents");
        }

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loans (id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, type);
            ps.setString(4, counterpartyName);
            ps.setLong(5, principalCents);
            ps.setString(6, currency);
            ps.setString(7, STATUS_OPEN);
            ps.setString(8, notes);
            ps.setLong(9, now);
            ps.setLong(10, now);
            ps.executeUpdate();
        }

        return id;
    }

    public void update(String userUid, String loanId, String type, String counterpartyName, long principalCents, String currency, String status, String notes) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(counterpartyName, "counterpartyName");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");

        if (!TYPE_LENT.equals(type) && !TYPE_BORROWED.equals(type)) {
            throw new IllegalArgumentException("type");
        }
        if (!STATUS_OPEN.equals(status) && !STATUS_CLOSED.equals(status)) {
            throw new IllegalArgumentException("status");
        }
        if (principalCents < 0) {
            throw new IllegalArgumentException("principalCents");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE loans SET type = ?, counterparty_name = ?, principal_cents = ?, currency = ?, status = ?, notes = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, type);
            ps.setString(2, counterpartyName);
            ps.setLong(3, principalCents);
            ps.setString(4, currency);
            ps.setString(5, status);
            ps.setString(6, notes);
            ps.setLong(7, now);
            ps.setString(8, userUid);
            ps.setString(9, loanId);
            ps.executeUpdate();
        }
    }

    public Loan getByIdOrNull(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, created_at_epoch_sec, updated_at_epoch_sec FROM loans WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Loan(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("type"),
                    rs.getString("counterparty_name"),
                    rs.getLong("principal_cents"),
                    rs.getString("currency"),
                    rs.getString("status"),
                    rs.getString("notes"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public List<Loan> listByType(String userUid, String type, String currency, boolean onlyOpen) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");

        String sql =
            "SELECT id, user_uid, type, counterparty_name, principal_cents, currency, status, notes, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM loans WHERE user_uid = ? AND type = ? AND currency = ?";

        if (onlyOpen) {
            sql += " AND status = 'OPEN'";
        }
        sql += " ORDER BY updated_at_epoch_sec DESC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, type);
            ps.setString(3, currency);

            List<Loan> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Loan(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("type"),
                        rs.getString("counterparty_name"),
                        rs.getLong("principal_cents"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("notes"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public void delete(String userUid, String loanId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(loanId, "loanId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM loans WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, loanId);
            ps.executeUpdate();
        }
    }
}
