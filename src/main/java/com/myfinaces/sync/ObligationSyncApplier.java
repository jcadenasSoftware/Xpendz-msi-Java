package com.myfinaces.sync;

import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Aplica snapshots remotos de obligaciones y settlements a la base local con
 * verificación explícita de dependencias.
 *
 * <p>Reglas duras:
 * <ul>
 *   <li>Un settlement remoto solo se materializa si su obligación y su
 *       transacción enlazada ya existen localmente. Jamás se crea una
 *       transacción para satisfacer un settlement.</li>
 *   <li>El {@code linkedTransactionId} y el {@code obligationId} de un
 *       settlement existente son referencias estables: una actualización
 *       remota que intente reasignarlos se rechaza.</li>
 *   <li>La poda solo actúa sobre snapshots autoritativos (documentIds
 *       provienen del servidor; los ids de documentos no parseables también
 *       cuentan como "presentes" para no borrar por errores de formato) y
 *       preserva todo registro con pending_sync u outbox pendiente.</li>
 *   <li>El borrado por ausencia remota elimina settlement y transacción
 *       enlazada en una sola transacción y encola el borrado remoto del par.</li>
 * </ul>
 */
public final class ObligationSyncApplier {

    public record ApplyResult(int applied, int deferred, int rejected, int pruned, int preserved) {
        public ApplyResult merge(ApplyResult other) {
            return new ApplyResult(
                applied + other.applied,
                deferred + other.deferred,
                rejected + other.rejected,
                pruned + other.pruned,
                preserved + other.preserved
            );
        }
    }

    private final ObligationRepository obligationRepo;
    private final ObligationSettlementRepository settlementRepo;
    private final TransactionRepository transactionRepo;
    private final SqliteDatabase db;

    public ObligationSyncApplier(
        ObligationRepository obligationRepo,
        ObligationSettlementRepository settlementRepo,
        TransactionRepository transactionRepo,
        SqliteDatabase db
    ) {
        this.obligationRepo = obligationRepo;
        this.settlementRepo = settlementRepo;
        this.transactionRepo = transactionRepo;
        this.db = db;
    }

    public ApplyResult applyRemoteObligations(
        String userUid,
        List<ObligationRepository.Obligation> records,
        Set<String> remoteDocumentIds
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(remoteDocumentIds, "remoteDocumentIds");

        int applied = 0;
        int deferred = 0;
        int rejected = 0;
        int pruned = 0;
        int preserved = 0;

        for (ObligationRepository.Obligation remote : records) {
            try {
                obligationRepo.upsertFromRemote(userUid, remote);
                applied++;
            } catch (Exception e) {
                // FK faltante (p. ej. categoría aún no sincronizada) u otro
                // problema puntual: se difiere sin fabricar datos.
                deferred++;
                System.out.println("[ObligationSync] obligation deferred id=" + remote.id()
                    + " error=" + e.getMessage());
            }
        }

        Set<String> pendingIds = obligationRepo.listPendingSyncIds(userUid);
        for (ObligationRepository.Obligation local : obligationRepo.listAllByUser(userUid)) {
            if (remoteDocumentIds.contains(local.id()) || pendingIds.contains(local.id())) {
                preserved++;
                continue;
            }
            if (!settlementRepo.listByObligation(userUid, local.id()).isEmpty()) {
                // No borrar en cascada: los settlements tienen su propia
                // convergencia y arrastrarían transacciones.
                deferred++;
                System.out.println("[ObligationSync] obligation " + local.id()
                    + " absent remotely but has local settlements; prune deferred");
                continue;
            }
            try {
                obligationRepo.deleteLocal(userUid, local.id());
                pruned++;
            } catch (Exception e) {
                deferred++;
                System.out.println("[ObligationSync] obligation prune failed id=" + local.id()
                    + " error=" + e.getMessage());
            }
        }
        return new ApplyResult(applied, deferred, rejected, pruned, preserved);
    }

    public ApplyResult applyRemoteSettlements(
        String userUid,
        List<ObligationSettlementRepository.ObligationSettlement> records,
        Set<String> remoteDocumentIds
    ) throws SQLException {
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(remoteDocumentIds, "remoteDocumentIds");

        int applied = 0;
        int deferred = 0;
        int rejected = 0;
        int pruned = 0;
        int preserved = 0;

        Set<String> pendingDeletes = ObligationPublishQueue.pendingSettlementIds(db);
        for (ObligationSettlementRepository.ObligationSettlement remote : records) {
            if (pendingDeletes.contains(remote.id())) {
                preserved++;
                continue;
            }
            ObligationSettlementRepository.ObligationSettlement local =
                settlementRepo.getByIdOrNull(userUid, remote.id());
            if (local != null
                && (!Objects.equals(local.obligationId(), remote.obligationId())
                    || !Objects.equals(local.linkedTransactionId(), remote.linkedTransactionId()))) {
                rejected++;
                System.out.println("[ObligationSync] settlement " + remote.id()
                    + " remote reassigns stable references; rejected");
                continue;
            }
            if (obligationRepo.getByIdOrNull(userUid, remote.obligationId()) == null) {
                deferred++;
                System.out.println("[ObligationSync] settlement " + remote.id()
                    + " references missing obligation " + remote.obligationId() + "; deferred");
                continue;
            }
            if (transactionRepo.getForSyncByIdOrNull(userUid, remote.linkedTransactionId()) == null) {
                deferred++;
                System.out.println("[ObligationSync] settlement " + remote.id()
                    + " references missing transaction " + remote.linkedTransactionId()
                    + "; deferred (never fabricated)");
                continue;
            }
            ObligationSettlementRepository.ObligationSettlement owner =
                settlementRepo.getByLinkedTransactionId(userUid, remote.linkedTransactionId());
            if (owner != null && !owner.id().equals(remote.id())) {
                rejected++;
                System.out.println("[ObligationSync] settlement " + remote.id()
                    + " linked transaction already owned by " + owner.id() + "; rejected");
                continue;
            }
            try {
                settlementRepo.upsertFromRemote(userUid, remote);
                applied++;
            } catch (Exception e) {
                deferred++;
                System.out.println("[ObligationSync] settlement apply failed id=" + remote.id()
                    + " error=" + e.getMessage());
            }
        }

        Set<String> pendingIds = settlementRepo.listPendingSyncIds(userUid);
        for (ObligationSettlementRepository.ObligationSettlement local : settlementRepo.listAllByUser(userUid)) {
            if (remoteDocumentIds.contains(local.id())
                || pendingIds.contains(local.id())
                || pendingDeletes.contains(local.id())) {
                preserved++;
                continue;
            }
            try {
                deleteSettlementAndLinkedTransaction(userUid, local);
                pruned++;
            } catch (Exception e) {
                deferred++;
                System.out.println("[ObligationSync] settlement prune failed id=" + local.id()
                    + " error=" + e.getMessage());
            }
        }
        return new ApplyResult(applied, deferred, rejected, pruned, preserved);
    }

    private void deleteSettlementAndLinkedTransaction(
        String userUid,
        ObligationSettlementRepository.ObligationSettlement settlement
    ) throws SQLException {
        try (Connection c = db.openConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                settlementRepo.deleteDirect(c, userUid, settlement.id());
                transactionRepo.deleteDirect(c, userUid, settlement.linkedTransactionId());
                ObligationPublishQueue.markPendingDelete(
                    c, userUid, settlement.id(), settlement.linkedTransactionId());
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public static Set<String> idsOfObligations(List<ObligationRepository.Obligation> records) {
        Set<String> out = new HashSet<>();
        for (ObligationRepository.Obligation o : records) {
            out.add(o.id());
        }
        return out;
    }

    public static Set<String> idsOfSettlements(List<ObligationSettlementRepository.ObligationSettlement> records) {
        Set<String> out = new HashSet<>();
        for (ObligationSettlementRepository.ObligationSettlement s : records) {
            out.add(s.id());
        }
        return out;
    }
}
