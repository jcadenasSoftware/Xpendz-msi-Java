package myfinances.infrastructure.loan.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import myfinances.application.loan.LoanApplicationService;
import myfinances.infrastructure.loan.di.LoanApplicationServiceFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class HistoricalLoanPaymentReconcilerLiveDbTest {
    @Test
    void reconcilesAuditedDesktopDatabaseCopy() throws Exception {
        String sourceDb = System.getProperty("myfinances.liveDb");
        Assumptions.assumeTrue(sourceDb != null && !sourceDb.isBlank(), "Set myfinances.liveDb to run the live reconciliation copy test");

        Path source = Path.of(sourceDb);
        Path copy = Files.createTempFile("myfinances-live-reconcile", ".db");
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);

        SqliteDatabase database = new SqliteDatabase(copy);
        LoanApplicationServiceFactory factory = new LoanApplicationServiceFactory(database);
        LoanApplicationService service = factory.loanApplicationService();
        HistoricalLoanPaymentReconciler reconciler = new HistoricalLoanPaymentReconciler();

        HistoricalLoanPaymentReconciler.ReconciliationReport report = reconciler.reconcile(database, service);
        assertEquals(61, report.loansProcessed());
        assertEquals(93, report.paymentsProcessed());
        assertEquals(49, report.paymentsReconciled());
        assertEquals(41, report.paymentsAlreadyExisting());
        assertEquals(3, report.paymentsOmitted());
        assertEquals(3, report.errors().size());

        assertEquals(93, count(database, "SELECT COUNT(*) FROM loan_journal_v1 WHERE event_type = 'PAYMENT'"));
        assertEquals(93, count(database, "SELECT COUNT(*) FROM loan_payment_projection_v1"));
        assertEquals(72, count(database, "SELECT COUNT(*) FROM loan_snapshots_v1"));
        assertEquals(72, count(database, "SELECT COUNT(*) FROM loan_summary_projection_v1"));
    }

    private long count(SqliteDatabase database, String sql) throws Exception {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(sql); var result = statement.executeQuery()) {
            if (!result.next()) {
                return 0L;
            }
            return result.getLong(1);
        }
    }
}
