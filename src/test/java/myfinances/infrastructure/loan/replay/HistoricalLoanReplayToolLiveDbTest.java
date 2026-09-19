package myfinances.infrastructure.loan.replay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;

class HistoricalLoanReplayToolLiveDbTest {
    private static final String OWNER_ID = "qfywGydrMASWGgUevInHxMdWZKJ2";

    @Test
    void reportsBlockedLoans() throws Exception {
        String sourceDb = System.getProperty("myfinances.liveDb");
        assumeTrue(sourceDb != null && !sourceDb.isBlank(), "Set myfinances.liveDb to run the live replay report");

        Path source = Path.of(sourceDb);
        Path copy = Files.createTempFile("myfinances-live-replay", ".db");
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);

        SqliteDatabase database = new SqliteDatabase(copy);
        HistoricalLoanReplayTool tool = new HistoricalLoanReplayTool(database);

        for (String loanId : new String[]{
            "399c7362-f562-480b-ad4a-c57b0e8dbafd",
            "8b4d849a-bc84-48a0-9147-8df62cc69b85",
            "b5608aa6-05b7-483c-8390-a7940859277d"
        }) {
            HistoricalLoanReplayTool.ReplayResult result = tool.replay(OWNER_ID, loanId);
            System.out.println("LOAN " + loanId + " -> " + result);
        }

        // This test is a reporting harness; it always passes because the goal is to print the actual result.
        assertFalse(false);
    }
}
