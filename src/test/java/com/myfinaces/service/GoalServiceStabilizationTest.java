package com.myfinaces.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.sync.GoalPublishQueue;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contrato funcional v1.1 del módulo Metas:
 * - operaciones solo en OPEN;
 * - CLOSED = archivada (solo lectura, reapertura permitida);
 * - eliminación: física sin historial, archivo con historial;
 * - cada mutación local queda marcada en el outbox para el sincronizador.
 */
class GoalServiceStabilizationTest {

    private static final String UID = "user-1";

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase db;
    private GoalRepository goalRepo;
    private AccountRepository accountRepo;
    private TransferRepository transferRepo;
    private GoalService service;

    @BeforeEach
    void setUp() throws Exception {
        db = new SqliteDatabase(temporaryDirectory.resolve("goals.db"));
        AppSchema.init(db);
        try (Connection c = db.openConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO users (uid, email, created_at_epoch_sec, updated_at_epoch_sec)"
                + " VALUES (?,?,0,0)")) {
            ps.setString(1, UID);
            ps.setString(2, "u@test");
            ps.executeUpdate();
        }
        goalRepo = new GoalRepository(db);
        accountRepo = new AccountRepository(db);
        transferRepo = new TransferRepository(db);
        service = new GoalService(goalRepo, accountRepo, transferRepo, db);
    }

    @Test
    void createdGoalIsOpenAndMarkedPendingPublish() throws Exception {
        GoalRepository.Goal goal = service.crearMeta(UID, "Viaje", "COP", 1_000_000L, 0L);

        assertEquals(GoalRepository.STATUS_OPEN, goal.status());
        assertTrue(GoalPublishQueue.pendingIds(db, GoalPublishQueue.OP_PUBLISH).contains(goal.id()));
        assertEquals(1, service.obtenerMetas(UID).size());
        assertTrue(service.obtenerMetasArchivadas(UID).isEmpty());
    }

    @Test
    void closedGoalRejectsDepositWithdrawAndEdit() throws Exception {
        GoalRepository.Goal goal = service.crearMeta(UID, "Carro", "COP", 2_000_000L, 0L);
        goalRepo.archive(UID, goal.id());

        assertThrows(IllegalStateException.class,
            () -> service.depositar(UID, goal.id(), "acc-other", 100_000L, null));
        assertThrows(IllegalStateException.class,
            () -> service.retirar(UID, goal.id(), "acc-other", 100_000L, null));
        assertThrows(IllegalStateException.class,
            () -> service.actualizarMeta(UID, goal.id(), "Carro2", "COP", 3_000_000L, 0L));
    }

    @Test
    void openGoalAcceptsDepositAndWithdraw() throws Exception {
        GoalRepository.Goal goal = service.crearMeta(UID, "Prueba", "COP", 500_000L, 0L);
        AccountRepository.Account other = accountRepo.create(UID, "Bolsillo", "CASH", "COP");

        service.depositar(UID, goal.id(), other.id(), 100_000L, null);
        assertEquals(100_000L, service.calcularSaldo(UID, goal.accountId()));

        service.retirar(UID, goal.id(), other.id(), 40_000L, null);
        assertEquals(60_000L, service.calcularSaldo(UID, goal.accountId()));
    }

    @Test
    void reopenRestoresOpenPreservingAccountAndBalance() throws Exception {
        GoalRepository.Goal goal = service.crearMeta(UID, "Laptop", "COP", 3_000_000L, 0L);
        AccountRepository.Account other = accountRepo.create(UID, "Banco", "CHECKING", "COP");
        service.depositar(UID, goal.id(), other.id(), 250_000L, null);

        goalRepo.archive(UID, goal.id());
        GoalRepository.Goal reopened = service.reabrirMeta(UID, goal.id());

        assertEquals(GoalRepository.STATUS_OPEN, reopened.status());
        assertEquals(goal.accountId(), reopened.accountId());
        assertEquals(250_000L, service.calcularSaldo(UID, reopened.accountId()));
        assertTrue(service.obtenerMetasArchivadas(UID).isEmpty());
        assertEquals(1, service.obtenerMetas(UID).size());
        assertTrue(GoalPublishQueue.pendingIds(db, GoalPublishQueue.OP_PUBLISH).contains(goal.id()));
    }

    @Test
    void reopenRejectsNonClosedGoal() throws Exception {
        GoalRepository.Goal goal = service.crearMeta(UID, "Abierta", "COP", 100_000L, 0L);
        assertThrows(IllegalStateException.class, () -> service.reabrirMeta(UID, goal.id()));
    }

    @Test
    void deleteWithoutHistoryIsPhysicalAndMarkedPendingDelete() throws Exception {
        GoalRepository.Goal goal = service.crearMeta(UID, "Temporal", "COP", 100_000L, 0L);

        GoalService.GoalDeletionOutcome outcome = service.eliminarMeta(UID, goal.id(), true);

        assertEquals(GoalService.GoalDeletionOutcome.DELETED, outcome);
        assertNull(goalRepo.getByIdOrNull(UID, goal.id()));
        assertTrue(GoalPublishQueue.pendingIds(db, GoalPublishQueue.OP_DELETE).contains(goal.id()));
    }

    @Test
    void deleteWithHistoryArchivesAndKeepsData() throws Exception {
        GoalRepository.Goal goal = service.crearMeta(UID, "ConHistorial", "COP", 100_000L, 0L);
        AccountRepository.Account other = accountRepo.create(UID, "Banco", "CHECKING", "COP");
        service.depositar(UID, goal.id(), other.id(), 50_000L, null);
        service.retirar(UID, goal.id(), other.id(), 50_000L, null);

        GoalService.GoalDeletionOutcome outcome = service.eliminarMeta(UID, goal.id(), true);

        assertEquals(GoalService.GoalDeletionOutcome.ARCHIVED, outcome);
        GoalRepository.Goal archived = goalRepo.getByIdOrNull(UID, goal.id());
        assertEquals(GoalRepository.STATUS_CLOSED, archived.status());
        assertEquals(1, service.obtenerMetasArchivadas(UID).size());
        assertTrue(service.obtenerMetas(UID).isEmpty());
    }
}
