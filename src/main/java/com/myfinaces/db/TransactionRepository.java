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

public final class TransactionRepository {

    private final SqliteDatabase db;

    public TransactionRepository(SqliteDatabase db) {
        this.db = db;
    }

    private static List<String> expandedReportKinds(String kind) {
        if (kind == null) {
            return List.of();
        }
        String k = kind.trim().toUpperCase();
        if (k.isBlank()) {
            return List.of();
        }
        if ("INCOME".equals(k)) {
            return List.of("INCOME", "LOAN_BORROWED_IN", "LOAN_REPAYMENT_PRINCIPAL_IN");
        }
        if ("EXPENSE".equals(k)) {
            return List.of("EXPENSE", "LOAN_LENT_OUT", "LOAN_REPAYMENT_PRINCIPAL_OUT");
        }
        return List.of(k);
    }

    public record TransactionRow(
        String id,
        String userUid,
        String accountId,
        String accountName,
        String categoryId,
        String categoryName,
        String kind,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    public record TransactionSyncRow(
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
    ) {
    }

    public record MonthlyCategoryTotal(
        String rootCategoryId,
        String rootCategoryName,
        int month,
        long totalAmountCents
    ) {
    }

    public record MonthlyCategoryDetailTotal(
        String rootCategoryId,
        String rootCategoryName,
        String categoryId,
        String categoryName,
        int month,
        long totalAmountCents
    ) {
    }

    public record MonthlyCategoryDetailAccountTotal(
        String rootCategoryId,
        String rootCategoryName,
        String categoryId,
        String categoryName,
        String accountId,
        String accountName,
        int month,
        long totalAmountCents
    ) {
    }

    public List<String> listAccountIdsUsedInCategory(
        String userUid,
        int year,
        String kind,
        String categoryId
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");

        int y = year <= 0 ? java.time.LocalDate.now().getYear() : year;
        List<String> kinds = expandedReportKinds(kind);
        boolean hasKinds = !kinds.isEmpty();

        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT t.account_id AS account_id " +
            "FROM transactions t " +
            "WHERE t.user_uid = ? " +
            "  AND CAST(strftime('%Y', t.occurred_at_epoch_sec, 'unixepoch', 'localtime') AS INTEGER) = ? " +
            "  AND t.category_id = ?"
        );
        if (hasKinds) {
            sql.append(" AND t.kind IN (");
            for (int i = 0; i < kinds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY account_id ASC");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, userUid);
            ps.setInt(idx++, y);
            ps.setString(idx++, categoryId);
            if (hasKinds) {
                for (String reportKind : kinds) {
                    ps.setString(idx++, reportKind);
                }
            }

            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("account_id");
                    if (id != null && !id.isBlank()) {
                        out.add(id);
                    }
                }
            }
            return out;
        }
    }

    public String create(
        String userUid,
        String accountId,
        String categoryId,
        String kind,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(kind, "kind");

        if (amountCents < 0) {
            throw new IllegalArgumentException("amountCents");
        }

        String id = UUID.randomUUID().toString();
        createWithId(id, userUid, accountId, categoryId, kind, amountCents, occurredAtEpochSec, note);
        return id;
    }

    public String createWithId(
        String id,
        String userUid,
        String accountId,
        String categoryId,
        String kind,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(kind, "kind");

        if (amountCents < 0) {
            throw new IllegalArgumentException("amountCents");
        }

        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, accountId);
            ps.setString(4, categoryId);
            ps.setString(5, kind);
            ps.setLong(6, amountCents);
            ps.setLong(7, occurredAtEpochSec);
            ps.setString(8, note);
            ps.setLong(9, now);
            ps.setLong(10, now);
            ps.executeUpdate();
        }

        return id;
    }

    public TransactionSyncRow getForSyncByIdOrNull(String userUid, String transactionId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");

        String sql =
            "SELECT id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transactions WHERE user_uid = ? AND id = ?";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TransactionSyncRow(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("account_id"),
                    rs.getString("category_id"),
                    rs.getString("kind"),
                    rs.getLong("amount_cents"),
                    rs.getLong("occurred_at_epoch_sec"),
                    rs.getString("note"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public void upsertFromRemote(String userUid, TransactionSyncRow remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        TransactionSyncRow local = getForSyncByIdOrNull(userUid, remote.id());
        if (local == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions (id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec, pending_sync) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, userUid);
                ps.setString(3, remote.accountId());
                ps.setString(4, remote.categoryId());
                ps.setString(5, remote.kind());
                ps.setLong(6, remote.amountCents());
                ps.setLong(7, remote.occurredAtEpochSec());
                ps.setString(8, remote.note());
                ps.setLong(9, remote.createdAtEpochSec());
                ps.setLong(10, remote.updatedAtEpochSec());
                ps.executeUpdate();
            }
            return;
        }

        if (remote.updatedAtEpochSec() < local.updatedAtEpochSec()) {
            return;
        }
        if (remote.updatedAtEpochSec() == local.updatedAtEpochSec()) {
            boolean same =
                Objects.equals(remote.accountId(), local.accountId()) &&
                    Objects.equals(remote.categoryId(), local.categoryId()) &&
                    Objects.equals(remote.kind(), local.kind()) &&
                    remote.amountCents() == local.amountCents() &&
                    remote.occurredAtEpochSec() == local.occurredAtEpochSec() &&
                    Objects.equals(remote.note(), local.note());
            if (same) {
                return;
            }
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE transactions SET account_id = ?, category_id = ?, kind = ?, amount_cents = ?, occurred_at_epoch_sec = ?, note = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ?, pending_sync = 0 " +
            "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.accountId());
            ps.setString(2, remote.categoryId());
            ps.setString(3, remote.kind());
            ps.setLong(4, remote.amountCents());
            ps.setLong(5, remote.occurredAtEpochSec());
            ps.setString(6, remote.note());
            ps.setLong(7, remote.createdAtEpochSec());
            ps.setLong(8, remote.updatedAtEpochSec());
            ps.setString(9, userUid);
            ps.setString(10, remote.id());
            ps.executeUpdate();
        }
    }

    public TransactionSyncRow getForSyncById(String userUid, String transactionId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");

        String sql =
            "SELECT id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transactions WHERE user_uid = ? AND id = ?";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("transaction_not_found");
                }
                return new TransactionSyncRow(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("account_id"),
                    rs.getString("category_id"),
                    rs.getString("kind"),
                    rs.getLong("amount_cents"),
                    rs.getLong("occurred_at_epoch_sec"),
                    rs.getString("note"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public List<TransactionRow> listRecent(String userUid, int limit) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        int lim = limit <= 0 ? 50 : limit;

        String sql =
            "SELECT t.id, t.user_uid, t.account_id, a.name AS account_name, " +
            "       t.category_id, c.name AS category_name, " +
            "       t.kind, t.amount_cents, t.occurred_at_epoch_sec, t.note " +
            "FROM transactions t " +
            "INNER JOIN accounts a ON a.id = t.account_id " +
            "INNER JOIN categories c ON c.id = t.category_id " +
            "WHERE t.user_uid = ? " +
            "ORDER BY t.occurred_at_epoch_sec DESC, t.created_at_epoch_sec DESC " +
            "LIMIT ?";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setInt(2, lim);
            List<TransactionRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransactionRow(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("account_id"),
                        rs.getString("account_name"),
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        rs.getString("kind"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("note")
                    ));
                }
            }
            return out;
        }
    }

    public List<TransactionSyncRow> listAllForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        String sql =
            "SELECT id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transactions WHERE user_uid = ? ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            List<TransactionSyncRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransactionSyncRow(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("account_id"),
                        rs.getString("category_id"),
                        rs.getString("kind"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("note"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public List<TransactionRow> listFiltered(
        String userUid,
        String accountId,
        String categoryId,
        Long fromOccurredAtEpochSec,
        Long toOccurredAtEpochSec,
        int limit
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        if (categoryId == null || categoryId.isBlank()) {
            return listFiltered(userUid, accountId, (List<String>) null, fromOccurredAtEpochSec, toOccurredAtEpochSec, limit);
        }
        List<String> ids = new ArrayList<>();
        ids.add(categoryId);
        return listFiltered(userUid, accountId, ids, fromOccurredAtEpochSec, toOccurredAtEpochSec, limit);
    }

    public List<TransactionRow> listFiltered(
        String userUid,
        String accountId,
        List<String> categoryIds,
        Long fromOccurredAtEpochSec,
        Long toOccurredAtEpochSec,
        int limit
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        int lim = limit <= 0 ? 50 : limit;

        StringBuilder sql = new StringBuilder(
            "SELECT t.id, t.user_uid, t.account_id, a.name AS account_name, " +
            "       t.category_id, COALESCE(c.name, t.category_id) AS category_name, " +
            "       t.kind, t.amount_cents, t.occurred_at_epoch_sec, t.note " +
            "FROM transactions t " +
            "INNER JOIN accounts a ON a.id = t.account_id " +
            "LEFT JOIN categories c ON c.id = t.category_id " +
            "WHERE t.user_uid = ?"
        );

        List<Object> args = new ArrayList<>();
        args.add(userUid);

        if (accountId != null && !accountId.isBlank()) {
            sql.append(" AND t.account_id = ?");
            args.add(accountId);
        }
        if (categoryIds != null && !categoryIds.isEmpty()) {
            sql.append(" AND t.category_id IN (");
            for (int i = 0; i < categoryIds.size(); i++) {
                if (i > 0) {
                    sql.append(",");
                }
                sql.append("?");
                args.add(categoryIds.get(i));
            }
            sql.append(")");
        }
        if (fromOccurredAtEpochSec != null) {
            sql.append(" AND t.occurred_at_epoch_sec >= ?");
            args.add(fromOccurredAtEpochSec);
        }
        if (toOccurredAtEpochSec != null) {
            sql.append(" AND t.occurred_at_epoch_sec <= ?");
            args.add(toOccurredAtEpochSec);
        }

        sql.append(" ORDER BY t.occurred_at_epoch_sec DESC, t.created_at_epoch_sec DESC LIMIT ?");
        args.add(lim);

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object v = args.get(i);
                int idx = i + 1;
                if (v == null) {
                    ps.setObject(idx, null);
                } else if (v instanceof Long l) {
                    ps.setLong(idx, l);
                } else if (v instanceof Integer in) {
                    ps.setInt(idx, in);
                } else {
                    ps.setString(idx, v.toString());
                }
            }

            List<TransactionRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransactionRow(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("account_id"),
                        rs.getString("account_name"),
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        rs.getString("kind"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("note")
                    ));
                }
            }
            return out;
        }
    }

    public void update(
        String userUid,
        String transactionId,
        String accountId,
        String categoryId,
        String kind,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(kind, "kind");

        if (amountCents < 0) {
            throw new IllegalArgumentException("amountCents");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection()) {
            long existingUpdatedAt = 0L;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT kind, updated_at_epoch_sec FROM transactions WHERE user_uid = ? AND id = ?"
            )) {
                ps.setString(1, userUid);
                ps.setString(2, transactionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (isLoanRepaymentKind(rs.getString("kind"))) {
                            throw new IllegalArgumentException("Los pagos de préstamos deben modificarse desde Préstamos");
                        }
                        existingUpdatedAt = rs.getLong("updated_at_epoch_sec");
                    }
                }
            }
            if (now <= existingUpdatedAt) {
                now = existingUpdatedAt + 1L;
            }

            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE transactions " +
                "SET account_id = ?, category_id = ?, kind = ?, amount_cents = ?, occurred_at_epoch_sec = ?, note = ?, updated_at_epoch_sec = ?, pending_sync = 1 " +
                "WHERE user_uid = ? AND id = ?"
            )) {
            ps.setString(1, accountId);
            ps.setString(2, categoryId);
            ps.setString(3, kind);
            ps.setLong(4, amountCents);
            ps.setLong(5, occurredAtEpochSec);
            ps.setString(6, note);
            ps.setLong(7, now);
            ps.setString(8, userUid);
            ps.setString(9, transactionId);
            ps.executeUpdate();
            }
        }
    }

    public List<TransactionSyncRow> listPendingForSync(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        String sql =
            "SELECT id, user_uid, account_id, category_id, kind, amount_cents, occurred_at_epoch_sec, note, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM transactions WHERE user_uid = ? AND pending_sync = 1 ORDER BY occurred_at_epoch_sec ASC, created_at_epoch_sec ASC";

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            List<TransactionSyncRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransactionSyncRow(
                        rs.getString("id"),
                        rs.getString("user_uid"),
                        rs.getString("account_id"),
                        rs.getString("category_id"),
                        rs.getString("kind"),
                        rs.getLong("amount_cents"),
                        rs.getLong("occurred_at_epoch_sec"),
                        rs.getString("note"),
                        rs.getLong("created_at_epoch_sec"),
                        rs.getLong("updated_at_epoch_sec")
                    ));
                }
            }
            return out;
        }
    }

    public void markSynced(String userUid, String transactionId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE transactions SET pending_sync = 0 WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, transactionId);
            ps.executeUpdate();
        }
    }

    public List<String> listIdsForRemotePrune(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM transactions WHERE user_uid = ? AND pending_sync = 0"
        )) {
            ps.setString(1, userUid);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("id"));
                }
            }
            return out;
        }
    }

    public void delete(String userUid, String transactionId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT kind FROM transactions WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && isLoanRepaymentKind(rs.getString("kind"))) {
                    throw new IllegalArgumentException("Los pagos de préstamos deben eliminarse desde Préstamos");
                }
            }
        }
        deleteDirect(userUid, transactionId);
    }

    public void deleteFailedLoanTransaction(String userUid, String transactionId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(transactionId, "transactionId");
        deleteDirect(userUid, transactionId);
    }

    private void deleteDirect(String userUid, String transactionId) throws SQLException {
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM transactions WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, transactionId);
            ps.executeUpdate();
        }
    }

    private static boolean isLoanRepaymentKind(String kind) {
        return "LOAN_REPAYMENT_PRINCIPAL_IN".equals(kind)
            || "LOAN_REPAYMENT_PRINCIPAL_OUT".equals(kind);
    }

    public List<MonthlyCategoryTotal> listMonthlyTotalsByRootCategory(
        String userUid,
        String accountId,
        int year,
        String kind
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        int y = year <= 0 ? java.time.LocalDate.now().getYear() : year;
        List<String> kinds = expandedReportKinds(kind);
        boolean hasKinds = !kinds.isEmpty();

        StringBuilder sql = new StringBuilder(
            "WITH RECURSIVE cat_root(id, root_id, root_name) AS (" +
            "  SELECT id, id, name FROM categories WHERE user_uid = ? AND parent_id IS NULL" +
            "  UNION ALL " +
            "  SELECT c.id, cr.root_id, cr.root_name FROM categories c " +
            "  INNER JOIN cat_root cr ON c.parent_id = cr.id " +
            "  WHERE c.user_uid = ?" +
            ") " +
            "SELECT cr.root_id AS root_category_id, cr.root_name AS root_category_name, " +
            "       CAST(strftime('%m', t.occurred_at_epoch_sec, 'unixepoch', 'localtime') AS INTEGER) AS month, " +
            "       COALESCE(SUM(t.amount_cents), 0) AS total_amount_cents " +
            "FROM transactions t " +
            "INNER JOIN cat_root cr ON cr.id = t.category_id " +
            "WHERE t.user_uid = ? " +
            "  AND CAST(strftime('%Y', t.occurred_at_epoch_sec, 'unixepoch', 'localtime') AS INTEGER) = ?"
        );

        List<Object> args = new ArrayList<>();
        args.add(userUid);
        args.add(userUid);
        args.add(userUid);
        args.add(y);

        if (accountId != null && !accountId.isBlank()) {
            sql.append(" AND t.account_id = ?");
            args.add(accountId);
        }
        if (hasKinds) {
            sql.append(" AND t.kind IN (");
            for (int i = 0; i < kinds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
            args.addAll(kinds);
        }

        sql.append(" GROUP BY cr.root_id, cr.root_name, month ORDER BY cr.root_name ASC, month ASC");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object v = args.get(i);
                int idx = i + 1;
                if (v == null) {
                    ps.setObject(idx, null);
                } else if (v instanceof Integer in) {
                    ps.setInt(idx, in);
                } else {
                    ps.setString(idx, v.toString());
                }
            }

            List<MonthlyCategoryTotal> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MonthlyCategoryTotal(
                        rs.getString("root_category_id"),
                        rs.getString("root_category_name"),
                        rs.getInt("month"),
                        rs.getLong("total_amount_cents")
                    ));
                }
            }
            return out;
        }
    }

    public List<MonthlyCategoryDetailTotal> listMonthlyTotalsBySubcategory(
        String userUid,
        String accountId,
        int year,
        String kind
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        int y = year <= 0 ? java.time.LocalDate.now().getYear() : year;
        List<String> kinds = expandedReportKinds(kind);
        boolean hasKinds = !kinds.isEmpty();

        StringBuilder sql = new StringBuilder(
            "WITH RECURSIVE cat_root(id, root_id, root_name) AS (" +
            "  SELECT id, id, name FROM categories WHERE user_uid = ? AND parent_id IS NULL" +
            "  UNION ALL " +
            "  SELECT c.id, cr.root_id, cr.root_name FROM categories c " +
            "  INNER JOIN cat_root cr ON c.parent_id = cr.id " +
            "  WHERE c.user_uid = ?" +
            ") " +
            "SELECT cr.root_id AS root_category_id, cr.root_name AS root_category_name, " +
            "       CASE WHEN c.parent_id IS NULL THEN (c.id || ':NONE') ELSE c.id END AS category_id, " +
            "       CASE WHEN c.parent_id IS NULL THEN '(Sin subcategoría)' ELSE c.name END AS category_name, " +
            "       CAST(strftime('%m', t.occurred_at_epoch_sec, 'unixepoch', 'localtime') AS INTEGER) AS month, " +
            "       COALESCE(SUM(t.amount_cents), 0) AS total_amount_cents " +
            "FROM transactions t " +
            "INNER JOIN categories c ON c.id = t.category_id " +
            "INNER JOIN cat_root cr ON cr.id = t.category_id " +
            "WHERE t.user_uid = ? " +
            "  AND c.user_uid = ? " +
            "  AND CAST(strftime('%Y', t.occurred_at_epoch_sec, 'unixepoch', 'localtime') AS INTEGER) = ?"
        );

        List<Object> args = new ArrayList<>();
        args.add(userUid);
        args.add(userUid);
        args.add(userUid);
        args.add(userUid);
        args.add(y);

        if (accountId != null && !accountId.isBlank()) {
            sql.append(" AND t.account_id = ?");
            args.add(accountId);
        }
        if (hasKinds) {
            sql.append(" AND t.kind IN (");
            for (int i = 0; i < kinds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
            args.addAll(kinds);
        }

        sql.append(" GROUP BY cr.root_id, cr.root_name, category_id, category_name, month " +
                   "ORDER BY cr.root_name ASC, category_name ASC, month ASC");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object v = args.get(i);
                int idx = i + 1;
                if (v == null) {
                    ps.setObject(idx, null);
                } else if (v instanceof Integer in) {
                    ps.setInt(idx, in);
                } else {
                    ps.setString(idx, v.toString());
                }
            }

            List<MonthlyCategoryDetailTotal> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MonthlyCategoryDetailTotal(
                        rs.getString("root_category_id"),
                        rs.getString("root_category_name"),
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        rs.getInt("month"),
                        rs.getLong("total_amount_cents")
                    ));
                }
            }
            return out;
        }
    }

    public List<MonthlyCategoryDetailAccountTotal> listMonthlyTotalsBySubcategoryAndAccount(
        String userUid,
        int year,
        String kind
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        int y = year <= 0 ? java.time.LocalDate.now().getYear() : year;
        List<String> kinds = expandedReportKinds(kind);
        boolean hasKinds = !kinds.isEmpty();

        StringBuilder sql = new StringBuilder(
            "WITH RECURSIVE cat_root(id, root_id, root_name) AS (" +
            "  SELECT id, id, name FROM categories WHERE user_uid = ? AND parent_id IS NULL" +
            "  UNION ALL " +
            "  SELECT c.id, cr.root_id, cr.root_name FROM categories c " +
            "  INNER JOIN cat_root cr ON c.parent_id = cr.id " +
            "  WHERE c.user_uid = ?" +
            ") " +
            "SELECT cr.root_id AS root_category_id, cr.root_name AS root_category_name, " +
            "       CASE WHEN c.parent_id IS NULL THEN (c.id || ':NONE') ELSE c.id END AS category_id, " +
            "       CASE WHEN c.parent_id IS NULL THEN '(Sin subcategoría)' ELSE c.name END AS category_name, " +
            "       t.account_id AS account_id, a.name AS account_name, " +
            "       CAST(strftime('%m', t.occurred_at_epoch_sec, 'unixepoch', 'localtime') AS INTEGER) AS month, " +
            "       COALESCE(SUM(t.amount_cents), 0) AS total_amount_cents " +
            "FROM transactions t " +
            "INNER JOIN accounts a ON a.id = t.account_id AND a.user_uid = t.user_uid " +
            "INNER JOIN categories c ON c.id = t.category_id " +
            "INNER JOIN cat_root cr ON cr.id = t.category_id " +
            "WHERE t.user_uid = ? " +
            "  AND c.user_uid = ? " +
            "  AND CAST(strftime('%Y', t.occurred_at_epoch_sec, 'unixepoch', 'localtime') AS INTEGER) = ?"
        );

        List<Object> args = new ArrayList<>();
        args.add(userUid);
        args.add(userUid);
        args.add(userUid);
        args.add(userUid);
        args.add(y);

        if (hasKinds) {
            sql.append(" AND t.kind IN (");
            for (int i = 0; i < kinds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
            args.addAll(kinds);
        }

        sql.append(
            " GROUP BY cr.root_id, cr.root_name, category_id, category_name, t.account_id, a.name, month " +
            "ORDER BY cr.root_name ASC, category_name ASC, a.name ASC, month ASC"
        );

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object v = args.get(i);
                int idx = i + 1;
                if (v == null) {
                    ps.setObject(idx, null);
                } else if (v instanceof Integer in) {
                    ps.setInt(idx, in);
                } else {
                    ps.setString(idx, v.toString());
                }
            }

            List<MonthlyCategoryDetailAccountTotal> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MonthlyCategoryDetailAccountTotal(
                        rs.getString("root_category_id"),
                        rs.getString("root_category_name"),
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        rs.getString("account_id"),
                        rs.getString("account_name"),
                        rs.getInt("month"),
                        rs.getLong("total_amount_cents")
                    ));
                }
            }
            return out;
        }
    }
}
