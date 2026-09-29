package com.myfinaces.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.UserRepository;
import com.myfinaces.service.ObligationService;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObligationSyncApplierTest {

    private static final String UID = "obligation-sync-user";
    private static final long NOW = 1_700_000_000L;

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private ObligationRepository obligationRepository;
    private ObligationSettlementRepository settlementRepository;
    private TransactionRepository transactionRepository;
    private ObligationService service;
    private ObligationSyncApplier applier;
    private String accountId;
    private String incomeCategoryId;
    private String obligationCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("obligation-sync.db"));
        AppSchema.init(database);
        new UserRepository(database).upsert(UID, "sync@test.dev");

        AccountRepository accountRepository = new AccountRepository(database);
        CategoryRepository categoryRepository = new CategoryRepository(database);
        transactionRepository = new TransactionRepository(database);
        obligationRepository = new ObligationRepository(database);
        settlementRepository = new ObligationSettlementRepository(database);
        service = new ObligationService(database, obligationRepository, settlementRepository, transactionRepository);
        applier = new ObligationSyncApplier(obligationRepository, settlementRepository, transactionRepository, database);

        accountId = accountRepository.create(UID, "Banco", "BANK", "COP", null).id();
        incomeCategoryId = categoryRepository.create(UID, "Ventas", null, "INCOME", "fas-wallet").id();
        obligationCategoryId = categoryRepository.create(UID, "Clientes", null, "BOTH", "fas-users").id();
    }

    // ---------- Obligaciones ----------

    @Test
    void remoteObligationAppliesLocallyWithSameIdAndSyncedFlag() throws Exception {
        ObligationRepository.Obligation remote = remoteObligation("obl-1", "Remota", NOW, "desktop-device");
        ObligationSyncApplier.ApplyResult result =
            applier.applyRemoteObligations(UID, List.of(remote), Set.of("obl-1"));

        assertEquals(1, result.applied());
        ObligationRepository.Obligation local = obligationRepository.getByIdOrNull(UID, "obl-1");
        assertNotNull(local);
        assertEquals("Remota", local.title());
        assertEquals("POR_COBRAR", local.type());
        assertTrue(obligationRepository.listPendingSyncIds(UID).isEmpty());
    }

    @Test
    void newerRemoteObligationWinsConflict() throws Exception {
        String id = obligationRepository.create(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Local viejo", "Pedro", "COP",
            100_000L, NOW, null, null, null, null);
        obligationRepository.markSynced(UID, id);
        long localUpdatedAt = obligationRepository.getByIdOrNull(UID, id).updatedAtEpochSec();

        ObligationRepository.Obligation remote =
            remoteObligation(id, "Remota nueva", localUpdatedAt + 60L, "desktop-device");
        applier.applyRemoteObligations(UID, List.of(remote), Set.of(id));

        assertEquals("Remota nueva", obligationRepository.getByIdOrNull(UID, id).title());
    }

    @Test
    void olderRemoteObligationLosesConflict() throws Exception {
        String id = obligationRepository.create(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Local nuevo", "Pedro", "COP",
            100_000L, NOW, null, null, null, null);
        obligationRepository.markSynced(UID, id);

        ObligationRepository.Obligation remote = remoteObligation(id, "Remota vieja", 1L, "desktop-device");
        applier.applyRemoteObligations(UID, List.of(remote), Set.of(id));

        assertEquals("Local nuevo", obligationRepository.getByIdOrNull(UID, id).title());
    }

    @Test
    void equalTimestampConflictResolvesByUpdatedByDeterministically() throws Exception {
        String id = "obl-tie";
        obligationRepository.upsertFromRemote(UID, remoteObligation(id, "Autor local", NOW, "aaa-device"));
        ObligationRepository.Obligation remote = remoteObligation(id, "Autor remoto", NOW, "zzz-device");

        applier.applyRemoteObligations(UID, List.of(remote), Set.of(id));

        assertEquals("Autor remoto", obligationRepository.getByIdOrNull(UID, id).title());
    }

    @Test
    void cancellationPropagatesFromRemote() throws Exception {
        String id = obligationRepository.create(
            UID, ObligationRepository.TYPE_PAYABLE, "Por cancelar", "Proveedor", "COP",
            100_000L, NOW, null, null, null, null);
        obligationRepository.markSynced(UID, id);
        long localUpdatedAt = obligationRepository.getByIdOrNull(UID, id).updatedAtEpochSec();

        ObligationRepository.Obligation remote = new ObligationRepository.Obligation(
            id, UID, ObligationRepository.TYPE_PAYABLE, "Por cancelar", "Proveedor",
            null, null, null, "COP", 100_000L, NOW, null, localUpdatedAt + 50L, NOW, localUpdatedAt + 60L, "desktop-device");
        applier.applyRemoteObligations(UID, List.of(remote), Set.of(id));

        assertEquals(localUpdatedAt + 50L, obligationRepository.getByIdOrNull(UID, id).cancelledAtEpochSec());
    }

    @Test
    void localPendingObligationAbsentRemoteIsPreserved() throws Exception {
        String id = obligationRepository.create(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Sin publicar", "Pedro", "COP",
            100_000L, NOW, null, null, null, null);
        assertTrue(obligationRepository.listPendingSyncIds(UID).contains(id));

        // Snapshot remoto autoritativo sin el id.
        applier.applyRemoteObligations(UID, List.of(), Set.of());

        assertNotNull(obligationRepository.getByIdOrNull(UID, id));
    }

    @Test
    void syncedObligationAbsentRemoteIsPruned() throws Exception {
        String id = obligationRepository.create(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Borrada en remoto", "Pedro", "COP",
            100_000L, NOW, null, null, null, null);
        obligationRepository.markSynced(UID, id);

        applier.applyRemoteObligations(UID, List.of(), Set.of());

        assertNull(obligationRepository.getByIdOrNull(UID, id));
    }

    @Test
    void obligationWithLocalSettlementsIsNotPruned() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Con abonos", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);
        service.registerSettlement(UID, obligation.id(), accountId, incomeCategoryId, 10_000L, NOW + 1L, null);
        obligationRepository.markSynced(UID, obligation.id());

        ObligationSyncApplier.ApplyResult result =
            applier.applyRemoteObligations(UID, List.of(), Set.of());

        assertNotNull(obligationRepository.getByIdOrNull(UID, obligation.id()));
        assertEquals(1, result.deferred());
    }

    @Test
    void repeatedObligationApplyStaysIdempotent() throws Exception {
        ObligationRepository.Obligation remote = remoteObligation("obl-1", "Estable", NOW, "desktop-device");
        for (int i = 0; i < 4; i++) {
            applier.applyRemoteObligations(UID, List.of(remote), Set.of("obl-1"));
        }
        assertEquals(1, obligationRepository.listAllByUser(UID).size());
        assertEquals("obl-1", obligationRepository.listAllByUser(UID).get(0).id());
    }

    // ---------- Settlements ----------

    @Test
    void remoteSettlementAppliesWhenDependenciesExist() throws Exception {
        String obligationId = seedSyncedObligation();
        String txId = transactionRepository.createWithId(
            "tx-1", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);
        transactionRepository.markSynced(UID, txId);

        ObligationSettlementRepository.ObligationSettlement remote = remoteSettlement(
            "set-1", obligationId, "tx-1", 40_000L, NOW, "android-device");
        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(
            UID, List.of(remote), Set.of("set-1"));

        assertEquals(1, result.applied());
        ObligationSettlementRepository.ObligationSettlement local =
            settlementRepository.getByIdOrNull(UID, "set-1");
        assertNotNull(local);
        assertEquals("tx-1", local.linkedTransactionId());
        assertEquals(obligationId, local.obligationId());
        assertTrue(settlementRepository.listPendingSyncIds(UID).isEmpty());
        assertEquals(1, transactionRepository.listAllForSync(UID).size());
    }

    @Test
    void settlementWithoutObligationIsDeferredNotMaterialized() throws Exception {
        transactionRepository.createWithId("tx-1", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);

        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(
            UID,
            List.of(remoteSettlement("set-orphan", "obl-missing", "tx-1", 40_000L, NOW, "android")),
            Set.of("set-orphan"));

        assertEquals(1, result.deferred());
        assertNull(settlementRepository.getByIdOrNull(UID, "set-orphan"));
    }

    @Test
    void settlementWithoutTransactionIsDeferredAndNeverCreatesOne() throws Exception {
        String obligationId = seedSyncedObligation();

        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(
            UID,
            List.of(remoteSettlement("set-no-tx", obligationId, "tx-missing", 40_000L, NOW, "android")),
            Set.of("set-no-tx"));

        assertEquals(1, result.deferred());
        assertNull(settlementRepository.getByIdOrNull(UID, "set-no-tx"));
        assertEquals(0, transactionRepository.listAllForSync(UID).size());
    }

    @Test
    void settlementLinkedToTransactionOwnedByAnotherIsRejected() throws Exception {
        String obligationId = seedSyncedObligation();
        transactionRepository.createWithId("tx-shared", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);
        settlementRepository.createWithId(
            "set-owner", UID, obligationId, accountId, 40_000L, NOW, "tx-shared", null, "local");
        settlementRepository.markSynced(UID, "set-owner");

        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(
            UID,
            List.of(
                remoteSettlement("set-owner", obligationId, "tx-shared", 40_000L, NOW, "android"),
                remoteSettlement("set-intruder", obligationId, "tx-shared", 40_000L, NOW + 99_999L, "android")),
            Set.of("set-owner", "set-intruder"));

        assertEquals(1, result.rejected());
        assertNull(settlementRepository.getByIdOrNull(UID, "set-intruder"));
        assertEquals("set-owner",
            settlementRepository.getByLinkedTransactionId(UID, "tx-shared").id());
    }

    @Test
    void remoteCannotRepointLinkedTransactionOfExistingSettlement() throws Exception {
        String obligationId = seedSyncedObligation();
        transactionRepository.createWithId("tx-orig", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);
        transactionRepository.createWithId("tx-other", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);
        settlementRepository.createWithId(
            "set-1", UID, obligationId, accountId, 40_000L, NOW, "tx-orig", null, "local");
        settlementRepository.markSynced(UID, "set-1");

        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(
            UID,
            List.of(remoteSettlement("set-1", obligationId, "tx-other", 40_000L, NOW + 99_999L, "android")),
            Set.of("set-1"));

        assertEquals(1, result.rejected());
        assertEquals("tx-orig", settlementRepository.getByIdOrNull(UID, "set-1").linkedTransactionId());
    }

    @Test
    void remoteSettlementEditUpdatesSameSettlementAndKeepsLinkedTransaction() throws Exception {
        String obligationId = seedSyncedObligation();
        transactionRepository.createWithId("tx-1", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);
        settlementRepository.createWithId(
            "set-1", UID, obligationId, accountId, 40_000L, NOW, "tx-1", null, "local");
        settlementRepository.markSynced(UID, "set-1");
        long localUpdatedAt = settlementRepository.getByIdOrNull(UID, "set-1").updatedAtEpochSec();

        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(
            UID,
            List.of(remoteSettlement("set-1", obligationId, "tx-1", 60_000L, localUpdatedAt + 60L, "android")),
            Set.of("set-1"));

        assertEquals(1, result.applied());
        ObligationSettlementRepository.ObligationSettlement local = settlementRepository.getByIdOrNull(UID, "set-1");
        assertEquals(60_000L, local.amountCents());
        assertEquals("tx-1", local.linkedTransactionId());
        assertEquals(1, transactionRepository.listAllForSync(UID).size());
    }

    @Test
    void remoteSettlementAbsenceDeletesSettlementAndLinkedTransactionCoordinately() throws Exception {
        String obligationId = seedSyncedObligation();
        transactionRepository.createWithId("tx-1", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);
        settlementRepository.createWithId(
            "set-1", UID, obligationId, accountId, 40_000L, NOW, "tx-1", null, "local");
        settlementRepository.markSynced(UID, "set-1");

        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(UID, List.of(), Set.of());

        assertEquals(1, result.pruned());
        assertNull(settlementRepository.getByIdOrNull(UID, "set-1"));
        assertNull(transactionRepository.getForSyncByIdOrNull(UID, "tx-1"));
        // El borrado remoto del par queda encolado para limpieza idempotente.
        List<ObligationPublishQueue.Entry> pending = ObligationPublishQueue.listPending(database);
        assertEquals(1, pending.size());
        assertEquals("set-1", pending.get(0).settlementId());
        assertEquals("tx-1", pending.get(0).transactionId());
    }

    @Test
    void pendingSettlementAbsentRemoteIsPreserved() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Pendiente", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);
        ObligationService.SettlementMutationResult created = service.registerSettlement(
            UID, obligation.id(), accountId, incomeCategoryId, 10_000L, NOW + 1L, null);
        assertTrue(settlementRepository.listPendingSyncIds(UID).contains(created.settlement().id()));

        ObligationSyncApplier.ApplyResult result = applier.applyRemoteSettlements(UID, List.of(), Set.of());

        assertNotNull(settlementRepository.getByIdOrNull(UID, created.settlement().id()));
        assertEquals(1, result.preserved());
        assertNotNull(transactionRepository.getForSyncByIdOrNull(UID, created.transaction().id()));
    }

    @Test
    void repeatedSettlementApplyNeverCreatesTransactions() throws Exception {
        String obligationId = seedSyncedObligation();
        transactionRepository.createWithId("tx-1", UID, accountId, incomeCategoryId, "INCOME", 40_000L, NOW, null);
        ObligationSettlementRepository.ObligationSettlement remote =
            remoteSettlement("set-1", obligationId, "tx-1", 40_000L, NOW, "android");

        for (int i = 0; i < 4; i++) {
            applier.applyRemoteSettlements(UID, List.of(remote), Set.of("set-1"));
        }

        assertEquals(1, settlementRepository.listAllByUser(UID).size());
        assertEquals(1, transactionRepository.listAllForSync(UID).size());
    }

    @Test
    void serviceSettlementDeleteEnqueuesRemotePair() throws Exception {
        ObligationRepository.Obligation obligation = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Con abono", "Cliente", "COP",
            100_000L, NOW, null, null, null, null);
        ObligationService.SettlementMutationResult created = service.registerSettlement(
            UID, obligation.id(), accountId, incomeCategoryId, 10_000L, NOW + 1L, null);

        service.deleteSettlement(UID, created.settlement().id());

        assertNull(settlementRepository.getByIdOrNull(UID, created.settlement().id()));
        assertNull(transactionRepository.getForSyncByIdOrNull(UID, created.transaction().id()));
        List<ObligationPublishQueue.Entry> pending = ObligationPublishQueue.listPending(database);
        assertEquals(1, pending.size());
        assertEquals(created.settlement().id(), pending.get(0).settlementId());
        assertEquals(created.transaction().id(), pending.get(0).transactionId());
    }

    // ---------- Fixtures ----------

    private String seedSyncedObligation() throws Exception {
        String id = obligationRepository.create(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Obligación base", "Cliente", "COP",
            100_000L, NOW, null, obligationCategoryId, null, null);
        obligationRepository.markSynced(UID, id);
        return id;
    }

    private ObligationRepository.Obligation remoteObligation(
        String id, String title, long updatedAt, String updatedBy) {
        return new ObligationRepository.Obligation(
            id, UID, ObligationRepository.TYPE_RECEIVABLE, title, "Contraparte remota",
            null, null, obligationCategoryId, "COP", 100_000L, NOW, null, null, NOW, updatedAt, updatedBy);
    }

    private ObligationSettlementRepository.ObligationSettlement remoteSettlement(
        String id, String obligationId, String linkedTransactionId, long amountCents, long updatedAt, String updatedBy) {
        return new ObligationSettlementRepository.ObligationSettlement(
            id, obligationId, UID, accountId, amountCents, NOW, linkedTransactionId, null, NOW, updatedAt, updatedBy);
    }
}
