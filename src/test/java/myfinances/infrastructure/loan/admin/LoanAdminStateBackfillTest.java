package myfinances.infrastructure.loan.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import myfinances.domain.loan.admin.LoanAdminState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanAdminStateBackfillTest {
    private static final String OWNER_ID = "owner-1";

    @TempDir
    Path temporaryDirectory;
    private SqliteDatabase database;
    private JdbcLoanAdminStateRepository repository;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabase(temporaryDirectory.resolve("backfill.db"));
        repository = new JdbcLoanAdminStateRepository(database);
    }

    @Test
    void marksPreSprint6GArchivedLoanAsPending() {
        // Simula un préstamo archivado antes del Sprint 6G: archived=1 sin pending_sync.
        repository.archive(OWNER_ID, "loan-old", "device-1");
        repository.markSynced(OWNER_ID, "loan-old");

        LoanAdminStateBackfill.run(database);

        assertEquals(List.of("loan-old"),
            repository.listPendingForSync(OWNER_ID).stream().map(LoanAdminState::loanId).toList());
    }

    @Test
    void secondRunChangesNothing() {
        repository.archive(OWNER_ID, "loan-old", "device-1");
        repository.markSynced(OWNER_ID, "loan-old");
        LoanAdminStateBackfill.run(database);
        LoanAdminState afterFirst = repository.getByLoan(OWNER_ID, "loan-old");

        LoanAdminStateBackfill.run(database);

        assertEquals(afterFirst, repository.getByLoan(OWNER_ID, "loan-old"));
        // Un préstamo archivado y ya sincronizado después de la primera ejecución
        // no debe volver a marcarse.
        repository.archive(OWNER_ID, "loan-later", "device-1");
        repository.markSynced(OWNER_ID, "loan-later");
        LoanAdminStateBackfill.run(database);
        assertEquals(List.of("loan-old"),
            repository.listPendingForSync(OWNER_ID).stream().map(LoanAdminState::loanId).toList());
    }

    @Test
    void pushPendingClearsFlagAndBackfillDoesNotReMark() {
        repository.archive(OWNER_ID, "loan-old", "device-1");
        repository.markSynced(OWNER_ID, "loan-old");
        LoanAdminStateBackfill.run(database);

        // pushPending publica y marca synced; se simula el efecto del pipeline.
        assertEquals(1, repository.listPendingForSync(OWNER_ID).size());
        repository.markSynced(OWNER_ID, "loan-old");

        assertEquals(List.of(), repository.listPendingForSync(OWNER_ID));
        LoanAdminStateBackfill.run(database);
        assertEquals(List.of(), repository.listPendingForSync(OWNER_ID));
    }

    @Test
    void versionOneFlagReRunsOnceAndUpgradesToVersionTwo() throws Exception {
        // Instalación donde el backfill v1 ya corrió mientras el publish usaba un
        // PATCH sin updateMask: archivado marcado synced sin haber llegado a
        // Firestore. La versión 2 debe re-marcarlo una única vez.
        repository.archive(OWNER_ID, "loan-old", "device-1");
        repository.markSynced(OWNER_ID, "loan-old");
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE loan_admin_state_backfill_v1 (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1), ran_at_epoch_sec INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO loan_admin_state_backfill_v1 (id, ran_at_epoch_sec) VALUES (1, 1)");
        }

        LoanAdminStateBackfill.run(database);
        assertEquals(List.of("loan-old"),
            repository.listPendingForSync(OWNER_ID).stream().map(LoanAdminState::loanId).toList());

        repository.markSynced(OWNER_ID, "loan-old");
        LoanAdminStateBackfill.run(database);
        assertEquals(List.of(), repository.listPendingForSync(OWNER_ID));
    }

    @Test
    void newlyArchivedLoanIsNotModified() {
        LoanAdminState fresh = repository.archive(OWNER_ID, "loan-new", "device-1");

        LoanAdminStateBackfill.run(database);

        assertEquals(fresh, repository.getByLoan(OWNER_ID, "loan-new"));
        assertTrue(repository.getByLoan(OWNER_ID, "loan-new").archived());
    }
}
