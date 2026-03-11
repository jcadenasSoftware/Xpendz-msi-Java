package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
        AccountRepository accountRepo,
        GoalRepository goalRepo,
        CategoryRepository categoryRepo,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        BudgetRepository budgetRepo,
        Runnable refreshBalances,
        AtomicBoolean syncInProgress,
        AtomicLong lastSyncMs,
        AtomicLong syncBlockedUntilMs
    ) {
        Runnable pullCategories = () -> {
            System.out.println("[Sync] pullCategories start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<CategoryRepository.Category> remote = sync.pullCategories(session);
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

        Runnable pullLoanPayments = () -> {
            System.out.println("[Sync] pullLoanPayments start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<LoanPaymentRepository.LoanPayment> remote = sync.pullLoanPayments(session);
                System.out.println("[Sync] pulled loanPayments=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (LoanPaymentRepository.LoanPayment p : remote) {
                    remoteIds.add(p.id());
                    if (loanRepo.getByIdOrNull(session.uid(), p.loanId()) == null) {
                        continue;
                    }
                    if (accountRepo.getById(session.uid(), p.accountId()) == null) {
                        continue;
                    }
                    try {
                        loanPaymentRepo.upsertFromRemote(session.uid(), p);
                    } catch (Exception ignored) {
                    }
                }

                List<LoanPaymentRepository.LoanPayment> localAll = loanPaymentRepo.listAllByUser(session.uid());
                for (LoanPaymentRepository.LoanPayment p : localAll) {
                    if (remoteIds.contains(p.id())) {
                        continue;
                    }
                    try {
                        loanPaymentRepo.delete(session.uid(), p.id());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
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

        Runnable pullLoans = () -> {
            System.out.println("[Sync] pullLoans start");
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<LoanRepository.Loan> remote = sync.pullLoans(session);
                System.out.println("[Sync] pulled loans=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (LoanRepository.Loan l : remote) {
                    remoteIds.add(l.id());
                    try {
                        loanRepo.upsertFromRemote(session.uid(), l);
                    } catch (Exception ignored) {
                    }
                }

                List<LoanRepository.Loan> localAllLent = loanRepo.listByType(session.uid(), LoanRepository.TYPE_LENT, null, false);
                for (LoanRepository.Loan l : localAllLent) {
                    if (remoteIds.contains(l.id())) {
                        continue;
                    }
                    try {
                        loanRepo.delete(session.uid(), l.id());
                    } catch (Exception ignored) {
                    }
                }
                List<LoanRepository.Loan> localAllBorrowed = loanRepo.listByType(session.uid(), LoanRepository.TYPE_BORROWED, null, false);
                for (LoanRepository.Loan l : localAllBorrowed) {
                    if (remoteIds.contains(l.id())) {
                        continue;
                    }
                    try {
                        loanRepo.delete(session.uid(), l.id());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
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
                List<TransferRepository.TransferSyncRow> remote = sync.pullTransfers(session);
                System.out.println("[Sync] pulled transfers=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (TransferRepository.TransferSyncRow tr : remote) {
                    remoteIds.add(tr.id());
                    if (accountRepo.getById(session.uid(), tr.fromAccountId()) == null) {
                        continue;
                    }
                    if (accountRepo.getById(session.uid(), tr.toAccountId()) == null) {
                        continue;
                    }
                    try {
                        transferRepo.upsertFromRemote(session.uid(), tr);
                    } catch (Exception ignored) {
                    }
                }

                List<TransferRepository.TransferSyncRow> localAll = transferRepo.listAllForSync(session.uid());
                for (TransferRepository.TransferSyncRow tr : localAll) {
                    if (remoteIds.contains(tr.id())) {
                        continue;
                    }
                    try {
                        transferRepo.delete(session.uid(), tr.id());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
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
                List<TransactionRepository.TransactionSyncRow> remote = sync.pullTransactions(session);
                System.out.println("[Sync] pulled transactions=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (TransactionRepository.TransactionSyncRow t : remote) {
                    remoteIds.add(t.id());
                    if (accountRepo.getById(session.uid(), t.accountId()) == null) {
                        continue;
                    }
                    if (categoryRepo.getById(session.uid(), t.categoryId()) == null) {
                        continue;
                    }
                    try {
                        txRepo.upsertFromRemote(session.uid(), t);
                    } catch (Exception ignored) {
                    }
                }

                List<TransactionRepository.TransactionSyncRow> localAll = txRepo.listAllForSync(session.uid());
                for (TransactionRepository.TransactionSyncRow t : localAll) {
                    if (remoteIds.contains(t.id())) {
                        continue;
                    }
                    try {
                        txRepo.delete(session.uid(), t.id());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
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
                List<AccountRepository.Account> remote = sync.pullAccounts(session);
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
                List<GoalRepository.Goal> remote = sync.pullGoals(session);
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
                        goalRepo.delete(session.uid(), g.id());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
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
                List<BudgetRepository.Budget> remote = sync.pullBudgets(session);
                System.out.println("[Sync] pulled budgets=" + remote.size());

                Set<String> remoteIds = new HashSet<>();
                for (BudgetRepository.Budget b : remote) {
                    remoteIds.add(b.id());
                    try {
                        budgetRepo.upsertFromRemote(session.uid(), b);
                    } catch (Exception ignored) {
                    }
                }

                List<BudgetRepository.Budget> localAll = budgetRepo.listByUser(session.uid());
                for (BudgetRepository.Budget b : localAll) {
                    if (remoteIds.contains(b.id())) {
                        continue;
                    }
                    try {
                        budgetRepo.delete(session.uid(), b.id());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
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

        Runnable runSyncNow = () -> {
            long nowMs = System.currentTimeMillis();
            long blockedUntil = syncBlockedUntilMs.get();
            if (blockedUntil > nowMs) {
                System.out.println("[Sync] skip: quota cooldown active");
                return;
            }
            long last = lastSyncMs.get();
            if (last > 0 && (nowMs - last) < 120_000L) {
                System.out.println("[Sync] skip: cooldown active");
                return;
            }
            if (!syncInProgress.compareAndSet(false, true)) {
                System.out.println("[Sync] skip: sync already in progress");
                return;
            }
            lastSyncMs.set(nowMs);
            new Thread(() -> {
                System.out.println("[Sync] refresh thread start");
                try {
                    try {
                        pullCategories.run();
                        pullAccounts.run();
                        pullGoals.run();
                        pullLoans.run();
                        pullLoanPayments.run();
                        pullTransactions.run();
                        pullTransfers.run();
                        pullBudgets.run();
                    } catch (RuntimeException quotaAbort) {
                        System.out.println("[Sync] aborted due to quota (429)");
                    }
                } finally {
                    syncInProgress.set(false);
                }
                Platform.runLater(() -> {
                    try {
                        refreshBalances.run();
                    } finally {
                        System.out.println("[Sync] refresh thread end");
                    }
                });
            }).start();
        };

        Runnable doRefreshNow = () -> {
            System.out.println("[Sync] manual refresh triggered");
            runSyncNow.run();
        };

        return new SyncActions(runSyncNow, doRefreshNow);
    }
}
