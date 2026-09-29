package com.myfinaces.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.service.ObligationService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contrato de Fase 2E: verifica que las llamadas que ObligationsView realiza
 * contra ObligationService/repositorios producen exactamente el estado que la
 * UI representa (resumen, lista, historial, banderas de protección), y que la
 * UI nunca crea Transactions fuera del flujo de settlement.
 */
class ObligationsViewFlowTest {

    private static final String UID = "obligations-view-user";
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
    private String incomeCategoryId;
    private String expenseCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("obligations-view.db"));
        AppSchema.init(database);
        new com.myfinaces.db.UserRepository(database).upsert(UID, "view@test.dev");

        accountRepository = new AccountRepository(database);
        categoryRepository = new CategoryRepository(database);
        transactionRepository = new TransactionRepository(database);
        obligationRepository = new ObligationRepository(database);
        settlementRepository = new ObligationSettlementRepository(database);
        service = new ObligationService(database, obligationRepository, settlementRepository, transactionRepository);

        accountId = accountRepository.create(UID, "Banco", "BANK", "COP", null).id();
        incomeCategoryId = categoryRepository.create(UID, "Ventas", null, "INCOME", "fas-wallet").id();
        expenseCategoryId = categoryRepository.create(UID, "Servicios", null, "EXPENSE", "fas-briefcase").id();
    }

    /** Replica exacta del agregado del hero de ObligationsView. */
    @Test
    void heroSummaryDerivesFromResolvedStates() throws Exception {
        seedIncome(accountId, 1_000_000L);

        ObligationRepository.Obligation receivable = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura", "Cliente A", "COP",
            500_000L, NOW, null, null, null, null);
        service.createObligation(
            UID, ObligationRepository.TYPE_PAYABLE, "Servicio", "Proveedor", "COP",
            300_000L, NOW, null, null, null, null);
        service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Vencida", "Cliente B", "COP",
            200_000L, NOW - 100_000L, NOW - 50_000L, null, null, null);
        ObligationRepository.Obligation cancelled = service.createObligation(
            UID, ObligationRepository.TYPE_PAYABLE, "Anulada", "Proveedor 2", "COP",
            900_000L, NOW, null, null, null, null);
        service.cancelObligation(UID, cancelled.id(), NOW + 1L);
        service.registerSettlement(UID, receivable.id(), accountId, incomeCategoryId, 200_000L, NOW + 1L, "Abono");

        long receivableCents = 0;
        long payableCents = 0;
        long overdueCents = 0;
        for (ObligationRepository.Obligation o : obligationRepository.listAllByUser(UID)) {
            ObligationService.ResolvedObligationState state = service.getResolvedState(UID, o.id(), NOW);
            if (state.status() == ObligationService.ObligationResolvedStatus.CANCELADA) continue;
            if (ObligationRepository.TYPE_RECEIVABLE.equals(o.type())) {
                receivableCents += state.pendingAmountCents();
            } else {
                payableCents += state.pendingAmountCents();
            }
            if (state.status() == ObligationService.ObligationResolvedStatus.VENCIDA) {
                overdueCents += state.pendingAmountCents();
            }
        }

        assertEquals(500_000L, receivableCents);   // 500k - 200k abonado + 200k vencida
        assertEquals(300_000L, payableCents);      // excluye la cancelada de 900k
        assertEquals(200_000L, overdueCents);
    }

    /** La lista de Transactions muestra el badge "Obligación" solo en la tx enlazada. */
    @Test
    void listFilteredMarksOnlySettlementLinkedTransactions() throws Exception {
        String normalTxId = seedIncome(accountId, 600_000L);
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura", "Cliente", "COP",
            300_000L, NOW, null, null, null, null);
        ObligationService.SettlementMutationResult registered = service.registerSettlement(
            UID, obligation.id(), accountId, incomeCategoryId, 150_000L, NOW + 1L, "Cobro");

        List<TransactionRepository.TransactionRow> rows =
            transactionRepository.listFiltered(UID, null, (List<String>) null, null, null, 50);

        TransactionRepository.TransactionRow linked = null;
        TransactionRepository.TransactionRow normal = null;
        for (TransactionRepository.TransactionRow r : rows) {
            if (r.id().equals(registered.transaction().id())) linked = r;
            if (r.id().equals(normalTxId)) normal = r;
        }
        assertNotNull(linked);
        assertNotNull(normal);
        assertTrue(linked.linkedToObligation());
        assertFalse(normal.linkedToObligation());
    }

    @Test
    void listRecentMarksSettlementLinkedTransaction() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);
        ObligationService.SettlementMutationResult registered = service.registerSettlement(
            UID, obligation.id(), accountId, incomeCategoryId, 60_000L, NOW + 1L, "Cobro");

        boolean flagged = false;
        for (TransactionRepository.TransactionRow r : transactionRepository.listRecent(UID, 50)) {
            if (r.id().equals(registered.transaction().id())) {
                flagged = r.linkedToObligation();
            }
        }
        assertTrue(flagged);
    }

    /** Edit/delete desde la UI de Transactions quedan bloqueados con el mensaje de obligación. */
    @Test
    void transactionsUiPathRejectsEditAndDeleteOfLinkedTransaction() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura", "Cliente", "COP",
            200_000L, NOW, null, null, null, null);
        ObligationService.SettlementMutationResult registered = service.registerSettlement(
            UID, obligation.id(), accountId, incomeCategoryId, 80_000L, NOW + 1L, "Cobro");
        String txId = registered.transaction().id();

        IllegalArgumentException updateEx = assertThrows(IllegalArgumentException.class, () ->
            transactionRepository.update(UID, txId, accountId, incomeCategoryId,
                TransactionKind.INCOME.name(), 90_000L, NOW + 2L, "edit"));
        assertTrue(updateEx.getMessage().contains("obligación"));

        IllegalArgumentException deleteEx = assertThrows(IllegalArgumentException.class, () ->
            transactionRepository.delete(UID, txId));
        assertTrue(deleteEx.getMessage().contains("obligación"));

        // La tx sigue intacta y enlazada.
        assertNotNull(transactionRepository.getForSyncByIdOrNull(UID, txId));
        assertNotNull(settlementRepository.getByLinkedTransactionId(UID, txId));
    }

    /** Flujo completo que el modal de settlement invoca: registrar → editar → eliminar. */
    @Test
    void settlementModalFlowRegisterEditDeleteKeepsSingleTransaction() throws Exception {
        seedIncome(accountId, 700_000L);
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_PAYABLE, "Proveedor", "Proveedor S.A.", "COP",
            400_000L, NOW, null, null, null, null);

        // Registrar (el modal nunca crea la tx: lo hace el servicio atómicamente).
        ObligationService.SettlementMutationResult registered = service.registerSettlement(
            UID, obligation.id(), accountId, expenseCategoryId, 150_000L, NOW + 1L, "Anticipo");
        assertEquals(1, transactionRepository.listAllForSync(UID).size() - 1); // 1 seed + 1 settlement
        ObligationService.ResolvedObligationState afterRegister = service.getResolvedState(UID, obligation.id(), NOW);
        assertEquals(ObligationService.ObligationResolvedStatus.PARCIAL, afterRegister.status());
        assertEquals(250_000L, afterRegister.pendingAmountCents());

        // Editar: mismo settlement, misma tx enlazada.
        ObligationService.SettlementMutationResult updated = service.updateSettlement(
            UID, registered.settlement().id(), accountId, expenseCategoryId, 200_000L, NOW + 2L, "Anticipo corregido");
        assertEquals(registered.settlement().id(), updated.settlement().id());
        assertEquals(registered.transaction().id(), updated.transaction().id());
        assertEquals(200_000L, transactionRepository.getForSyncByIdOrNull(UID, registered.transaction().id()).amountCents());
        assertEquals(1, settlementRepository.listByObligation(UID, obligation.id()).size());

        // Eliminar: borra el par coordinadamente.
        service.deleteSettlement(UID, registered.settlement().id());
        assertNull(settlementRepository.getByIdOrNull(UID, registered.settlement().id()));
        assertNull(transactionRepository.getForSyncByIdOrNull(UID, registered.transaction().id()));
        ObligationService.ResolvedObligationState afterDelete = service.getResolvedState(UID, obligation.id(), NOW);
        assertEquals(ObligationService.ObligationResolvedStatus.PENDIENTE, afterDelete.status());
        assertEquals(400_000L, afterDelete.pendingAmountCents());
    }

    /** Crear/editar/cancelar obligación nunca produce Transactions (regla de la vista). */
    @Test
    void obligationFormAndCancelNeverCreateTransactions() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura", "Cliente", "COP",
            300_000L, NOW, NOW + 10_000L, null, "REF-1", "nota");
        service.updateObligationMetadata(
            UID, obligation.id(), ObligationRepository.TYPE_RECEIVABLE, "Factura 2", "Cliente 2", "COP",
            350_000L, NOW, NOW + 20_000L, null, "REF-2", "nota 2");
        service.cancelObligation(UID, obligation.id(), NOW + 5L);

        assertEquals(0, transactionRepository.listAllForSync(UID).size());
        assertEquals(0, settlementRepository.listByObligation(UID, obligation.id()).size());
        assertEquals(ObligationService.ObligationResolvedStatus.CANCELADA,
            service.getResolvedState(UID, obligation.id(), NOW).status());
    }

    /** La validación definitiva del servicio respalda las pre-validaciones del formulario. */
    @Test
    void serviceRejectsSettlementBeyondPendingAndInsufficientBalance() throws Exception {
        ObligationRepository.Obligation receivable = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);
        assertThrows(IllegalStateException.class, () -> service.registerSettlement(
            UID, receivable.id(), accountId, incomeCategoryId, 150_000L, NOW + 1L, "exceso"));

        ObligationRepository.Obligation payable = service.createObligation(
            UID, ObligationRepository.TYPE_PAYABLE, "Proveedor", "Proveedor", "COP",
            100_000L, NOW, null, null, null, null);
        IllegalArgumentException insufficient = assertThrows(IllegalArgumentException.class, () ->
            service.registerSettlement(UID, payable.id(), accountId, expenseCategoryId, 50_000L, NOW + 1L, "pago"));
        assertTrue(insufficient.getMessage().contains("Saldo insuficiente"));

        assertEquals(0, transactionRepository.listAllForSync(UID).size());
    }

    /** El DatePicker cambia solo el día: conserva la hora del timestamp base. */
    @Test
    void occurredAtForPickedDatePreservesTimeOfDay() {
        long base = NOW + 15L * 3600 + 8L * 60 + 33L; // 15:08:33 del día base
        java.time.LocalDate baseDate = java.time.Instant.ofEpochSecond(base)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate();

        // Mismo día → timestamp exacto conservado (hora real del registro).
        assertEquals(base, ObligationsView.occurredAtForPickedDate(baseDate, base));

        // Otro día → traslada la hora del base al nuevo día.
        java.time.LocalDate nextDay = baseDate.plusDays(1);
        long expected = nextDay.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
            + (base - baseDate.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond());
        assertEquals(expected, ObligationsView.occurredAtForPickedDate(nextDay, base));
    }

    /** Cobro → solo raíces/subcategorías INCOME; pago → solo EXPENSE (como Nueva transacción). */
    @Test
    void settlementCategoryTreeFiltersByMovementKind() {
        java.util.List<CategoryRepository.Category> all = java.util.List.of(
            cat("income-root", "INGRESOS", "BOTH", null),
            cat("expense-root", "GASTOS", "BOTH", null),
            cat("income-explicit", "Ventas", "INCOME", null),
            cat("expense-explicit", "Servicios", "EXPENSE", null),
            cat("income-sub", "Ventas mayoreo", "INCOME", "income-root"),
            cat("income-sub-legacy", "Ventas varias", null, "income-root"),
            cat("expense-under-income", "Mal", "EXPENSE", "income-root"),
            cat("expense-sub", "Servicios pro", "EXPENSE", "expense-root"),
            cat("system-sub", "Interna", "INCOME", "income-root")
        );

        assertEquals(
            java.util.Set.of("income-root", "income-explicit"),
            new java.util.HashSet<>(ObligationsView.settlementCompatibleRoots(all, "INCOME")
                .stream().map(CategoryRepository.Category::id).toList()));
        assertEquals(
            java.util.Set.of("expense-root", "expense-explicit"),
            new java.util.HashSet<>(ObligationsView.settlementCompatibleRoots(all, "EXPENSE")
                .stream().map(CategoryRepository.Category::id).toList()));

        // Hojas compatibles con el kind del movimiento (o kind vacío heredado del padre).
        assertEquals(
            java.util.Set.of("income-sub", "income-sub-legacy"),
            new java.util.HashSet<>(ObligationsView
                .settlementCompatibleSubcategories(all, "income-root", "INCOME")
                .stream().map(CategoryRepository.Category::id).toList()));
        assertEquals(
            java.util.Set.of("expense-sub"),
            new java.util.HashSet<>(ObligationsView
                .settlementCompatibleSubcategories(all, "expense-root", "EXPENSE")
                .stream().map(CategoryRepository.Category::id).toList()));
        assertTrue(ObligationsView.settlementCompatibleSubcategories(all, null, "INCOME").isEmpty());
    }

    @Test
    void cancelledObligationLeavesActiveListForCancelledSection() throws Exception {
        ObligationRepository.Obligation active = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura activa", "Cliente A",
            "COP", 100_000L, NOW, null, null, null, null);
        ObligationRepository.Obligation toCancel = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Factura cancelada", "Cliente B",
            "COP", 50_000L, NOW, null, null, null, null);
        service.cancelObligation(UID, toCancel.id(), NOW + 10L);

        List<ObligationRepository.Obligation> all = obligationRepository.listAllByUser(UID);
        List<List<ObligationRepository.Obligation>> parts = ObligationsView.partitionByCancellation(
            all,
            id -> {
                try {
                    return service.getResolvedState(UID, id, NOW);
                } catch (Exception e) {
                    return null;
                }
            });

        List<ObligationRepository.Obligation> activeList = parts.get(0);
        List<ObligationRepository.Obligation> cancelled = parts.get(1);
        assertEquals(List.of(active.id()), activeList.stream().map(ObligationRepository.Obligation::id).toList());
        assertEquals(List.of(toCancel.id()), cancelled.stream().map(ObligationRepository.Obligation::id).toList());

        // Cancelar no crea ni altera Transactions.
        assertTrue(transactionRepository.listAllForSync(UID).isEmpty());
    }

    @Test
    void partitionKeepsNonCancelledStatusesInActiveList() {
        ObligationRepository.Obligation o1 = obligation("o-pendiente");
        ObligationRepository.Obligation o2 = obligation("o-parcial");
        ObligationRepository.Obligation o3 = obligation("o-vencida");
        ObligationRepository.Obligation o4 = obligation("o-pagada");
        ObligationRepository.Obligation o5 = obligation("o-cancelada");
        ObligationRepository.Obligation o6 = obligation("o-sin-estado");

        java.util.Map<String, ObligationService.ResolvedObligationState> states = new java.util.HashMap<>();
        states.put("o-pendiente", state("o-pendiente", ObligationService.ObligationResolvedStatus.PENDIENTE));
        states.put("o-parcial", state("o-parcial", ObligationService.ObligationResolvedStatus.PARCIAL));
        states.put("o-vencida", state("o-vencida", ObligationService.ObligationResolvedStatus.VENCIDA));
        states.put("o-pagada", state("o-pagada", ObligationService.ObligationResolvedStatus.PAGADA));
        states.put("o-cancelada", state("o-cancelada", ObligationService.ObligationResolvedStatus.CANCELADA));

        List<List<ObligationRepository.Obligation>> parts = ObligationsView.partitionByCancellation(
            List.of(o1, o2, o3, o4, o5, o6), states::get);

        assertEquals(
            List.of("o-pendiente", "o-parcial", "o-vencida", "o-pagada", "o-sin-estado"),
            parts.get(0).stream().map(ObligationRepository.Obligation::id).toList());
        assertEquals(List.of("o-cancelada"), parts.get(1).stream().map(ObligationRepository.Obligation::id).toList());
    }

    private static ObligationRepository.Obligation obligation(String id) {
        return new ObligationRepository.Obligation(
            id, UID, ObligationRepository.TYPE_RECEIVABLE, "T", "C",
            null, null, null, "COP", 100_000L, NOW, null, null, NOW, NOW, null);
    }

    private static ObligationService.ResolvedObligationState state(
        String id, ObligationService.ObligationResolvedStatus status
    ) {
        return new ObligationService.ResolvedObligationState(
            id, status, 100_000L, 0L, 100_000L, null,
            status == ObligationService.ObligationResolvedStatus.CANCELADA ? NOW + 10L : null);
    }

    private static CategoryRepository.Category cat(String id, String name, String kind, String parentId) {
        return new CategoryRepository.Category(id, UID, name, parentId, kind, null, NOW, NOW);
    }

    private String seedIncome(String account, long amountCents) throws Exception {
        return transactionRepository.create(
            UID, account, incomeCategoryId, TransactionKind.INCOME.name(), amountCents, NOW - 50L, "Seed income");
    }
}
