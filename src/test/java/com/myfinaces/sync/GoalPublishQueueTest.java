package com.myfinaces.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AppSchema;
import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoalPublishQueueTest {

    private static final String OWNER = "owner-1";
    private static final String GOAL_ID = "goal-1";

    @TempDir
    Path temporaryDirectory;
    private SqliteDatabase database;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("outbox.db"));
        AppSchema.init(database);
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec)"
                + " VALUES (?,?,0,0)")) {
            ps.setString(1, OWNER);
            ps.setString(2, "o@test");
            ps.executeUpdate();
        }
    }

    @Test
    void pendingPublishIsQueuedAndClearedOnSuccess() throws Exception {
        GoalPublishQueue.markPendingPublish(database, OWNER, GOAL_ID);

        List<GoalPublishQueue.Entry> pending = GoalPublishQueue.listPending(database);
        assertEquals(1, pending.size());
        assertEquals(OWNER, pending.get(0).userUid());
        assertEquals(GOAL_ID, pending.get(0).goalId());
        assertEquals(GoalPublishQueue.OP_PUBLISH, pending.get(0).operation());
        assertTrue(GoalPublishQueue.pendingIds(database, GoalPublishQueue.OP_PUBLISH).contains(GOAL_ID));

        GoalPublishQueue.markDone(database, GOAL_ID);
        assertTrue(GoalPublishQueue.listPending(database).isEmpty());
    }

    @Test
    void pendingDeleteReplacesPendingPublish() throws Exception {
        GoalPublishQueue.markPendingPublish(database, OWNER, GOAL_ID);
        GoalPublishQueue.markPendingDelete(database, OWNER, GOAL_ID);

        List<GoalPublishQueue.Entry> pending = GoalPublishQueue.listPending(database);
        assertEquals(1, pending.size());
        assertEquals(GoalPublishQueue.OP_DELETE, pending.get(0).operation());
        assertTrue(GoalPublishQueue.pendingIds(database, GoalPublishQueue.OP_DELETE).contains(GOAL_ID));
        assertTrue(GoalPublishQueue.pendingIds(database, GoalPublishQueue.OP_PUBLISH).isEmpty());
    }

    @Test
    void recordFailureIncrementsAttemptsAndKeepsPending() throws Exception {
        GoalPublishQueue.markPendingPublish(database, OWNER, GOAL_ID);
        long id = GoalPublishQueue.listPending(database).get(0).id();

        GoalPublishQueue.recordFailure(database, id, "Firestore patch failed (401)");

        List<GoalPublishQueue.Entry> pending = GoalPublishQueue.listPending(database);
        assertEquals(1, pending.size());
        assertEquals(id, pending.get(0).id());
    }
}
