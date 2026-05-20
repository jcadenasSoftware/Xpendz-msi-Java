package com.myfinaces.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
        String kind,
        String icon,
        long createdAtEpochSec,
        long updatedAtEpochSec
    ) {
    }

    public Category createWithId(String userUid, String id, String name, String parentId) throws SQLException {
        return createWithId(userUid, id, name, parentId, null, null);
    }

    public Category createWithId(String userUid, String id, String name, String parentId, String kind) throws SQLException {
        return createWithId(userUid, id, name, parentId, kind, null);
    }

    public Category createWithId(String userUid, String id, String name, String parentId, String kind, String icon) throws SQLException {
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
            "INSERT INTO categories (id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, cid);
            ps.setString(2, userUid);
            ps.setString(3, n);
            if (parentId == null || parentId.isBlank()) {
                ps.setObject(4, null);
            } else {
                ps.setString(4, parentId);
            }
            if (kind == null || kind.isBlank()) {
                ps.setObject(5, null);
            } else {
                ps.setString(5, kind);
            }
            if (icon == null || icon.isBlank()) {
                ps.setObject(6, null);
            } else {
                ps.setString(6, icon);
            }
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        }

        String normalizedKind = (kind == null || kind.isBlank()) ? null : kind;
        return new Category(cid, userUid, n, (parentId == null || parentId.isBlank()) ? null : parentId, normalizedKind, icon, now, now);
    }

    public Category create(String userUid, String name, String parentId) throws SQLException {
        return create(userUid, name, parentId, null);
    }

    public Category create(String userUid, String name, String parentId, String kind) throws SQLException {
        return create(userUid, name, parentId, kind, null);
    }

    public Category create(String userUid, String name, String parentId, String kind, String icon) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(name, "name");

        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        System.out.println("[DB] Creando categoría - ID: " + id + ", userUid: " + userUid + ", name: " + name);

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO categories (id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, id);
            ps.setString(2, userUid);
            ps.setString(3, name);
            if (parentId == null || parentId.isBlank()) {
                ps.setObject(4, null);
            } else {
                ps.setString(4, parentId);
            }
            if (kind == null || kind.isBlank()) {
                ps.setObject(5, null);
            } else {
                ps.setString(5, kind);
            }
            if (icon == null || icon.isBlank()) {
                ps.setObject(6, null);
            } else {
                ps.setString(6, icon);
            }
            ps.setLong(7, now);
            ps.setLong(8, now);
            int rows = ps.executeUpdate();
            System.out.println("[DB] Filas insertadas: " + rows);
            
            // Forzar commit para asegurar persistencia inmediata
            if (!c.getAutoCommit()) {
                c.commit();
                System.out.println("[DB] Commit ejecutado");
            }
            
            // Forzar checkpoint del WAL para persistir en disco inmediatamente
            try (Statement stmt = c.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                System.out.println("[DB] WAL checkpoint ejecutado");
            }
        }
        System.out.println("[DB] Categoría creada exitosamente: " + id);

        String normalizedKind = (kind == null || kind.isBlank()) ? null : kind;
        return new Category(id, userUid, name, (parentId == null || parentId.isBlank()) ? null : parentId, normalizedKind, icon, now, now);
    }

    public List<Category> listRoots(String userUid) throws SQLException {
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec " +
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
            "SELECT id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec " +
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
                    rs.getString("kind"),
                    rs.getString("icon"),
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
            "SELECT id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec " +
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
            "SELECT id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec " +
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
                    rs.getString("kind"),
                    rs.getString("icon"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                );
            }
        }
    }

    public List<Category> listAll(String userUid) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec " +
            "FROM categories WHERE user_uid = ? ORDER BY name"
        )) {
            ps.setString(1, userUid);
            return readCategories(ps);
        }
    }

    public void update(String userUid, String categoryId, String newName, String kind) throws SQLException {
        update(userUid, categoryId, newName, kind, null);
    }

    public void update(String userUid, String categoryId, String newName, String kind, String icon) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(newName, "newName");

        String n = newName.trim();
        if (n.isBlank()) {
            throw new IllegalArgumentException("newName");
        }

        long now = Instant.now().getEpochSecond();
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE categories SET name = ?, kind = ?, icon = ?, updated_at_epoch_sec = ? WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, n);
            if (kind == null || kind.isBlank()) {
                ps.setObject(2, null);
            } else {
                ps.setString(2, kind);
            }
            if (icon == null || icon.isBlank()) {
                ps.setObject(3, null);
            } else {
                ps.setString(3, icon);
            }
            ps.setLong(4, now);
            ps.setString(5, userUid);
            ps.setString(6, categoryId);
            ps.executeUpdate();
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
                "INSERT INTO categories (id, user_uid, name, parent_id, kind, icon, created_at_epoch_sec, updated_at_epoch_sec) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                ps.setString(1, remote.id());
                ps.setString(2, userUid);
                ps.setString(3, remote.name());
                if (remote.parentId() == null || remote.parentId().isBlank()) {
                    ps.setObject(4, null);
                } else {
                    ps.setString(4, remote.parentId());
                }
                if (remote.kind() == null || remote.kind().isBlank()) {
                    ps.setObject(5, null);
                } else {
                    ps.setString(5, remote.kind());
                }
                if (remote.icon() == null || remote.icon().isBlank()) {
                    ps.setObject(6, null);
                } else {
                    ps.setString(6, remote.icon());
                }
                ps.setLong(7, remote.createdAtEpochSec());
                ps.setLong(8, remote.updatedAtEpochSec());
                ps.executeUpdate();
            }
            return;
        }

        if (remote.updatedAtEpochSec() <= existing.updatedAtEpochSec()) {
            return;
        }

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE categories SET name = ?, parent_id = ?, kind = ?, icon = ?, created_at_epoch_sec = ?, updated_at_epoch_sec = ? " +
                "WHERE user_uid = ? AND id = ?"
        )) {
            ps.setString(1, remote.name());
            if (remote.parentId() == null || remote.parentId().isBlank()) {
                ps.setObject(2, null);
            } else {
                ps.setString(2, remote.parentId());
            }
            if (remote.kind() == null || remote.kind().isBlank()) {
                ps.setObject(3, null);
            } else {
                ps.setString(3, remote.kind());
            }
            if (remote.icon() == null || remote.icon().isBlank()) {
                ps.setObject(4, null);
            } else {
                ps.setString(4, remote.icon());
            }
            ps.setLong(5, remote.createdAtEpochSec());
            ps.setLong(6, remote.updatedAtEpochSec());
            ps.setString(7, userUid);
            ps.setString(8, remote.id());
            ps.executeUpdate();
        }
    }

    public void delete(String userUid, String categoryId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");

        try (Connection c = db.openConnection()) {
            c.setAutoCommit(false);
            try {
                long now = Instant.now().getEpochSecond();
                
                // 1. Actualizar transacciones: poner category_id a NULL
                try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET category_id = NULL, updated_at_epoch_sec = ?, pending_sync = 1 " +
                    "WHERE user_uid = ? AND category_id = ?"
                )) {
                    ps.setLong(1, now);
                    ps.setString(2, userUid);
                    ps.setString(3, categoryId);
                    ps.executeUpdate();
                }
                
                // 2. Eliminar presupuestos asociados a esta categoría
                try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM budgets WHERE user_uid = ? AND category_id = ?"
                )) {
                    ps.setString(1, userUid);
                    ps.setString(2, categoryId);
                    ps.executeUpdate();
                }
                
                // 3. Eliminar subcategorías primero (por ON DELETE CASCADE en parent_id)
                try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM categories WHERE user_uid = ? AND parent_id = ?"
                )) {
                    ps.setString(1, userUid);
                    ps.setString(2, categoryId);
                    ps.executeUpdate();
                }
                
                // 4. Finalmente eliminar la categoría raíz
                try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM categories WHERE user_uid = ? AND id = ?"
                )) {
                    ps.setString(1, userUid);
                    ps.setString(2, categoryId);
                    ps.executeUpdate();
                }
                
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }
    
    public void deleteSubcategory(String userUid, String subcategoryId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(subcategoryId, "subcategoryId");

        try (Connection c = db.openConnection()) {
            c.setAutoCommit(false);
            try {
                long now = Instant.now().getEpochSecond();
                
                // 1. Actualizar transacciones: poner category_id a NULL
                try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET category_id = NULL, updated_at_epoch_sec = ?, pending_sync = 1 " +
                    "WHERE user_uid = ? AND category_id = ?"
                )) {
                    ps.setLong(1, now);
                    ps.setString(2, userUid);
                    ps.setString(3, subcategoryId);
                    ps.executeUpdate();
                }
                
                // 2. Eliminar la subcategoría (no tiene presupuestos ni sub-subcategorías)
                try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM categories WHERE user_uid = ? AND id = ?"
                )) {
                    ps.setString(1, userUid);
                    ps.setString(2, subcategoryId);
                    ps.executeUpdate();
                }
                
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public long countTransactions(String userUid, String categoryId) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(categoryId, "categoryId");

        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT COUNT(*) FROM transactions WHERE user_uid = ? AND category_id = ?"
        )) {
            ps.setString(1, userUid);
            ps.setString(2, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
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
                    rs.getString("kind"),
                    rs.getString("icon"),
                    rs.getLong("created_at_epoch_sec"),
                    rs.getLong("updated_at_epoch_sec")
                ));
            }
        }
        return out;
    }
}
