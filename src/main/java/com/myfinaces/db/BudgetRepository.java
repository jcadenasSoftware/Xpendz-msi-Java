package com.myfinaces.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class BudgetRepository {

    public static final String BASE_BUDGET_MONTH = "__BASE__";

    private final SqliteDatabase db;

    public BudgetRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record Budget(
        String id,
        String userUid,
        String month,
        String categoryId,
        long limitCents,
        String currency,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {
    }

    public record BudgetProgress(
        Budget budget,
        long spentCents
    ) {
        public long remainingCents() {
            return budget.limitCents() - spentCents;
        }
    }

    public Budget getByUniqueKeyOrNull(String userUid, String month, String currency, String categoryId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(categoryId, "categoryId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, month, category_id, limit_cents, currency, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM budgets WHERE user_uid = ? AND month = ? AND currency = ? AND category_id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, month);
            ps.setString(3, currency);
            ps.setString(4, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Budget(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("month"),
                    rs.getString("category_id"),
                    rs.getLong("limit_cents"),
                    rs.getString("currency"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public List<Budget> listByUser(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, month, category_id, limit_cents, currency, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM budgets WHERE user_uid = ? ORDER BY updated_at_epoch_sec DESC"
        )) {
            ps.setString(1, userUid);
            List<Budget> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Budget(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("month"),
                        rs.getString("category_id"),
                        rs.getLong("limit_cents"),
                        rs.getString("currency"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public void upsertFromRemote(String userUid, Budget remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        Budget localById = getByIdOrNull(userUid, remote.id());
        if (localById == null) {
            Budget localByKey = getByUniqueKeyOrNull(userUid, remote.month(), remote.currency(), remote.categoryId());
            if (localByKey != null && !localByKey.id().equals(remote.id())) {
                if (localByKey.updatedAtEpochSec() >= remote.updatedAtEpochSec()) {
                    return;
                }
                delete(userUid, localByKey.id());
            }
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO budgets (id, user_uid, month, category_id, limit_cents, currency, created_at_epoch_sec, updated_at_epoch_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, userUid);
                ps.setString(3, remote.month());
                ps.setString(4, remote.categoryId());
                ps.setLong(5, remote.limitCents());
                ps.setString(6, remote.currency());
                ps.setLong(7, remote.createdAtEpochSec());
                ps.setLong(8, remote.updatedAtEpochSec());
                ps.executeUpdate();
            }
            return;
        }

        if (localById.updatedAtEpochSec() >= remote.updatedAtEpochSec()) {
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE budgets SET month = ?, category_id = ?, limit_cents = ?, currency = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.month());
            ps.setString(2, remote.categoryId());
            ps.setLong(3, remote.limitCents());
            ps.setString(4, remote.currency());
            ps.setLong(5, remote.updatedAtEpochSec());
            ps.setString(6, userUid);
            ps.setString(7, remote.id());
            ps.executeUpdate();
        }
    }

    public String create(String userUid, String month, String categoryId, long limitCents, String currency) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(currency, "currency");

        if (limitCents < 0) {
            throw new IllegalArgumentException("limitCents");
        }

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO budgets (id, user_uid, month, category_id, limit_cents, currency, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, month);
            ps.setString(4, categoryId);
            ps.setLong(5, limitCents);
            ps.setString(6, currency);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        }

        return id;
    }

    public void update(String userUid, String budgetId, String month, String categoryId, long limitCents, String currency) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(budgetId, "budgetId");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(currency, "currency");

        if (limitCents < 0) {
            throw new IllegalArgumentException("limitCents");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE budgets SET month = ?, category_id = ?, limit_cents = ?, currency = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, month);
            ps.setString(2, categoryId);
            ps.setLong(3, limitCents);
            ps.setString(4, currency);
            ps.setLong(5, now);
            ps.setString(6, userUid);
            ps.setString(7, budgetId);
            ps.executeUpdate();
        }
    }

    public void delete(String userUid, String budgetId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(budgetId, "budgetId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM budgets WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, budgetId);
            ps.executeUpdate();
        }
    }

    public Budget getByIdOrNull(String userUid, String budgetId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(budgetId, "budgetId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, month, category_id, limit_cents, currency, created_at_epoch_sec, updated_at_epoch_sec FROM budgets WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, budgetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Budget(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("month"),
                    rs.getString("category_id"),
                    rs.getLong("limit_cents"),
                    rs.getString("currency"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public List<Budget> listByMonthAndCurrency(String userUid, String month, String currency) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(currency, "currency");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, month, category_id, limit_cents, currency, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM budgets WHERE user_uid = ? AND month = ? AND currency = ? ORDER BY created_at_epoch_sec ASC"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, month);
            ps.setString(3, currency);
            List<Budget> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Budget(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("month"),
                        rs.getString("category_id"),
                        rs.getLong("limit_cents"),
                        rs.getString("currency"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public long computeSpentExpenseCentsForCategoryTree(
        String userUid,
        String categoryId,
        String month,
        String currency
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(currency, "currency");

        long[] range = monthToEpochRange(month);
        long from = range[0];
        long toExclusive = range[1];

        String sql =
            "WITH RECURSIVE subtree(id) AS (" +
            "  SELECT id FROM categories WHERE user_uid = ? AND id = ?" +
            "  UNION ALL " +
            "  SELECT c.id FROM categories c INNER JOIN subtree s ON c.parent_id = s.id WHERE c.user_uid = ?" +
            ") " +
            "SELECT COALESCE(SUM(t.amount_cents), 0) AS total " +
            "FROM transactions t " +
            "INNER JOIN accounts a ON a.id = t.account_id " +
            "WHERE t.user_uid = ? " +
            "  AND a.user_uid = ? " +
            "  AND a.currency = ? " +
            "  AND t.kind = 'EXPENSE' " +
            "  AND t.occurred_at_epoch_sec >= ? " +
            "  AND t.occurred_at_epoch_sec < ? " +
            "  AND t.category_id IN (SELECT id FROM subtree)";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, categoryId);
            ps.setString(3, userUid);
            ps.setString(4, userUid);
            ps.setString(5, userUid);
            ps.setString(6, currency);
            ps.setLong(7, from);
            ps.setLong(8, toExclusive);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong("total");
            }
        }
    }

    public List<BudgetProgress> listProgressByMonthAndCurrency(String userUid, String month, String currency) throws SQLException {
        List<Budget> budgets = listByMonthAndCurrency(userUid, BASE_BUDGET_MONTH, currency);
        List<BudgetProgress> out = new ArrayList<>();
        for (Budget b : budgets) {
            long spent = computeSpentExpenseCentsForCategoryTree(userUid, b.categoryId(), month, b.currency());
            out.add(new BudgetProgress(b, spent));
        }
        return out;
    }

    private static long[] monthToEpochRange(String month) {
        String m = month.trim();
        if (m.length() != 7 || m.charAt(4) != '-') {
            throw new IllegalArgumentException("month");
        }
        int year = Integer.parseInt(m.substring(0, 4));
        int mon = Integer.parseInt(m.substring(5, 7));
        LocalDate start = LocalDate.of(year, mon, 1);
        LocalDate end = start.plusMonths(1);
        long from = start.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long to = end.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return new long[] { from, to };
    }

    public List<String> listDistinctExpenseMonths(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        List<String> months = new ArrayList<>();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT month FROM budgets WHERE user_uid = ? ORDER BY month DESC"
        )) {
            ps.setString(1, userUid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    months.add(rs.getString("month"));
                }
            }
        }
        return months;
    }
}
