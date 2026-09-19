package myfinances.infrastructure.loan.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.myfinaces.db.SqliteDatabase;
import java.nio.file.Path;
import java.util.List;
import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.commands.AddPrincipalCommand;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.commands.FieldChange;
import myfinances.domain.loan.commands.LoanCommand;
import myfinances.domain.loan.commands.LoanCommandEnvelope;
import myfinances.domain.loan.commands.LoanCommandType;
import myfinances.domain.loan.commands.RegisterPaymentCommand;
import myfinances.domain.loan.commands.UpdateMetadataCommand;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.reducer.DefaultLoanReducer;
import myfinances.domain.loan.repository.FakeLoanRepository;
import myfinances.domain.loan.service.DefaultLoanAggregateService;
import myfinances.domain.loan.service.LoanAggregateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeRepositoryCompatibilityTest {
    private static final String OWNER_ID = "owner-1";
    private static final String LOAN_ID = "loan-1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void fakeAndJdbcAdapterHaveIdenticalObservableBehavior() {
        FakeLoanRepository fakeRepository = new FakeLoanRepository();
        LoanAggregateService fakeService = new DefaultLoanAggregateService(fakeRepository, new DefaultLoanReducer());
        JdbcLoanRepositoryAdapter jdbcRepository = new JdbcLoanRepositoryAdapter(
            new SqliteDatabase(temporaryDirectory.resolve("compatibility.db"))
        );
        LoanAggregateService jdbcService = new JdbcLoanAggregateExecutor(
            jdbcRepository,
            new DefaultLoanAggregateService(jdbcRepository, new DefaultLoanReducer())
        );

        CreateLoanCommand create = new CreateLoanCommand(
            envelope(LoanCommandType.CREATE_LOAN, operation(1), null, 1_000),
            LoanType.LENT,
            100_000,
            "Ana",
            "USD",
            "A1",
            "tx-create",
            null
        );
        assertEquivalent(create, fakeService, jdbcService);

        String fingerprint = fakeRepository.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow().journalFingerprint();
        RegisterPaymentCommand payment = new RegisterPaymentCommand(
            envelope(LoanCommandType.REGISTER_PAYMENT, operation(2), fingerprint, 2_000),
            20_000,
            "A1",
            "tx-payment",
            null
        );
        assertEquivalent(payment, fakeService, jdbcService);

        fingerprint = fakeRepository.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow().journalFingerprint();
        AddPrincipalCommand topup = new AddPrincipalCommand(
            envelope(LoanCommandType.ADD_PRINCIPAL, operation(3), fingerprint, 3_000),
            10_000,
            "A1",
            "tx-topup",
            null
        );
        assertEquivalent(topup, fakeService, jdbcService);

        fingerprint = fakeRepository.loadSnapshot(OWNER_ID, LOAN_ID).orElseThrow().journalFingerprint();
        UpdateMetadataCommand metadata = new UpdateMetadataCommand(
            envelope(LoanCommandType.UPDATE_METADATA, operation(4), fingerprint, 4_000),
            new FieldChange<>(true, "Ana Pérez"),
            new FieldChange<>(false, null),
            new FieldChange<>(true, "Actualizado")
        );
        assertEquivalent(metadata, fakeService, jdbcService);

        assertEquals(fakeRepository.getJournal(OWNER_ID, LOAN_ID), jdbcRepository.getJournal(OWNER_ID, LOAN_ID));
        assertEquals(
            fakeRepository.loadSnapshot(OWNER_ID, LOAN_ID),
            jdbcRepository.loadSnapshot(OWNER_ID, LOAN_ID)
        );
    }

    private static void assertEquivalent(
        LoanCommand command,
        LoanAggregateService fakeService,
        LoanAggregateService jdbcService
    ) {
        LoanCommandResult expected = fakeService.process(command);
        LoanCommandResult actual = jdbcService.process(command);
        assertEquals(expected, actual);
    }

    private static LoanCommandEnvelope envelope(
        LoanCommandType type,
        String operationId,
        String fingerprint,
        long occurredAt
    ) {
        return new LoanCommandEnvelope(
            type,
            operationId,
            LOAN_ID,
            OWNER_ID,
            fingerprint,
            occurredAt,
            "actor-1",
            "device-1"
        );
    }

    private static String operation(int value) {
        return "00000000-0000-4000-8000-%012d".formatted(value);
    }
}
