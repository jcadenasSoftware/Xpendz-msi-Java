package com.myfinaces.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObligationServiceTest {

    private static final String UID = "obligation-service-user";
    private static final long NOW = 1_700_000_000L;

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private TransactionRepository transactionRepository;
    private ObligationRepository obligationRepository;
    private ObligationSettlementRepository settlementRepository;
    private ObligationService service;
    private String accountId;
    private String secondAccountId;
    private String incomeCategoryId;
    private String expenseCategoryId;
    private String secondExpenseCategoryId;
    private String obligationCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("obligation-service.db"));
        AppSchema.init(database);
        new com.myfinaces.db.UserRepository(database).upsert(UID, "obligation@test.dev");

        accountRepository = new AccountRepository(database);
        categoryRepository = new CategoryRepository(database);
        transactionRepository = new TransactionRepository(database);
        obligationRepository = new ObligationRepository(database);
        settlementRepository = new ObligationSettlementRepository(database);
        service = new ObligationService(database, obligationRepository, settlementRepository, transactionRepository);

        accountId = accountRepository.create(UID, "Banco", "BANK", "COP", null).id();
        secondAccountId = accountRepository.create(UID, "Caja", "CASH", "COP", null).id();
        incomeCategoryId = categoryRepository.create(UID, "Ventas", null, "INCOME", "fas-wallet").id();
        expenseCategoryId = categoryRepository.create(UID, "Servicios", null, "EXPENSE", "fas-briefcase").id();
        secondExpenseCategoryId = categoryRepository.create(UID, "Compras", null, "EXPENSE", "fas-cart-shopping").id();
        obligationCategoryId = categoryRepository.create(UID, "Clientes", null, "BOTH", "fas-users").id();
    }

    @Test
    void createObligationStartsPendingWithFullBalanceAndNoTransaction() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Factura A",
            "Cliente A",
            "COP",
            500_000L,
            NOW,
            NOW + 5_000L,
            obligationCategoryId,
            "FAC-100",
            "Crédito"
        );

        ObligationService.ResolvedObligationState state = service.getResolvedState(UID, obligation.id(), NOW);

        assertEquals(0, transactionRepository.listAllForSync(UID).size());
        assertEquals(ObligationService.ObligationResolvedStatus.PENDIENTE, state.status());
        assertEquals(500_000L, state.pendingAmountCents());
        assertEquals(0L, state.totalSettledCents());
    }

    @Test
    void updateObligationMetadataDoesNotCreateOrModifyTransaction() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Factura B",
            "Cliente B",
            "COP",
            400_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        ObligationService.SettlementMutationResult registered = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            100_000L,
            NOW + 1L,
            "Abono"
        );
        TransactionRepository.TransactionSyncRow before = transactionRepository.getForSyncByIdOrNull(UID, registered.transaction().id());

        ObligationRepository.Obligation updated = service.updateObligationMetadata(
            UID,
            obligation.id(),
            ObligationRepository.TYPE_RECEIVABLE,
            "Factura B2",
            "Cliente B2",
            "COP",
            450_000L,
            NOW,
            NOW + 9_000L,
            obligationCategoryId,
            "FAC-101",
            "Actualizada"
        );
        TransactionRepository.TransactionSyncRow after = transactionRepository.getForSyncByIdOrNull(UID, registered.transaction().id());

        assertEquals("Factura B2", updated.title());
        assertEquals(before, after);
        assertEquals(1, transactionRepository.listAllForSync(UID).size());
    }

    @Test
    void cancelObligationDoesNotCreateOrModifyTransaction() throws Exception {
        seedIncome(accountId, 300_000L);
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_PAYABLE,
            "Servicio contratado",
            "Proveedor",
            "COP",
            300_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        ObligationService.SettlementMutationResult registered = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            expenseCategoryId,
            50_000L,
            NOW + 2L,
            "Anticipo"
        );
        TransactionRepository.TransactionSyncRow before = transactionRepository.getForSyncByIdOrNull(UID, registered.transaction().id());

        ObligationRepository.Obligation cancelled = service.cancelObligation(UID, obligation.id(), NOW + 20L);
        TransactionRepository.TransactionSyncRow after = transactionRepository.getForSyncByIdOrNull(UID, registered.transaction().id());

        assertEquals(Long.valueOf(NOW + 20L), cancelled.cancelledAtEpochSec());
        assertEquals(before, after);
        assertEquals(2, transactionRepository.listAllForSync(UID).size());
    }

    @Test
    void validReceivableSettlementCreatesExactlyOneIncomeTransaction() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro 1",
            "Cliente",
            "COP",
            250_000L,
            NOW,
            null,
            null,
            null,
            null
        );

        ObligationService.SettlementMutationResult result = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            120_000L,
            NOW + 1L,
            "Ingreso"
        );

        assertEquals(1, transactionRepository.listAllForSync(UID).size());
        assertEquals(TransactionKind.INCOME.name(), result.transaction().kind());
        assertEquals(result.transaction().id(), result.settlement().linkedTransactionId());
        assertEquals(result.transaction().id(), settlementRepository.getByIdOrNull(UID, result.settlement().id()).linkedTransactionId());
    }

    @Test
    void payableSettlementCreatesExpenseTransaction() throws Exception {
        seedIncome(accountId, 500_000L);
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_PAYABLE,
            "Pago proveedor",
            "Proveedor",
            "COP",
            200_000L,
            NOW,
            null,
            null,
            null,
            null
        );

        ObligationService.SettlementMutationResult result = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            expenseCategoryId,
            80_000L,
            NOW + 1L,
            "Pago parcial"
        );

        assertEquals(TransactionKind.EXPENSE.name(), result.transaction().kind());
        assertEquals(2, transactionRepository.listAllForSync(UID).size());
    }

    @Test
    void secondSettlementCreatesIndependentTransactionAndNeverReusesLinkedTransactionId() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro 2",
            "Cliente",
            "COP",
            300_000L,
            NOW,
            null,
            null,
            null,
            null
        );

        ObligationService.SettlementMutationResult first = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            100_000L,
            NOW + 1L,
            null
        );
        ObligationService.SettlementMutationResult second = service.registerSettlement(
            UID,
            obligation.id(),
            secondAccountId,
            incomeCategoryId,
            50_000L,
            NOW + 2L,
            null
        );

        assertEquals(2, settlementRepository.listByObligation(UID, obligation.id()).size());
        assertNotEquals(first.transaction().id(), second.transaction().id());
        assertNotEquals(first.settlement().linkedTransactionId(), second.settlement().linkedTransactionId());
    }

    @Test
    void registerSettlementPreservesExactOccurredAtTimestampOnSettlementAndTransaction() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro timestamp",
            "Cliente",
            "COP",
            200_000L,
            NOW,
            null,
            null,
            null,
            null
        );

        // Timestamp con hora real (no medianoche): +7h13m42s del mismo día base.
        long realTs = NOW + 7L * 3600 + 13L * 60 + 42L;
        ObligationService.SettlementMutationResult result = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            100_000L,
            realTs,
            null
        );

        assertEquals(realTs, result.settlement().occurredAtEpochSec());
        assertEquals(realTs, result.transaction().occurredAtEpochSec());
    }

    @Test
    void updateSettlementPreservesExactOccurredAtTimestampOnSettlementAndTransaction() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro timestamp edit",
            "Cliente",
            "COP",
            200_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        long realTs = NOW + 16L * 3600 + 45L * 60 + 30L;
        ObligationService.SettlementMutationResult created = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            100_000L,
            realTs,
            null
        );

        long editedTs = realTs + 86_400L; // mismo instante horario, día siguiente
        ObligationService.SettlementMutationResult updated = service.updateSettlement(
            UID,
            created.settlement().id(),
            accountId,
            incomeCategoryId,
            120_000L,
            editedTs,
            null
        );

        assertEquals(created.settlement().id(), updated.settlement().id());
        assertEquals(created.transaction().id(), updated.transaction().id());
        assertEquals(editedTs, updated.settlement().occurredAtEpochSec());
        assertEquals(editedTs, updated.transaction().occurredAtEpochSec());
    }

    @Test
    void overpaySettlementIsRejected() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro 3",
            "Cliente",
            "COP",
            100_000L,
            NOW,
            null,
            null,
            null,
            null
        );

        assertThrows(IllegalStateException.class, () -> service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            100_001L,
            NOW + 1L,
            null
        ));
    }

    @Test
    void zeroAndNegativeSettlementAmountsAreRejected() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro 4",
            "Cliente",
            "COP",
            100_000L,
            NOW,
            null,
            null,
            null,
            null
        );

        assertThrows(IllegalArgumentException.class, () -> service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            0L,
            NOW + 1L,
            null
        ));
        assertThrows(IllegalArgumentException.class, () -> service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            -1L,
            NOW + 1L,
            null
        ));
    }

    @Test
    void cancelledObligationRejectsNewSettlement() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro cancelado",
            "Cliente",
            "COP",
            100_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        service.cancelObligation(UID, obligation.id(), NOW + 10L);

        assertThrows(IllegalStateException.class, () -> service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            10_000L,
            NOW + 11L,
            null
        ));
    }

    @Test
    void paidObligationRejectsNewSettlement() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro pagado",
            "Cliente",
            "COP",
            100_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        service.registerSettlement(UID, obligation.id(), accountId, incomeCategoryId, 100_000L, NOW + 1L, null);

        assertThrows(IllegalStateException.class, () -> service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            1L,
            NOW + 2L,
            null
        ));
    }

    @Test
    void updateSettlementModifiesSameTransactionWithoutCreatingAnotherOne() throws Exception {
        seedIncome(accountId, 500_000L);
        seedIncome(secondAccountId, 300_000L);
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_PAYABLE,
            "Pago editable",
            "Proveedor",
            "COP",
            300_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        ObligationService.SettlementMutationResult first = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            expenseCategoryId,
            100_000L,
            NOW + 1L,
            "Pago 1"
        );

        ObligationService.SettlementMutationResult updated = service.updateSettlement(
            UID,
            first.settlement().id(),
            secondAccountId,
            secondExpenseCategoryId,
            120_000L,
            NOW + 5L,
            "Pago ajustado"
        );
        TransactionRepository.TransactionSyncRow storedTx = transactionRepository.getForSyncByIdOrNull(UID, first.transaction().id());
        ObligationService.ResolvedObligationState state = service.getResolvedState(UID, obligation.id(), NOW + 5L);

        assertEquals(first.transaction().id(), updated.transaction().id());
        assertEquals(first.settlement().id(), updated.settlement().id());
        assertEquals(secondExpenseCategoryId, storedTx.categoryId());
        assertEquals(secondAccountId, storedTx.accountId());
        assertEquals(180_000L, state.pendingAmountCents());
    }

    @Test
    void updateSettlementRollbackKeepsPreviousStateIfSomethingFails() throws Exception {
        seedIncome(accountId, 400_000L);
        seedIncome(secondAccountId, 300_000L);
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_PAYABLE,
            "Pago rollback",
            "Proveedor",
            "COP",
            250_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        ObligationService.SettlementMutationResult first = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            expenseCategoryId,
            90_000L,
            NOW + 1L,
            "Inicial"
        );
        TransactionRepository.TransactionSyncRow txBefore = transactionRepository.getForSyncByIdOrNull(UID, first.transaction().id());
        ObligationSettlementRepository.ObligationSettlement settlementBefore = settlementRepository.getByIdOrNull(UID, first.settlement().id());

        service.setAfterTransactionMutationHook(() -> {
            throw new IllegalStateException("forced_failure");
        });
        try {
            assertThrows(IllegalStateException.class, () -> service.updateSettlement(
                UID,
                first.settlement().id(),
                secondAccountId,
                secondExpenseCategoryId,
                110_000L,
                NOW + 9L,
                "No debe persistir"
            ));
        } finally {
            service.setAfterTransactionMutationHook(null);
        }

        assertEquals(txBefore, transactionRepository.getForSyncByIdOrNull(UID, first.transaction().id()));
        assertEquals(settlementBefore, settlementRepository.getByIdOrNull(UID, first.settlement().id()));
    }

    @Test
    void deleteSettlementRemovesLinkedTransactionAndKeepsOtherSettlementsUntouched() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cobro múltiple",
            "Cliente",
            "COP",
            400_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        ObligationService.SettlementMutationResult first = service.registerSettlement(
            UID,
            obligation.id(),
            accountId,
            incomeCategoryId,
            100_000L,
            NOW + 1L,
            "Abono 1"
        );
        ObligationService.SettlementMutationResult second = service.registerSettlement(
            UID,
            obligation.id(),
            secondAccountId,
            incomeCategoryId,
            50_000L,
            NOW + 2L,
            "Abono 2"
        );

        ObligationService.DeleteSettlementResult deleted = service.deleteSettlement(UID, first.settlement().id());

        assertEquals(first.transaction().id(), deleted.deletedTransactionId());
        assertNull(transactionRepository.getForSyncByIdOrNull(UID, first.transaction().id()));
        assertNull(settlementRepository.getByIdOrNull(UID, first.settlement().id()));
        assertNotNull(transactionRepository.getForSyncByIdOrNull(UID, second.transaction().id()));
        assertNotNull(settlementRepository.getByIdOrNull(UID, second.settlement().id()));
        assertEquals(350_000L, deleted.resolvedState().pendingAmountCents());
    }

    @Test
    void resolvesAllVisibleStates() throws Exception {
        ObligationRepository.Obligation pending = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Pendiente",
            "A",
            "COP",
            100_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        ObligationRepository.Obligation partial = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Parcial",
            "B",
            "COP",
            200_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        ObligationRepository.Obligation paid = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Pagada",
            "C",
            "COP",
            150_000L,
            NOW,
            NOW + 50L,
            null,
            null,
            null
        );
        ObligationRepository.Obligation overdue = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Vencida",
            "D",
            "COP",
            180_000L,
            NOW,
            NOW - 10L,
            null,
            null,
            null
        );
        ObligationRepository.Obligation cancelled = service.createObligation(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Cancelada",
            "E",
            "COP",
            220_000L,
            NOW,
            NOW + 10L,
            null,
            null,
            null
        );

        service.registerSettlement(UID, partial.id(), accountId, incomeCategoryId, 50_000L, NOW + 1L, null);
        service.registerSettlement(UID, paid.id(), accountId, incomeCategoryId, 150_000L, NOW + 1L, null);
        service.cancelObligation(UID, cancelled.id(), NOW + 5L);

        assertEquals(ObligationService.ObligationResolvedStatus.PENDIENTE, service.getResolvedState(UID, pending.id(), NOW + 1L).status());
        assertEquals(ObligationService.ObligationResolvedStatus.PARCIAL, service.getResolvedState(UID, partial.id(), NOW + 1L).status());
        assertEquals(ObligationService.ObligationResolvedStatus.PAGADA, service.getResolvedState(UID, paid.id(), NOW + 1L).status());
        assertEquals(ObligationService.ObligationResolvedStatus.VENCIDA, service.getResolvedState(UID, overdue.id(), NOW + 1L).status());
        assertEquals(ObligationService.ObligationResolvedStatus.CANCELADA, service.getResolvedState(UID, cancelled.id(), NOW + 6L).status());
    }

    // ── Filtro de categoría financiera por kind del movimiento ─────

    @Test
    void receivableSettlementRejectsExpenseCategory() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Cobro inválido", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            service.registerSettlement(UID, obligation.id(), accountId, expenseCategoryId, 10_000L, NOW + 1L, null));

        assertEquals("settlement_category_kind_mismatch", error.getMessage());
        assertEquals(0, transactionRepository.listAllForSync(UID).size());
        assertEquals(0, settlementRepository.listByObligation(UID, obligation.id()).size());
    }

    @Test
    void payableSettlementRejectsIncomeCategory() throws Exception {
        seedIncome(accountId, 200_000L);
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_PAYABLE, "Pago inválido", "Proveedor", "COP",
            100_000L, NOW, null, null, null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            service.registerSettlement(UID, obligation.id(), accountId, incomeCategoryId, 10_000L, NOW + 1L, null));

        assertEquals("settlement_category_kind_mismatch", error.getMessage());
        // Solo queda el seed de income; el abono no creó transacción ni settlement.
        assertEquals(1, transactionRepository.listAllForSync(UID).size());
        assertEquals(0, settlementRepository.listByObligation(UID, obligation.id()).size());
    }

    @Test
    void settlementAcceptsCompatibleSubcategory() throws Exception {
        String subIncomeId = categoryRepository
            .create(UID, "Ventas mayoreo", incomeCategoryId, "INCOME", null).id();
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Cobro con subcategoría", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);

        ObligationService.SettlementMutationResult result = service.registerSettlement(
            UID, obligation.id(), accountId, subIncomeId, 25_000L, NOW + 1L, null);

        assertEquals(subIncomeId, result.transaction().categoryId());
        assertEquals(TransactionKind.INCOME.name(), result.transaction().kind());
    }

    @Test
    void updateSettlementRejectsIncompatibleCategory() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Cobro", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);
        ObligationService.SettlementMutationResult registered = service.registerSettlement(
            UID, obligation.id(), accountId, incomeCategoryId, 30_000L, NOW + 1L, null);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            service.updateSettlement(UID, registered.settlement().id(), accountId, expenseCategoryId,
                30_000L, NOW + 2L, null));

        assertEquals("settlement_category_kind_mismatch", error.getMessage());
        TransactionRepository.TransactionSyncRow storedTx =
            transactionRepository.getForSyncByIdOrNull(UID, registered.transaction().id());
        assertNotNull(storedTx);
        assertEquals(incomeCategoryId, storedTx.categoryId());
    }

    private void seedIncome(String accountId, long amountCents) throws Exception {
        transactionRepository.create(
            UID,
            accountId,
            incomeCategoryId,
            TransactionKind.INCOME.name(),
            amountCents,
            NOW - 50L,
            "Seed income"
        );
    }
}
