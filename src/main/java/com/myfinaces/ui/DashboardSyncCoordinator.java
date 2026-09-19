package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.auth.AuthSessionManager;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanMovementRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.service.GoalService;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.admin.LoanAdminState;
import myfinances.infrastructure.loan.admin.JdbcLoanAdminStateRepository;
import myfinances.infrastructure.loan.migration.CanonicalLoanDuplicateReconciler;
import myfinances.infrastructure.loan.migration.LegacyLoanMigration;
import myfinances.infrastructure.loan.migration.PhantomLoanTransactionReconciler;
import myfinances.infrastructure.loan.migration.ReversedLoanPaymentReconciler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class DashboardSyncCoordinator {

    private DashboardSyncCoordinator() {
    }

    public record SyncActions(
        Runnable runSyncNow,
        Runnable doRefreshNow
    ) {
    }

    public static SyncActions setup(
        AuthSession session,
        AuthSessionManager sessionManager,
        AccountRepository accountRepo,
        GoalRepository goalRepo,
        CategoryRepository categoryRepo,
        LoanRepository loanRepo,
        JdbcLoanAdminStateRepository loanAdminStateRepository,
        LoanPaymentRepository loanPaymentRepo,
        LoanMovementRepository loanMovementRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        BudgetRepository budgetRepo,
        LoanApplicationService loanApplicationService,
        Runnable refreshBalances,
        AtomicBoolean syncInProgress,
        AtomicLong lastSyncMs,
        AtomicLong syncBlockedUntilMs,
        Runnable onSyncStart,
        Runnable onSyncSuccess,
        Consumer<String> onSyncStatus
    ) {
        GoalService goalService = new GoalService(goalRepo, accountRepo, transferRepo);

        Runnable pullCategories = () -> {
            System.out.println("[Sync] pullCategories start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<CategoryRepository.Category> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullCategories(sessionManager.current()));
                System.out.println("[Sync] pulled categories=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (CategoryRepository.Category c : remote) {
                    remoteIds.add(c.id());
                }

                List<CategoryRepository.Category> roots = new ArrayList<>();
                List<CategoryRepository.Category> children = new ArrayList<>();
                for (CategoryRepository.Category c : remote) {
                    if (c.parentId() == null || c.parentId().isBlank()) {
                        roots.add(c);
                    } else {
                        children.add(c);
                    }
                }

                for (CategoryRepository.Category c : roots) {
                    categoryRepo.upsertFromRemote(session.uid(), c);
                }

                for (int pass = 0; pass < 5; pass++) {
                    boolean progressed = false;
                    for (CategoryRepository.Category c : children) {
                        if (c.parentId() == null || c.parentId().isBlank()) {
                            continue;
                        }
                        if (categoryRepo.getById(session.uid(), c.parentId()) == null) {
                            continue;
                        }
                        categoryRepo.upsertFromRemote(session.uid(), c);
                        progressed = true;
                    }
                    if (!progressed) {
                        break;
                    }
                }

                // Delete local categories that no longer exist in Firestore.
                // Delete children first to avoid FK issues.
                List<CategoryRepository.Category> localAll = categoryRepo.listAll(session.uid());
                List<String> toDeleteChildren = new ArrayList<>();
                List<String> toDeleteRoots = new ArrayList<>();
                for (CategoryRepository.Category c : localAll) {
                    if (remoteIds.contains(c.id())) {
                        continue;
                    }
                    if (c.parentId() == null || c.parentId().isBlank()) {
                        toDeleteRoots.add(c.id());
                    } else {
                        toDeleteChildren.add(c.id());
                    }
                }

                for (String id : toDeleteChildren) {
                    try {
                        categoryRepo.delete(session.uid(), id);
                    } catch (Exception ignored) {
                    }
                }
                for (String id : toDeleteRoots) {
                    try {
                        categoryRepo.delete(session.uid(), id);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullCategories failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullCategories end");
            }
        };

        Runnable pushPending = () -> {
            System.out.println("[Sync] pushPending start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                sessionManager.executeWithAuthRetry(() -> {
                    AuthSession s = sessionManager.current();
                    sync.syncTransactions(s, txRepo);
                    sync.syncTransfers(s, transferRepo);

                    List<LoanRepository.Loan> pendingLoans = loanRepo.listPendingForSync(s.uid());
                    System.out.println("[Sync] loans pending=" + pendingLoans.size());
                    for (LoanRepository.Loan l : pendingLoans) {
                        sync.syncLoan(s, l);
                        try {
                            loanRepo.markSynced(s.uid(), l.id());
                        } catch (Exception ignored) {
                        }
                    }

                    List<LoanPaymentRepository.LoanPayment> pendingPayments = loanPaymentRepo.listPendingForSync(s.uid());
                    System.out.println("[Sync] loanPayments pending=" + pendingPayments.size());
                    for (LoanPaymentRepository.LoanPayment p : pendingPayments) {
                        sync.syncLoanPayment(s, p);
                        try {
                            loanPaymentRepo.markSynced(s.uid(), p.id());
                        } catch (Exception ignored) {
                        }
                    }

                    List<LoanMovementRepository.LoanMovement> pendingMovements = loanMovementRepo.listPendingForSync(s.uid());
                    System.out.println("[Sync] loanMovements pending=" + pendingMovements.size());
                    for (LoanMovementRepository.LoanMovement m : pendingMovements) {
                        sync.syncLoanMovement(s, m);
                        try {
                            loanMovementRepo.markSynced(s.uid(), m.id());
                        } catch (Exception ignored) {
                        }
                    }

                    List<LoanAdminState> pendingAdminStates = loanAdminStateRepository.listPendingForSync(s.uid());
                    System.out.println("[Sync] loanAdminStates pending=" + pendingAdminStates.size());
                    for (LoanAdminState st : pendingAdminStates) {
                        sync.publishLoanAdminState(s, st);
                        try {
                            loanAdminStateRepository.markSynced(s.uid(), st.loanId());
                        } catch (Exception ignored) {
                        }
                    }

                    // Reversiones del journal: reintenta los borrados remotos de
                    // pagos/movimientos/transacciones que pudieron fallar offline.
                    ReversedLoanPaymentReconciler.reconcile(SqliteDatabase.defaultDatabase(), sync, s);

                    // Fantasmas históricos: transacciones materializadas por el
                    // backfill desde ADJUSTMENT sintéticos previas a Sprint 7J.2.
                    PhantomLoanTransactionReconciler.reconcile(SqliteDatabase.defaultDatabase(), sync, s);

                    // Duplicados canónicos históricos: materializaciones del
                    // backfill para eventos cuya transacción legacy ya existía
                    // sin estar enlazada (reparados por Sprint 7J.5/7J.6).
                    CanonicalLoanDuplicateReconciler.reconcile(SqliteDatabase.defaultDatabase(), sync, s);
                    return null;
                });
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pushPending failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("429"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pushPending end");
            }
        };

        Runnable pullLoanPayments = () -> {
            System.out.println("[Sync] pullLoanPayments start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<LoanPaymentRepository.LoanPayment> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullLoanPayments(sessionManager.current()));
                System.out.println("[Sync] pulled loanPayments=" + remote.size());
                System.out.println("[Sync] remote loanPayment ids=" + remote.stream().map(LoanPaymentRepository.LoanPayment::id).toList());

                Set<String> remoteIds = new HashSet<>();
                for (LoanPaymentRepository.LoanPayment p : remote) {
                    remoteIds.add(p.id());
                    logPaymentPull("PAYMENT_PULL_START", p, "stage=coordinator");
                    if (loanRepo.getByIdOrNull(session.uid(), p.loanId()) == null) {
                        logPaymentPull("PAYMENT_PULL_REJECTED", p, "stage=coordinator reason=missingLoan");
                        continue;
                    }
                    if (accountRepo.getById(session.uid(), p.accountId()) == null) {
                        logPaymentPull("PAYMENT_PULL_REJECTED", p, "stage=coordinator reason=missingAccount");
                        continue;
                    }
                    try {
                        loanPaymentRepo.upsertFromRemote(session.uid(), p);
                        logPaymentPull("PAYMENT_PULL_ACCEPTED", p, "stage=coordinator reason=upserted");
                    } catch (Exception e) {
                        logPaymentPull("PAYMENT_PULL_REJECTED", p, "stage=coordinator reason=upsertFailed error=" + e.getMessage());
                    }
                }

                List<LoanPaymentRepository.LoanPayment> localAll = loanPaymentRepo.listAllByUser(session.uid());
                // Filas pendientes de push no aparecen en el snapshot remoto
                // (REST no tiene cola offline): conservarlas para reintento.
                Set<String> pendingIds = loanPaymentRepo.listPendingSyncIds(session.uid());
                System.out.println("[Sync] local loanPayments before reconciliation=" + localAll.size()
                    + " pendingSync=" + pendingIds.size());
                for (LoanPaymentRepository.LoanPayment p : localAll) {
                    if (remoteIds.contains(p.id()) || pendingIds.contains(p.id())) {
                        continue;
                    }
                    System.out.println("[Sync] deleting local loanPayment missing from remote paymentId=" + p.id()
                        + " loanId=" + p.loanId()
                        + " amount=" + p.principalCents()
                        + " linkedTransactionId=" + p.linkedTransactionId()
                        + " reason=absentInFirestoreRemoteSnapshot remoteCount=" + remote.size()
                        + " remoteIds=" + remoteIds);
                    try {
                        loanPaymentRepo.delete(session.uid(), p.id());
                    } catch (Exception e) {
                        System.out.println("[Sync] delete local loanPayment failed paymentId=" + p.id() + " error=" + e.getMessage());
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullLoanPayments failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullLoanPayments end");
            }
        };

        Runnable pullLoanMovements = () -> {
            System.out.println("[Sync] pullLoanMovements start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());

                // Cobertura: préstamos remotos + préstamos locales (uno borrado
                // en remoto sigue siendo consultable; su subcolección vuelve
                // vacía y sus movimientos locales se podan).
                Set<String> loanIds = new LinkedHashSet<>();
                for (LoanRepository.Loan l : sessionManager.executeWithAuthRetry(() -> sync.pullLoans(sessionManager.current()))) {
                    loanIds.add(l.id());
                }
                for (LoanRepository.Loan l : loanRepo.listAllByUser(session.uid())) {
                    loanIds.add(l.id());
                }

                Set<String> remoteKeys = new HashSet<>();
                Set<String> failedLoanIds = new HashSet<>();
                int ingested = 0;
                for (String loanId : loanIds) {
                    List<LoanMovementRepository.LoanMovement> remote;
                    try {
                        remote = sessionManager.executeWithAuthRetry(
                            () -> sync.pullLoanMovements(sessionManager.current(), session.uid(), loanId));
                    } catch (Exception e) {
                        rethrowIfAuthFailure(e);
                        System.out.println("[Sync] pull movements failed loanId=" + loanId + ": " + e.getMessage());
                        failedLoanIds.add(loanId);
                        continue;
                    }
                    for (LoanMovementRepository.LoanMovement m : remote) {
                        remoteKeys.add(loanId + "/" + m.id());
                        try {
                            loanMovementRepo.upsertFromRemote(session.uid(), m);
                            ingested++;
                        } catch (Exception e) {
                            System.out.println("[Sync] upsert remote movement failed id=" + m.id() + " loanId=" + loanId + " error=" + e.getMessage());
                        }
                    }
                }
                System.out.println("[Sync] pulled loanMovements loans=" + loanIds.size() + " docs=" + remoteKeys.size()
                    + " ingested=" + ingested + " failedLoans=" + failedLoanIds.size());

                // Poda: una fila local ausente del snapshot remoto fue eliminada
                // en otro dispositivo (p. ej. pago revertido). Se conservan las
                // filas pending_sync=1 (push pendiente) y las de préstamos cuya
                // subcolección no pudo leerse (snapshot parcial).
                Set<String> pendingIds = loanMovementRepo.listPendingSyncIds(session.uid());
                int pruned = 0;
                for (LoanMovementRepository.LoanMovement m : loanMovementRepo.listAllByUser(session.uid())) {
                    if (failedLoanIds.contains(m.loanId()) || pendingIds.contains(m.id())) {
                        continue;
                    }
                    if (remoteKeys.contains(m.loanId() + "/" + m.id())) {
                        continue;
                    }
                    try {
                        loanMovementRepo.delete(session.uid(), m.id());
                        pruned++;
                    } catch (Exception e) {
                        System.out.println("[Sync] delete local movement failed id=" + m.id() + " error=" + e.getMessage());
                    }
                }
                if (pruned > 0) {
                    System.out.println("[Sync] pruned local loanMovements absent in remote=" + pruned);
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullLoanMovements failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullLoanMovements end");
            }
        };

        Runnable pullLoans = () -> {
            System.out.println("[Sync] pullLoans start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<LoanRepository.Loan> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullLoans(sessionManager.current()));
                System.out.println("[Sync] pulled loans=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (LoanRepository.Loan l : remote) {
                    remoteIds.add(l.id());
                    try {
                        loanRepo.upsertFromRemote(session.uid(), l);
                        loanAdminStateRepository.upsertFromRemote(
                            session.uid(),
                            l.id(),
                            l.archived(),
                            l.archivedAtEpochSec(),
                            l.updatedAtEpochSec(),
                            l.updatedBy()
                        );
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullLoans failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullLoans end");
            }
        };

        Runnable pullTransfers = () -> {
            System.out.println("[Sync] pullTransfers start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<TransferRepository.TransferSyncRow> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullTransfers(sessionManager.current()));
                System.out.println("[Sync] pulled transfers=" + remote.size());

                int appliedInsert = 0;
                int appliedUpdate = 0;
                int skippedMissingAccount = 0;
                int skippedStale = 0;
                int staleButDifferent = 0;
                int pushedLocalNewer = 0;
                int printed = 0;

                Set<String> remoteIds = new HashSet<>();
                for (TransferRepository.TransferSyncRow tr : remote) {
                    remoteIds.add(tr.id());
                    if (accountRepo.getById(session.uid(), tr.fromAccountId()) == null) {
                        skippedMissingAccount++;
                        continue;
                    }
                    if (accountRepo.getById(session.uid(), tr.toAccountId()) == null) {
                        skippedMissingAccount++;
                        continue;
                    }
                    try {
                        TransferRepository.TransferSyncRow local = transferRepo.getForSyncByIdOrNull(session.uid(), tr.id());
                        boolean shouldApplyUpdate = false;
                        if (local != null) {
                            if (tr.updatedAtEpochSec() > local.updatedAtEpochSec()) {
                                shouldApplyUpdate = true;
                            } else if (tr.updatedAtEpochSec() == local.updatedAtEpochSec()) {
                                boolean same =
                                    local.amountCents() == tr.amountCents() &&
                                        local.occurredAtEpochSec() == tr.occurredAtEpochSec() &&
                                        java.util.Objects.equals(local.note(), tr.note()) &&
                                        java.util.Objects.equals(local.fromAccountId(), tr.fromAccountId()) &&
                                        java.util.Objects.equals(local.toAccountId(), tr.toAccountId());
                                if (!same) {
                                    shouldApplyUpdate = true;
                                }
                            }
                        }
                        transferRepo.upsertFromRemote(session.uid(), tr);
                        if (local == null) {
                            appliedInsert++;
                        } else if (shouldApplyUpdate) {
                            appliedUpdate++;
                        } else {
                            skippedStale++;
                            if (local.amountCents() != tr.amountCents() || local.occurredAtEpochSec() != tr.occurredAtEpochSec() || !java.util.Objects.equals(local.note(), tr.note())) {
                                staleButDifferent++;
                                if (local.updatedAtEpochSec() > tr.updatedAtEpochSec()) {
                                    sessionManager.executeWithAuthRetry(() -> {
                                        sync.syncTransfer(sessionManager.current(), local);
                                        return null;
                                    });
                                    pushedLocalNewer++;
                                }
                                if (printed++ < 8) {
                                    System.out.println(
                                        "[Sync] transfer stale-but-different id=" + tr.id() +
                                            " remoteAmount=" + tr.amountCents() +
                                            " localAmount=" + local.amountCents() +
                                            " remoteUpdatedAt=" + tr.updatedAtEpochSec() +
                                            " localUpdatedAt=" + local.updatedAtEpochSec()
                                    );
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        rethrowIfAuthFailure(ignored);
                    }
                }

                System.out.println(
                    "[Sync] transfers appliedInsert=" + appliedInsert +
                        " appliedUpdate=" + appliedUpdate +
                        " skippedMissingAccount=" + skippedMissingAccount +
                        " skippedStale=" + skippedStale +
                        " staleButDifferent=" + staleButDifferent +
                        " pushedLocalNewer=" + pushedLocalNewer
                );

                List<String> localIds = transferRepo.listIdsForRemotePrune(session.uid());
                for (String id : localIds) {
                    if (remoteIds.contains(id)) {
                        continue;
                    }
                    try {
                        transferRepo.delete(session.uid(), id);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullTransfers failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullTransfers end");
            }
        };

        Runnable pullTransactions = () -> {
            System.out.println("[Sync] pullTransactions start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<TransactionRepository.TransactionSyncRow> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullTransactions(sessionManager.current()));
                System.out.println("[Sync] pulled transactions=" + remote.size());

                int appliedInsert = 0;
                int appliedUpdate = 0;
                int skippedMissingAccount = 0;
                int skippedMissingCategory = 0;
                int skippedStale = 0;
                int staleButDifferent = 0;
                int pushedLocalNewer = 0;
                int printed = 0;

                Set<String> remoteIds = new HashSet<>();
                for (TransactionRepository.TransactionSyncRow t : remote) {
                    remoteIds.add(t.id());
                    if (isLoanRepaymentKind(t.kind())) {
                        logTransactionPull("TRANSACTION_PULL_START", t, "stage=coordinator");
                    }
                    if (accountRepo.getById(session.uid(), t.accountId()) == null) {
                        skippedMissingAccount++;
                        if (isLoanRepaymentKind(t.kind())) {
                            logTransactionPull("TRANSACTION_PULL_REJECTED", t, "stage=coordinator reason=missingAccount");
                        }
                        continue;
                    }
                    if (categoryRepo.getById(session.uid(), t.categoryId()) == null) {
                        skippedMissingCategory++;
                        if (isLoanRepaymentKind(t.kind())) {
                            logTransactionPull("TRANSACTION_PULL_REJECTED", t, "stage=coordinator reason=missingCategory");
                        }
                        continue;
                    }
                    try {
                        TransactionRepository.TransactionSyncRow local = txRepo.getForSyncByIdOrNull(session.uid(), t.id());
                        boolean shouldApplyUpdate = false;
                        if (local != null) {
                            if (t.updatedAtEpochSec() > local.updatedAtEpochSec()) {
                                shouldApplyUpdate = true;
                            } else if (t.updatedAtEpochSec() == local.updatedAtEpochSec()) {
                                boolean same =
                                    local.amountCents() == t.amountCents() &&
                                        local.occurredAtEpochSec() == t.occurredAtEpochSec() &&
                                        java.util.Objects.equals(local.note(), t.note()) &&
                                        java.util.Objects.equals(local.accountId(), t.accountId()) &&
                                        java.util.Objects.equals(local.categoryId(), t.categoryId()) &&
                                        java.util.Objects.equals(local.kind(), t.kind());
                                if (!same) {
                                    shouldApplyUpdate = true;
                                }
                            }
                        }
                        txRepo.upsertFromRemote(session.uid(), t);
                        if (local == null) {
                            appliedInsert++;
                        } else if (shouldApplyUpdate) {
                            appliedUpdate++;
                        } else {
                            skippedStale++;
                            if (isLoanRepaymentKind(t.kind())) {
                                logTransactionPull("TRANSACTION_PULL_ACCEPTED", t, "stage=coordinator resolution=staleNoChange");
                            }
                            if (
                                local.amountCents() != t.amountCents() ||
                                    local.occurredAtEpochSec() != t.occurredAtEpochSec() ||
                                    !java.util.Objects.equals(local.accountId(), t.accountId()) ||
                                    !java.util.Objects.equals(local.categoryId(), t.categoryId()) ||
                                    !java.util.Objects.equals(local.kind(), t.kind()) ||
                                    !java.util.Objects.equals(local.note(), t.note())
                            ) {
                                staleButDifferent++;
                                if (local.updatedAtEpochSec() > t.updatedAtEpochSec()) {
                                    sessionManager.executeWithAuthRetry(() -> {
                                        sync.syncTransaction(sessionManager.current(), local);
                                        return null;
                                    });
                                    pushedLocalNewer++;
                                }
                                if (printed++ < 12) {
                                    System.out.println(
                                        "[Sync] tx stale-but-different id=" + t.id() +
                                            " remoteAmount=" + t.amountCents() +
                                            " localAmount=" + local.amountCents() +
                                            " remoteUpdatedAt=" + t.updatedAtEpochSec() +
                                            " localUpdatedAt=" + local.updatedAtEpochSec() +
                                            " accountId=" + t.accountId() +
                                            " categoryId=" + t.categoryId()
                                    );
                                }
                            }
                            continue;
                        }
                        if (isLoanRepaymentKind(t.kind())) {
                            String resolution = local == null ? "inserted" : "updated";
                            logTransactionPull("TRANSACTION_PULL_ACCEPTED", t, "stage=coordinator resolution=" + resolution);
                        }
                    } catch (Exception e) {
                        rethrowIfAuthFailure(e);
                        if (isLoanRepaymentKind(t.kind())) {
                            logTransactionPull("TRANSACTION_PULL_REJECTED", t, "stage=coordinator reason=upsertFailed error=" + e.getMessage());
                        }
                    }
                }

                System.out.println(
                    "[Sync] transactions appliedInsert=" + appliedInsert +
                        " appliedUpdate=" + appliedUpdate +
                        " skippedMissingAccount=" + skippedMissingAccount +
                        " skippedMissingCategory=" + skippedMissingCategory +
                        " skippedStale=" + skippedStale +
                        " staleButDifferent=" + staleButDifferent +
                        " pushedLocalNewer=" + pushedLocalNewer
                );

                List<String> localIds = txRepo.listIdsForRemotePrune(session.uid());
                for (String id : localIds) {
                    if (remoteIds.contains(id)) {
                        continue;
                    }
                    try {
                        // La poda usa el borrado interno: el snapshot remoto es
                        // autoritativo y la guarda de delete() solo protege la UI.
                        txRepo.deleteFailedLoanTransaction(session.uid(), id);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullTransactions failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullTransactions end");
            }
        };

        Runnable pullAccounts = () -> {
            System.out.println("[Sync] pullAccounts start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<AccountRepository.Account> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullAccounts(sessionManager.current()));
                System.out.println("[Sync] pulled accounts=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (AccountRepository.Account a : remote) {
                    remoteIds.add(a.id());
                    accountRepo.upsertFromRemote(session.uid(), a);
                }

                List<AccountRepository.Account> localAll = accountRepo.list(session.uid());
                for (AccountRepository.Account a : localAll) {
                    if (remoteIds.contains(a.id())) {
                        continue;
                    }
                    try {
                        accountRepo.delete(session.uid(), a.id());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullAccounts failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullAccounts end");
            }
        };

        Runnable pullGoals = () -> {
            System.out.println("[Sync] pullGoals start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<GoalRepository.Goal> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullGoals(sessionManager.current()));
                System.out.println("[Sync] pulled goals=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (GoalRepository.Goal g : remote) {
                    remoteIds.add(g.id());
                    if (accountRepo.getById(session.uid(), g.accountId()) == null) {
                        continue;
                    }
                    try {
                        goalRepo.upsertFromRemote(session.uid(), g);
                    } catch (Exception ignored) {
                    }
                }

                List<GoalRepository.Goal> localAll = goalRepo.listByUser(session.uid());
                for (GoalRepository.Goal g : localAll) {
                    if (remoteIds.contains(g.id())) {
                        continue;
                    }
                    try {
                        if (goalService.tieneHistorial(session.uid(), g.accountId())) {
                            goalRepo.archive(session.uid(), g.id());
                        } else {
                            goalRepo.delete(session.uid(), g.id());
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullGoals failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullGoals end");
            }
        };

        Runnable pullBudgets = () -> {
            System.out.println("[Sync] pullBudgets start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<BudgetRepository.Budget> remote =
                    sessionManager.executeWithAuthRetry(() -> sync.pullBudgets(sessionManager.current()));
                System.out.println("[Sync] pulled budgets=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (BudgetRepository.Budget b : remote) {
                    remoteIds.add(b.id());
                    try {
                        budgetRepo.upsertFromRemote(session.uid(), b);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                rethrowIfAuthFailure(ex);
                String msg = ex.getMessage();
                System.out.println("[Sync] pullBudgets failed: " + msg);
                if (msg != null && (msg.contains("Firestore pull failed (429)") || msg.contains("Quota exceeded") || msg.contains("RESOURCE_EXHAUSTED"))) {
                    syncBlockedUntilMs.set(System.currentTimeMillis() + 900_000L);
                    throw new RuntimeException(ex);
                }
            } finally {
                System.out.println("[Sync] pullBudgets end");
            }
        };

        Runnable ingestCanonicalLoans = () -> {
            System.out.println("[Sync] ingestCanonicalLoans start");
            try {
                LegacyLoanMigration.migrate(SqliteDatabase.defaultDatabase(), loanApplicationService);
            } catch (Exception ex) {
                System.out.println("[Sync] ingestCanonicalLoans failed: " + ex.getMessage());
            } finally {
                System.out.println("[Sync] ingestCanonicalLoans end");
            }
        };

        Consumer<Boolean> startSync = (manual) -> {
            long nowMs = System.currentTimeMillis();
            long blockedUntil = syncBlockedUntilMs.get();
            if (blockedUntil > nowMs) {
                System.out.println("[Sync] skip: quota cooldown active");
                Platform.runLater(() -> onSyncStatus.accept("Sincronización pausada por cuota (intenta más tarde)"));
                return;
            }
            if (!manual) {
                long last = lastSyncMs.get();
                if (last > 0 && (nowMs - last) < 120_000L) {
                    System.out.println("[Sync] skip: cooldown active");
                    return;
                }
            }
            if (!syncInProgress.compareAndSet(false, true)) {
                System.out.println("[Sync] skip: sync already in progress");
                if (manual) {
                    Platform.runLater(() -> onSyncStatus.accept("Sincronización ya en curso"));
                }
                return;
            }
            lastSyncMs.set(nowMs);
            Platform.runLater(onSyncStart);
            new Thread(() -> {
                System.out.println("[Sync] refresh thread start");
                boolean[] authFailed = {false};
                try {
                    try {
                        sessionManager.validSession();
                        pushPending.run();
                        pullCategories.run();
                        pullAccounts.run();
                        pullGoals.run();
                        pullLoans.run();
                        pullLoanPayments.run();
                        pullLoanMovements.run();
                        pullTransactions.run();
                        pullTransfers.run();
                        pullBudgets.run();
                        ingestCanonicalLoans.run();
                    } catch (AuthSessionManager.SyncAuthenticationException authEx) {
                        authFailed[0] = true;
                        System.out.println("[Sync] aborted: authentication could not be renewed: " + authEx.getMessage());
                        Platform.runLater(() -> onSyncStatus.accept(
                            "La sesión expiró y no pudo renovarse. Vuelve a iniciar sesión."));
                    } catch (RuntimeException quotaAbort) {
                        System.out.println("[Sync] aborted due to quota (429)");
                        Platform.runLater(() -> onSyncStatus.accept("Sincronización pausada por cuota (intenta más tarde)"));
                    }
                } finally {
                    syncInProgress.set(false);
                }
                Platform.runLater(() -> {
                    if (authFailed[0]) {
                        System.out.println("[Sync] refresh thread end (authentication aborted)");
                        return;
                    }
                    try {
                        refreshBalances.run();
                        onSyncSuccess.run();
                    } finally {
                        System.out.println("[Sync] refresh thread end");
                    }
                });
            }).start();
        };

        Runnable runSyncNow = () -> startSync.accept(false);

        Runnable doRefreshNow = () -> {
            System.out.println("[Sync] manual refresh triggered");
            startSync.accept(true);
        };

        return new SyncActions(runSyncNow, doRefreshNow);
    }

    private static boolean isLoanRepaymentKind(String kind) {
        if (kind == null) {
            return false;
        }
        String normalized = kind.trim().toUpperCase();
        return "LOAN_REPAYMENT_PRINCIPAL_IN".equals(normalized)
            || "LOAN_REPAYMENT_PRINCIPAL_OUT".equals(normalized);
    }

    private static void logPaymentPull(String label, LoanPaymentRepository.LoanPayment payment, String extras) {
        System.out.println(
            "[LoanPaymentTrace] " + label
                + " loanId=" + valueOrDash(payment.loanId())
                + " paymentId=" + valueOrDash(payment.id())
                + " transactionId=" + valueOrDash(payment.linkedTransactionId())
                + " operationId=- eventId=" + valueOrDash(payment.id())
                + " updatedAt=" + payment.updatedAtEpochSec()
                + " updatedBy=" + valueOrDash(payment.updatedBy())
                + " accountId=" + valueOrDash(payment.accountId())
                + " principalCents=" + payment.principalCents()
                + " occurredAt=" + payment.occurredAtEpochSec()
                + (extras == null || extras.isBlank() ? "" : " " + extras)
        );
    }

    private static void logTransactionPull(String label, TransactionRepository.TransactionSyncRow transaction, String extras) {
        System.out.println(
            "[LoanPaymentTrace] " + label
                + " loanId=- paymentId=- transactionId=" + valueOrDash(transaction.id())
                + " operationId=- eventId=-"
                + " updatedAt=" + transaction.updatedAtEpochSec()
                + " updatedBy=-"
                + " accountId=" + valueOrDash(transaction.accountId())
                + " categoryId=" + valueOrDash(transaction.categoryId())
                + " kind=" + valueOrDash(transaction.kind())
                + " amountCents=" + transaction.amountCents()
                + " occurredAt=" + transaction.occurredAtEpochSec()
                + (extras == null || extras.isBlank() ? "" : " " + extras)
        );
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static void rethrowIfAuthFailure(Exception ex) {
        if (ex instanceof AuthSessionManager.SyncAuthenticationException authEx) {
            throw authEx;
        }
    }
}
