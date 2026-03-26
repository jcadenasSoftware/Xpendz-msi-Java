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

public final class CategoryRepository {

    private final SqliteDatabase db;

    public CategoryRepository(SqliteDatabase db) {
        this.db = db;
    }

    public record Category(
        String id,
        String userUid,
        String name,
        String parentId,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {
    }

    public Category createWithId(String userUid, String id, String name, String parentId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");

        String cid = id.trim();
        if (cid.isBlank()) {
            throw new IllegalArgumentException("id");
        }
        String n = name.trim();
        if (n.isBlank()) {
            throw new IllegalArgumentException("name");
        }

        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO categories (id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, cid);
            ps.setString(2, userUid);
            ps.setString(3, n);
            if (parentId == null || parentId.isBlank()) {
                ps.setObject(4, null);
            } else {
                ps.setString(4, parentId);
            }
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }

        return new Category(cid, userUid, n, (parentId == null || parentId.isBlank()) ? null : parentId, now, now);
    }

    public Category create(String userUid, String name, String parentId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(name, "name");

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO categories (id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, name);
            if (parentId == null || parentId.isBlank()) {
                ps.setObject(4, null);
            } else {
                ps.setString(4, parentId);
            }
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }

        return new Category(id, userUid, name, (parentId == null || parentId.isBlank()) ? null : parentId, now, now);
    }

    public List<Category> listRoots(String userUid) throws SQLException {
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM categories WHERE user_uid = ? AND parent_id IS NULL ORDER BY name"
        )) {
            ps.setString(1, userUid);
            return readCategories(ps);
        }
    }

    public Category getRootByNameOrNull(String userUid, String name) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(name, "name");

        String n = name.trim();
        if (n.isBlank()) {
            return null;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM categories WHERE user_uid = ? AND parent_id IS NULL AND name = ? LIMIT 1"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, n);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Category(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("name"),
                    rs.getString("parent_id"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public Category ensureRootCategory(String userUid, String name) throws SQLException {
        Category c = getRootByNameOrNull(userUid, name);
        if (c != null) {
            return c;
        }
        return create(userUid, name, null);
    }

    public List<Category> listChildren(String userUid, String parentId) throws SQLException {
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM categories WHERE user_uid = ? AND parent_id = ? ORDER BY name"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, parentId);
            return readCategories(ps);
        }
    }

    public Category getById(String userUid, String categoryId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec " +
                "FROM categories WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Category(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("name"),
                    rs.getString("parent_id"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public List<Category> listAll(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM categories WHERE user_uid = ? ORDER BY name"
        )) {
            ps.setString(1, userUid);
            return readCategories(ps);
        }
    }

    public void rename(String userUid, String categoryId, String newName) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(newName, "newName");

        String n = newName.trim();
        if (n.isBlank()) {
            throw new IllegalArgumentException("newName");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE categories SET name = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, n);
            ps.setLong(2, now);
            ps.setString(3, userUid);
            ps.setString(4, categoryId);
            ps.executeUpdate();
        }
    }

    public void upsertFromRemote(String userUid, Category remote) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(remote, "remote");

        Category existing = getById(userUid, remote.id());
        if (existing == null) {
            try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO categories (id, user_uid, name, parent_id, created_at_epoch_sec, updated_at_epoch_sec) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, userUid);
                ps.setString(3, remote.name());
                if (remote.parentId() == null || remote.parentId().isBlank()) {
                    ps.setObject(4, null);
                } else {
                    ps.setString(4, remote.parentId());
                }
                ps.setLong(5, remote.createdAtEpochSec());
                ps.setLong(6, remote.updatedAtEpochSec());
                ps.executeUpdate();
            }
            return;
        }

        if (remote.updatedAtEpochSec() <= existing.updatedAtEpochSec()) {
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE categories SET name = ?, parent_id = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ? " +
                "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.name());
            if (remote.parentId() == null || remote.parentId().isBlank()) {
                ps.setObject(2, null);
            } else {
                ps.setString(2, remote.parentId());
            }
            ps.setLong(3, remote.createdAtEpochSec());
            ps.setLong(4, remote.updatedAtEpochSec());
            ps.setString(5, userUid);
            ps.setString(6, remote.id());
            ps.executeUpdate();
        }
    }

    public void delete(String userUid, String categoryId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM categories WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, categoryId);
            ps.executeUpdate();
        }
    }

    public long sumAmountCentsForCategoryTree(String userUid, String rootCategoryId, String kind) throws SQLException {
        String sql =
            "WITH RECURSIVE subtree(id) AS (" +
            "  SELECT id FROM categories WHERE user_uid = ? AND id = ?" +
            "  UNION ALL " +
            "  SELECT c.id FROM categories c INNER JOIN subtree s ON c.parent_id = s.id WHERE c.user_uid = ?" +
            ") " +
            "SELECT COALESCE(SUM(t.amount_cents), 0) AS total " +
            "FROM transactions t " +
            "WHERE t.user_uid = ? AND t.category_id IN (SELECT id FROM subtree)";

        boolean hasKind = kind != null && !kind.isBlank();
        if (hasKind) {
            sql += " AND t.kind = ?";
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userUid);
            ps.setString(2, rootCategoryId);
            ps.setString(3, userUid);
            ps.setString(4, userUid);
            if (hasKind) {
                ps.setString(5, kind);
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong("total");
            }
        }
    }

    private static List<Category> readCategories(PreparedStatement ps) throws SQLException {
        List<Category> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Category(
                    rs.getString("id"),
                    rs.getString("user_uid"),
                    rs.getString("name"),
                    rs.getString("parent_id"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                ));
            }
        }
        return out;
    }
}
