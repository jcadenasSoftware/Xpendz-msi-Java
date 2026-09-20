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

class CanonicalLoanPublishQueueTest {

    private static final String OWNER = "owner-1";
    private static final String LOAN_ID = "loan-1";

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
    void failedPublishIsQueuedAndClearedOnSuccess() throws Exception {
        CanonicalLoanPublishQueue.markPending(database, OWNER, LOAN_ID);

        List<CanonicalLoanPublishQueue.Entry> pending = CanonicalLoanPublishQueue.listPending(database);
        assertEquals(1, pending.size());
        assertEquals(OWNER, pending.get(0).userUid());
        assertEquals(LOAN_ID, pending.get(0).loanId());

        CanonicalLoanPublishQueue.markDone(database, LOAN_ID);
        assertTrue(CanonicalLoanPublishQueue.listPending(database).isEmpty());
    }

    @Test
    void repeatedFailureDoesNotDuplicateEntry() throws Exception {
        CanonicalLoanPublishQueue.markPending(database, OWNER, LOAN_ID);
        CanonicalLoanPublishQueue.markPending(database, OWNER, LOAN_ID);

        assertEquals(1, CanonicalLoanPublishQueue.listPending(database).size());
    }

    @Test
    void recordFailureIncrementsAttemptsAndKeepsPending() throws Exception {
        CanonicalLoanPublishQueue.markPending(database, OWNER, LOAN_ID);
        long id = CanonicalLoanPublishQueue.listPending(database).get(0).id();

        CanonicalLoanPublishQueue.recordFailure(database, id, "Firestore patch failed (401)");

        List<CanonicalLoanPublishQueue.Entry> pending = CanonicalLoanPublishQueue.listPending(database);
        assertEquals(1, pending.size());
        assertEquals(id, pending.get(0).id());
    }
}
