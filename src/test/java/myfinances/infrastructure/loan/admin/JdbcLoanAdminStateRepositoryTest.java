package myfinances.infrastructure.loan.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import java.util.List;
import myfinances.domain.loan.admin.LoanAdminState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcLoanAdminStateRepositoryTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";

    @TempDir
    Path temporaryDirectory;
    private JdbcLoanAdminStateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcLoanAdminStateRepository(
            new SqliteDatabase(temporaryDirectory.resolve("admin-state.db"))
        );
    }

    @Test
    void archiveMarksStatePendingForSyncUntilMarkedSynced() {
        LoanAdminState archived = repository.archive(OWNER_ID, LOAN_ID, "device-1");

        assertTrue(archived.archived());
        assertEquals(List.of(LOAN_ID),
            repository.listPendingForSync(OWNER_ID).stream().map(LoanAdminState::loanId).toList());

        repository.markSynced(OWNER_ID, LOAN_ID);

        assertEquals(List.of(), repository.listPendingForSync(OWNER_ID));
        assertTrue(repository.getByLoan(OWNER_ID, LOAN_ID).archived());
    }

    @Test
    void staleRemoteUpsertPreservesPendingLocalArchive() {
        LoanAdminState archived = repository.archive(OWNER_ID, LOAN_ID, "device-1");

        LoanAdminState result = repository.upsertFromRemote(
            OWNER_ID, LOAN_ID, false, null, archived.updatedAtEpochSec() - 10, "device-2"
        );

        assertTrue(result.archived());
        assertEquals(List.of(LOAN_ID),
            repository.listPendingForSync(OWNER_ID).stream().map(LoanAdminState::loanId).toList());
    }

    @Test
    void newerRemoteUpsertWinsAndClearsPending() {
        LoanAdminState archived = repository.archive(OWNER_ID, LOAN_ID, "device-1");

        repository.upsertFromRemote(
            OWNER_ID, LOAN_ID, false, null, archived.updatedAtEpochSec() + 10, "device-2"
        );

        LoanAdminState local = repository.getByLoan(OWNER_ID, LOAN_ID);
        assertEquals(false, local.archived());
        assertNull(local.archivedAtEpochSec());
        assertEquals(List.of(), repository.listPendingForSync(OWNER_ID));
    }
}
