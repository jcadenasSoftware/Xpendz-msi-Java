package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.config.AppConfig;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.FileChooser;
import javafx.scene.control.TextFormatter;
import javafx.util.Duration;

import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class DashboardView {

    public interface Listener {
        void onLogout();
    }

    public static Parent create(
        AuthSession session,
        Listener listener,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        GoalRepository goalRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        BudgetRepository budgetRepo,
        BooleanProperty darkTheme
    ) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<ScheduledFuture<?>> autoSyncRef = new AtomicReference<>();
        AtomicBoolean syncInProgress = new AtomicBoolean(false);
        AtomicLong lastSyncMs = new AtomicLong(0L);
        AtomicLong syncBlockedUntilMs = new AtomicLong(0L);

        Label title = new Label("Panel principal");
        title.getStyleClass().add("app-title");

        Label totalCaption = new Label("Saldo total");
        totalCaption.getStyleClass().add("text-secondary");
        Label totalValue = new Label();
        totalValue.getStyleClass().add("account-name");
        totalValue.getStyleClass().add("money-neutral");
        totalValue.getStyleClass().add("dashboard-total-value");

        AtomicReference<Runnable> refreshBalancesRef = new AtomicReference<>();
        AtomicBoolean hideTotalBalance = new AtomicBoolean(true);
        Button toggleTotal = new Button("");
        toggleTotal.getStyleClass().add("btn-primary-soft");
        toggleTotal.setGraphic(new FontIcon("far-eye"));
        toggleTotal.setTooltip(new Tooltip("Mostrar saldo"));
        toggleTotal.setMinWidth(42);
        toggleTotal.setPrefWidth(42);
        toggleTotal.setMinHeight(34);
        toggleTotal.setPrefHeight(34);
        toggleTotal.setOnAction(e -> {
            hideTotalBalance.set(!hideTotalBalance.get());

            toggleTotal.setText("");
            toggleTotal.setGraphic(new FontIcon(hideTotalBalance.get() ? "far-eye" : "far-eye-slash"));
            toggleTotal.setTooltip(new Tooltip(hideTotalBalance.get() ? "Mostrar saldo" : "Ocultar saldo"));
            Runnable r = refreshBalancesRef.get();
            if (r != null) {
                r.run();
            }
        });

        HBox totalTop = new HBox(10, totalCaption);
        totalTop.setAlignment(Pos.CENTER_LEFT);

        Region totalValueSpacer = new Region();
        HBox.setHgrow(totalValueSpacer, Priority.ALWAYS);
        HBox totalRow = new HBox(10, totalValue, totalValueSpacer, toggleTotal);
        totalRow.setAlignment(Pos.CENTER_LEFT);

        VBox totalCard = new VBox(6, totalTop, totalRow);
        totalCard.getStyleClass().addAll("card", "summary-card");

        VBox accountsBox = new VBox(6);
        accountsBox.getStyleClass().add("accounts-list");

        VBox goalsBox = new VBox(6);
        goalsBox.getStyleClass().add("accounts-list");

        ScrollPane accountsScroll = new ScrollPane(accountsBox);
        accountsScroll.setFitToWidth(true);
        accountsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        accountsScroll.getStyleClass().addAll("card", "content-card");

        ScrollPane goalsScroll = new ScrollPane(goalsBox);
        goalsScroll.setFitToWidth(true);
        goalsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        goalsScroll.getStyleClass().addAll("card", "content-card");
        goalsScroll.setMinViewportHeight(240);
        goalsScroll.setPrefViewportHeight(260);
        goalsScroll.setVisible(false);
        goalsScroll.setManaged(false);

        Runnable openBudgetGoalsTab = () -> {
            Runnable r = refreshBalancesRef.get();
            if (r == null) {
                r = () -> {
                };
            }
            showBudgetDialog(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme.get(), r, 1);
        };

        Runnable refreshBalances = () -> refreshBalances(
            session,
            accountRepo,
            goalRepo,
            txRepo,
            transferRepo,
            totalValue,
            accountsBox,
            goalsBox,
            darkTheme,
            hideTotalBalance,
            openBudgetGoalsTab
        );
        refreshBalancesRef.set(refreshBalances);
        refreshBalances.run();

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

        Button addAccount = new Button("Agregar cuenta");
        addAccount.getStyleClass().add("btn-primary");
        addAccount.getStyleClass().add("nav-button");
        addAccount.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(addAccount, new FontIcon("fas-plus-circle"));
        addAccount.setOnAction(e -> {
            Optional<NewAccount> newAccount = showCreateAccountDialog(darkTheme.get());
            if (newAccount.isEmpty()) {
                return;
            }

            try {
                NewAccount a = newAccount.get();
                AccountRepository.Account created = accountRepo.create(session.uid(), a.name(), a.type(), a.currency());
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    sync.syncAccount(session, created);
                } catch (Exception ignored) {
                }
                refreshBalances.run();
            } catch (Exception ex) {
                totalValue.setText("No se pudo crear la cuenta.");
                accountsBox.getChildren().clear();
                accountsBox.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
            }
        });

        Button transactions = new Button("Transacciones");
        transactions.getStyleClass().add("btn-primary");
        transactions.getStyleClass().add("nav-button");
        transactions.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(transactions, new FontIcon("fas-receipt"));
        transactions.setOnAction(e -> showTransactionsDialog(session, txRepo, accountRepo, categoryRepo, darkTheme.get(), refreshBalances));

        Button summary = new Button("Resumen");
        summary.getStyleClass().add("btn-primary");
        summary.getStyleClass().add("nav-button");
        summary.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(summary, new FontIcon("fas-clipboard-list"));
        summary.setOnAction(e -> showSummaryDialog(session.uid(), txRepo, accountRepo, categoryRepo, darkTheme.get()));

        Button goals = new Button("Metas");
        goals.getStyleClass().add("btn-primary");
        goals.getStyleClass().add("nav-button");
        goals.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(goals, new FontIcon("fas-bullseye"));
        goals.setVisible(false);
        goals.setManaged(false);

        Button loans = new Button("Préstamos");
        loans.getStyleClass().add("btn-primary");
        loans.getStyleClass().add("nav-button");
        loans.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(loans, new FontIcon("fas-handshake"));
        loans.setOnAction(e -> showLoansDialog(session, loanRepo, loanPaymentRepo, accountRepo, darkTheme.get()));

        Button budget = new Button("Presupuesto");
        budget.getStyleClass().add("btn-primary");
        budget.getStyleClass().add("nav-button");
        budget.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(budget, new FontIcon("fas-piggy-bank"));
        budget.setOnAction(e -> showBudgetDialog(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme.get(), refreshBalances));

        Button charts = new Button("Gráficos");
        charts.getStyleClass().add("btn-primary");
        charts.getStyleClass().add("nav-button");
        charts.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(charts, new FontIcon("fas-chart-pie"));
        charts.setOnAction(e -> showChartsDialog(session.uid(), txRepo, accountRepo, categoryRepo, darkTheme.get()));

        Button transfers = new Button("Transferencias");
        transfers.getStyleClass().add("btn-primary");
        transfers.getStyleClass().add("nav-button");
        transfers.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(transfers, new FontIcon("fas-exchange-alt"));
        transfers.setOnAction(e -> showTransfersDialog(session, transferRepo, accountRepo, darkTheme.get(), refreshBalances));

        Button categories = new Button("Categorías");
        categories.getStyleClass().add("btn-primary");
        categories.getStyleClass().add("nav-button");
        categories.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(categories, new FontIcon("fas-tags"));
        categories.setOnAction(e -> showCategoriesDialog(session, categoryRepo, darkTheme.get()));

        Button syncNow = new Button("Actualizar");
        syncNow.getStyleClass().add("btn-secondary");
        syncNow.getStyleClass().add("nav-button");
        syncNow.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(syncNow, new FontIcon("fas-sync"));
        syncNow.setOnAction(e -> doRefreshNow.run());

        Button logout = new Button("Cerrar sesión");
        logout.getStyleClass().add("btn-danger");
        logout.getStyleClass().add("nav-button");
        logout.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(logout, new FontIcon("fas-sign-out-alt"));
        logout.setOnAction(e -> {
            try {
                ScheduledFuture<?> f = autoSyncRef.getAndSet(null);
                if (f != null) {
                    f.cancel(true);
                }
                scheduler.shutdownNow();
            } catch (Exception ignored) {
            }
            listener.onLogout();
        });

        Button exit = new Button("Salir");
        exit.getStyleClass().add("btn-danger");
        exit.getStyleClass().add("nav-button");
        exit.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(exit, new FontIcon("fas-times-circle"));
        exit.setOnAction(e -> {
            try {
                ScheduledFuture<?> f = autoSyncRef.getAndSet(null);
                if (f != null) {
                    f.cancel(true);
                }
                scheduler.shutdownNow();
            } catch (Exception ignored) {
            }
            Platform.exit();
            System.exit(0);
        });

        Button toggleTheme = new Button();
        toggleTheme.getStyleClass().add("btn-secondary");
        toggleTheme.getStyleClass().add("nav-button");
        FontIcon themeIcon = new FontIcon(darkTheme.get() ? "fas-moon" : "fas-sun");
        setButtonIcon(toggleTheme, themeIcon);
        toggleTheme.setText(darkTheme.get() ? "Tema: Oscuro" : "Tema: Claro");
        darkTheme.addListener((obs, oldV, newV) -> {
            toggleTheme.setText(Boolean.TRUE.equals(newV) ? "Tema: Oscuro" : "Tema: Claro");
            themeIcon.setIconLiteral(Boolean.TRUE.equals(newV) ? "fas-moon" : "fas-sun");
        });
        toggleTheme.setOnAction(e -> darkTheme.set(!darkTheme.get()));

        Label userCaption = new Label("Usuario");
        userCaption.getStyleClass().addAll("text-secondary", "sidebar-user-label");

        Label userEmail = new Label(session.email());
        userEmail.getStyleClass().add("sidebar-user-email");
        VBox userBox = new VBox(2, userCaption, userEmail);
        userBox.getStyleClass().add("sidebar-user");

        VBox menu = new VBox(12);
        menu.getStyleClass().add("sidebar");
        menu.setPadding(new Insets(16));
        menu.setPrefWidth(260);
        menu.setMinWidth(260);

        autoSyncRef.set(scheduler.scheduleAtFixedRate(() -> {
            runSyncNow.run();
        }, 120, 1800, TimeUnit.SECONDS));

        ImageView logo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                logo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setFitWidth(72);

        Label appName = new Label("Mis Finanzas");
        appName.getStyleClass().addAll("sidebar-title", "sidebar-title-gold");

        VBox brand = new VBox(6, logo, appName);
        brand.getStyleClass().add("sidebar-brand");
        brand.setMinHeight(Region.USE_PREF_SIZE);

        VBox menuTop = new VBox(6, brand, userBox);
        VBox menuMainActions = new VBox(10, transactions, transfers, summary, loans, budget, charts);
        menuMainActions.getStyleClass().add("sidebar-actions");
        VBox.setVgrow(menuMainActions, Priority.NEVER);

        Separator actionsSeparator = new Separator();
        actionsSeparator.getStyleClass().add("sidebar-separator");

        VBox menuSecondaryActions = new VBox(10, addAccount, categories, syncNow, logout, exit);
        menuSecondaryActions.getStyleClass().add("sidebar-actions");
        VBox.setVgrow(menuSecondaryActions, Priority.NEVER);

        Label footerCopyright = new Label(" JCadenas Software");
        footerCopyright.getStyleClass().add("sidebar-footer-text");
        Hyperlink footerLink = new Hyperlink("www.jcadenas.com");
        footerLink.getStyleClass().add("sidebar-footer-link");
        footerLink.setUserData("https://jcadenas.com/portfolio.php");
        footerLink.setOnAction(e -> {
            try {
                Object url = footerLink.getUserData();
                if (!(url instanceof String s) || s.isBlank()) {
                    return;
                }
                if (!java.awt.Desktop.isDesktopSupported()) {
                    return;
                }
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(s));
            } catch (Exception ignored) {
            }
        });
        VBox footer = new VBox(4, footerCopyright, footerLink);
        footer.getStyleClass().add("sidebar-footer");
        footer.setAlignment(Pos.CENTER);

        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        menu.getChildren().addAll(menuTop, menuMainActions, actionsSeparator, menuSecondaryActions, spacer, footer);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerBar = new HBox(12, title, headerSpacer, toggleTheme);
        headerBar.setFillHeight(true);

        VBox content = new VBox(14, headerBar, totalCard, accountsScroll, goalsScroll);
        content.getStyleClass().add("content");
        content.setPadding(new Insets(20));
        VBox.setVgrow(accountsScroll, Priority.ALWAYS);
        VBox.setVgrow(goalsScroll, Priority.SOMETIMES);

        ScrollPane sidebarScroll = new ScrollPane(menu);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setFitToHeight(true);
        sidebarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.getStyleClass().add("sidebar-scroll");

        menu.minHeightProperty().bind(sidebarScroll.heightProperty());

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(sidebarScroll);
        root.setCenter(content);
        root.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F5) {
                doRefreshNow.run();
                ev.consume();
            }
        });
        return root;
    }

    private static void setButtonIcon(Button button, FontIcon icon) {
        icon.getStyleClass().add("icon");
        icon.setIconSize(14);
        button.setGraphic(icon);
    }

    private static void showTransactionsDialog(
        AuthSession session,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Transacciones");
        UiDialogs.applyAppTheme(dialog, darkTheme);
        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(620);
        dialog.getDialogPane().setPrefHeight(660);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);

                        final double normalW = Math.min(1100, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(760, bounds.getHeight() * 0.90);
                        s.maximizedProperty().addListener((o, oldV, newV) -> {
                            if (Boolean.TRUE.equals(newV)) {
                                return;
                            }
                            try {
                                s.setWidth(normalW);
                                s.setHeight(normalH);
                                s.centerOnScreen();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Transacciones");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Filtra, crea, edita o elimina ingresos y gastos.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(96);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        VBox txBox = new VBox(6);
        txBox.getStyleClass().add("accounts-list");
        ScrollPane txScroll = new ScrollPane(txBox);
        txScroll.setFitToWidth(true);
        txScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        txScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(txScroll, Priority.ALWAYS);

        ChoiceBox<AccountRepository.Account> txAccountFilter = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> txRootCategoryFilter = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> txSubCategoryFilter = new ChoiceBox<>();
        DatePicker txFromDate = new DatePicker();
        DatePicker txToDate = new DatePicker();

        Runnable refreshTx = () -> refreshTransactions(
            session,
            userUid,
            txRepo,
            txBox,
            txAccountFilter.getValue() == null ? null : txAccountFilter.getValue().id(),
            txRootCategoryFilter.getValue() == null ? null : txRootCategoryFilter.getValue().id(),
            txSubCategoryFilter.getValue() == null ? null : txSubCategoryFilter.getValue().id(),
            txFromDate.getValue(),
            txToDate.getValue(),
            darkTheme,
            accountRepo,
            categoryRepo,
            refreshBalances
        );

        try {
            txAccountFilter.getItems().add(null);
            txAccountFilter.getItems().addAll(accountRepo.list(userUid));
            txAccountFilter.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        txAccountFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        try {
            txRootCategoryFilter.getItems().add(null);
            txRootCategoryFilter.getItems().addAll(categoryRepo.listRoots(userUid));
            txRootCategoryFilter.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        txRootCategoryFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las categorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        txSubCategoryFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las subcategorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshTxSubcats = () -> {
            CategoryRepository.Category root = txRootCategoryFilter.getValue();
            txSubCategoryFilter.getItems().clear();
            txSubCategoryFilter.getItems().add(null);
            if (root == null) {
                txSubCategoryFilter.setDisable(true);
                txSubCategoryFilter.getSelectionModel().selectFirst();
                return;
            }
            txSubCategoryFilter.setDisable(false);
            try {
                txSubCategoryFilter.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
            } catch (Exception ignored) {
            }
            txSubCategoryFilter.getSelectionModel().selectFirst();
        };
        txRootCategoryFilter.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshTxSubcats.run());
        refreshTxSubcats.run();

        txAccountFilter.valueProperty().addListener((obs, o, n) -> refreshTx.run());
        txRootCategoryFilter.valueProperty().addListener((obs, o, n) -> refreshTx.run());
        txSubCategoryFilter.valueProperty().addListener((obs, o, n) -> refreshTx.run());
        txFromDate.valueProperty().addListener((obs, o, n) -> refreshTx.run());
        txToDate.valueProperty().addListener((obs, o, n) -> refreshTx.run());

        Label fAccount = new Label("Cuenta");
        fAccount.getStyleClass().add("account-name");
        Label fCat = new Label("Categoría");
        fCat.getStyleClass().add("account-name");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("account-name");
        Label fFrom = new Label("Desde");
        fFrom.getStyleClass().add("account-name");
        Label fTo = new Label("Hasta");
        fTo.getStyleClass().add("account-name");

        txAccountFilter.setPrefWidth(200);
        txRootCategoryFilter.setPrefWidth(240);
        txSubCategoryFilter.setPrefWidth(260);
        txFromDate.setPrefWidth(150);
        txToDate.setPrefWidth(150);

        HBox pAccount = new HBox(8, fAccount, txAccountFilter);
        pAccount.setAlignment(Pos.CENTER_LEFT);
        HBox pCat = new HBox(8, fCat, txRootCategoryFilter);
        pCat.setAlignment(Pos.CENTER_LEFT);
        HBox pSub = new HBox(8, fSub, txSubCategoryFilter);
        pSub.setAlignment(Pos.CENTER_LEFT);
        HBox pFrom = new HBox(8, fFrom, txFromDate);
        pFrom.setAlignment(Pos.CENTER_LEFT);
        HBox pTo = new HBox(8, fTo, txToDate);
        pTo.setAlignment(Pos.CENTER_LEFT);

        FlowPane filtersRow = new FlowPane(12, 10);
        filtersRow.getChildren().addAll(pAccount, pCat, pSub, pFrom, pTo);
        VBox filtersCard = new VBox(10, filtersRow);
        filtersCard.getStyleClass().addAll("card", "content-card");
        filtersCard.setPadding(new Insets(10));

        Button newTx = new Button("Nueva transacción");
        newTx.getStyleClass().add("btn-primary");
        newTx.setOnAction(e -> {
            Optional<NewTransaction> t = showCreateTransactionDialog(userUid, accountRepo, categoryRepo, darkTheme);
            if (t.isEmpty()) {
                return;
            }
            try {
                NewTransaction tx = t.get();
                String txId = txRepo.create(userUid, tx.accountId(), tx.categoryId(), tx.kind(), tx.amountCents(), tx.occurredAtEpochSec(), tx.note());
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    sync.syncTransaction(session, txRepo.getForSyncById(userUid, txId));
                } catch (Exception ignored) {
                }
                refreshBalances.run();
                refreshTx.run();
            } catch (Exception ignored) {
            }
        });

        VBox body = new VBox(12, newTx, filtersCard, txScroll);
        body.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(body);

        Runnable doRefreshNow = () -> {
            new Thread(() -> {
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    List<TransactionRepository.TransactionSyncRow> remote = sync.pullTransactions(session);

                    Set<String> remoteIds = new HashSet<>();
                    for (TransactionRepository.TransactionSyncRow t : remote) {
                        remoteIds.add(t.id());
                        if (accountRepo.getById(userUid, t.accountId()) == null) {
                            continue;
                        }
                        if (categoryRepo.getById(userUid, t.categoryId()) == null) {
                            continue;
                        }
                        try {
                            txRepo.upsertFromRemote(userUid, t);
                        } catch (Exception ignored) {
                        }
                    }

                    List<TransactionRepository.TransactionSyncRow> localAll = txRepo.listAllForSync(userUid);
                    for (TransactionRepository.TransactionSyncRow t : localAll) {
                        if (remoteIds.contains(t.id())) {
                            continue;
                        }
                        try {
                            txRepo.delete(userUid, t.id());
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }

                Platform.runLater(() -> {
                    refreshBalances.run();
                    refreshTx.run();
                });
            }).start();
        };

        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F5) {
                doRefreshNow.run();
                ev.consume();
            }
        });

        refreshTx.run();
        dialog.showAndWait();
    }

    private static void showChartsDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Gráficos");
        UiDialogs.applyAppTheme(dialog, darkTheme);
        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(720);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);

                        final double normalW = Math.min(1200, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(820, bounds.getHeight() * 0.90);
                        s.maximizedProperty().addListener((o, oldV, newV) -> {
                            if (Boolean.TRUE.equals(newV)) {
                                return;
                            }
                            try {
                                s.setWidth(normalW);
                                s.setHeight(normalH);
                                s.centerOnScreen();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Gráficos");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Visualiza ingresos y gastos por categoría o subcategoría.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(96);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        ChoiceBox<Integer> year = new ChoiceBox<>();
        ChoiceBox<String> kind = new ChoiceBox<>();
        ChoiceBox<String> view = new ChoiceBox<>();
        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();
        ChoiceBox<String> month = new ChoiceBox<>();
        ChoiceBox<String> chartType = new ChoiceBox<>();

        int currentYear = LocalDate.now().getYear();
        for (int y = currentYear; y >= currentYear - 5; y--) {
            year.getItems().add(y);
        }
        year.getSelectionModel().selectFirst();

        kind.getItems().addAll("Gastos", "Ingresos");
        kind.getSelectionModel().select("Gastos");

        view.getItems().addAll("Categorías", "Subcategorías");
        view.getSelectionModel().select("Categorías");

        month.getItems().addAll(
            "TOTAL",
            "ENERO",
            "FEBRERO",
            "MARZO",
            "ABRIL",
            "MAYO",
            "JUNIO",
            "JULIO",
            "AGOSTO",
            "SEPTIEMBRE",
            "OCTUBRE",
            "NOVIEMBRE",
            "DICIEMBRE"
        );
        month.getSelectionModel().select("TOTAL");

        chartType.getItems().addAll("Torta", "Barras");
        chartType.getSelectionModel().select("Barras");

        try {
            account.getItems().add(null);
            account.getItems().addAll(accountRepo.list(userUid));
            account.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        try {
            rootCategory.getItems().add(null);
            rootCategory.getItems().addAll(categoryRepo.listRoots(userUid));
            rootCategory.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las categorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las subcategorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshSubcatsCharts = () -> {
            subCategory.getItems().clear();
            subCategory.getItems().add(null);

            boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
            CategoryRepository.Category root = rootCategory.getValue();
            if (!bySub || root == null) {
                subCategory.setDisable(true);
                subCategory.getSelectionModel().selectFirst();
                return;
            }
            subCategory.setDisable(false);
            try {
                subCategory.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
            } catch (Exception ignored) {
            }
            subCategory.getSelectionModel().selectFirst();
        };
        refreshSubcatsCharts.run();

        Label fYear = new Label("Año");
        fYear.getStyleClass().add("text-secondary");
        Label fKind = new Label("Tipo");
        fKind.getStyleClass().add("text-secondary");
        Label fView = new Label("Vista");
        fView.getStyleClass().add("text-secondary");
        Label fRoot = new Label("Categoría");
        fRoot.getStyleClass().add("text-secondary");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("text-secondary");
        Label fAccount = new Label("Cuenta");
        fAccount.getStyleClass().add("text-secondary");
        Label fMonth = new Label("Mes");
        fMonth.getStyleClass().add("text-secondary");
        Label fChart = new Label("Gráfico");
        fChart.getStyleClass().add("text-secondary");

        HBox pYear = new HBox(8, fYear, year);
        pYear.setAlignment(Pos.CENTER_LEFT);
        HBox pKind = new HBox(8, fKind, kind);
        pKind.setAlignment(Pos.CENTER_LEFT);
        HBox pView = new HBox(8, fView, view);
        pView.setAlignment(Pos.CENTER_LEFT);
        HBox pRoot = new HBox(8, fRoot, rootCategory);
        pRoot.setAlignment(Pos.CENTER_LEFT);
        HBox pSub = new HBox(8, fSub, subCategory);
        pSub.setAlignment(Pos.CENTER_LEFT);
        HBox pAccount = new HBox(8, fAccount, account);
        pAccount.setAlignment(Pos.CENTER_LEFT);
        HBox pMonth = new HBox(8, fMonth, month);
        pMonth.setAlignment(Pos.CENTER_LEFT);
        HBox pChart = new HBox(8, fChart, chartType);
        pChart.setAlignment(Pos.CENTER_LEFT);

        FlowPane filtersRow = new FlowPane(12, 10);
        filtersRow.getChildren().addAll(pYear, pKind, pView, pRoot, pSub, pAccount, pMonth, pChart);
        VBox filtersCard = new VBox(10, filtersRow);
        filtersCard.getStyleClass().addAll("card", "content-card");
        filtersCard.setPadding(new Insets(10));

        Button toggleFilters = new Button("Ocultar filtros");
        toggleFilters.getStyleClass().add("btn-secondary");
        toggleFilters.setOnAction(e -> {
            boolean show = !filtersCard.isVisible();
            filtersCard.setVisible(show);
            filtersCard.setManaged(show);
            toggleFilters.setText(show ? "Ocultar filtros" : "Mostrar filtros");
        });

        BorderPane chartPane = new BorderPane();
        chartPane.getStyleClass().addAll("card", "content-card");
        chartPane.setPadding(new Insets(10));
        VBox.setVgrow(chartPane, Priority.ALWAYS);

        Runnable refreshChart = () -> {
            String currencyCode = "COP";
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            boolean bySubcategory = "Subcategorías".equalsIgnoreCase(view.getValue());
            AccountRepository.Account a = account.getValue();
            String accountId = a == null ? null : a.id();
            int y = year.getValue() == null ? currentYear : year.getValue();

            int monthIdx;
            String mLabel = month.getValue();
            if (mLabel == null || "TOTAL".equalsIgnoreCase(mLabel)) {
                monthIdx = 0;
            } else {
                monthIdx = Math.max(1, month.getSelectionModel().getSelectedIndex());
            }

            CategoryRepository.Category rootFilter = rootCategory.getValue();
            CategoryRepository.Category subFilter = subCategory.getValue();

            Map<String, String> nameById = new HashMap<>();
            Map<String, long[]> centsById = new HashMap<>();

            if (bySubcategory) {
                try {
                    List<CategoryRepository.Category> roots = categoryRepo.listRoots(userUid);
                    for (CategoryRepository.Category r : roots) {
                        if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                            continue;
                        }
                        try {
                            for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, r.id())) {
                                nameById.put(c.id(), c.name());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }

                try {
                    List<TransactionRepository.MonthlyCategoryDetailTotal> rows = txRepo.listMonthlyTotalsBySubcategory(userUid, accountId, y, k);
                    for (TransactionRepository.MonthlyCategoryDetailTotal row : rows) {
                        if (rootFilter != null && !rootFilter.id().equals(row.rootCategoryId())) {
                            continue;
                        }
                        if (subFilter != null && !subFilter.id().equals(row.categoryId())) {
                            continue;
                        }
                        long[] months = centsById.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                        int m = row.month();
                        if (m >= 1 && m <= 12) {
                            months[m] = row.totalAmountCents();
                        }
                        nameById.putIfAbsent(row.categoryId(), row.categoryName());
                    }
                } catch (Exception ignored) {
                }
            } else {
                try {
                    List<CategoryRepository.Category> roots = categoryRepo.listRoots(userUid);
                    for (CategoryRepository.Category r : roots) {
                        if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                            continue;
                        }
                        nameById.put(r.id(), r.name());
                    }
                } catch (Exception ignored) {
                }

                try {
                    List<TransactionRepository.MonthlyCategoryTotal> rows = txRepo.listMonthlyTotalsByRootCategory(userUid, accountId, y, k);
                    for (TransactionRepository.MonthlyCategoryTotal row : rows) {
                        if (rootFilter != null && !rootFilter.id().equals(row.rootCategoryId())) {
                            continue;
                        }
                        long[] months = centsById.computeIfAbsent(row.rootCategoryId(), __ -> new long[13]);
                        int m = row.month();
                        if (m >= 1 && m <= 12) {
                            months[m] = row.totalAmountCents();
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            List<Map.Entry<String, Long>> items = new ArrayList<>();
            for (Map.Entry<String, long[]> e : centsById.entrySet()) {
                long[] months = e.getValue();
                long value;
                if (monthIdx == 0) {
                    long t = 0;
                    for (int m = 1; m <= 12; m++) {
                        t += months[m];
                    }
                    value = t;
                } else {
                    value = (monthIdx >= 1 && monthIdx <= 12) ? months[monthIdx] : 0;
                }
                if (value == 0) {
                    continue;
                }
                items.add(Map.entry(e.getKey(), value));
            }
            items.sort((a1, a2) -> Long.compare(Math.abs(a2.getValue()), Math.abs(a1.getValue())));

            String ct = chartType.getValue();
            boolean pie = "Torta".equalsIgnoreCase(ct);
            if (pie) {
                ObservableList<PieChart.Data> data = FXCollections.observableArrayList();
                for (var it : items) {
                    String name = nameById.getOrDefault(it.getKey(), it.getKey());
                    data.add(new PieChart.Data(name, Math.abs(it.getValue()) / 100.0));
                }
                PieChart chart = new PieChart(data);
                chart.setLegendVisible(true);
                chart.setLabelsVisible(true);
                chartPane.setCenter(chart);

                Platform.runLater(() -> {
                    for (PieChart.Data d : data) {
                        try {
                            String label = d.getName();
                            long cents = 0;
                            for (var it : items) {
                                if (label.equals(nameById.getOrDefault(it.getKey(), it.getKey()))) {
                                    cents = it.getValue();
                                    break;
                                }
                            }
                            Tooltip.install(d.getNode(), new Tooltip(label + ": " + formatMoney(cents, currencyCode)));
                        } catch (Exception ignored) {
                        }
                    }
                });
                return;
            }

            CategoryAxis xAxis = new CategoryAxis();
            NumberAxis yAxis = new NumberAxis();
            yAxis.setForceZeroInRange(true);
            BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
            chart.setLegendVisible(false);
            chart.setAnimated(false);
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            for (var it : items) {
                String name = nameById.getOrDefault(it.getKey(), it.getKey());
                double v = Math.abs(it.getValue()) / 100.0;
                XYChart.Data<String, Number> d = new XYChart.Data<>(name, v);
                d.setExtraValue(it.getKey());
                series.getData().add(d);
            }
            chart.getData().setAll(series);
            chartPane.setCenter(chart);

            Platform.runLater(() -> {
                for (XYChart.Data<String, Number> d : series.getData()) {
                    try {
                        Object extra = d.getExtraValue();
                        String id = extra == null ? null : String.valueOf(extra);
                        String color = colorFromKey(id == null ? String.valueOf(d.getXValue()) : id);
                        if (d.getNode() != null) {
                            d.getNode().setStyle("-fx-bar-fill: " + color + ";");
                        } else {
                            d.nodeProperty().addListener((o, oldN, newN) -> {
                                if (newN != null) {
                                    newN.setStyle("-fx-bar-fill: " + color + ";");
                                }
                            });
                        }

                        String label = String.valueOf(d.getXValue());
                        long cents = 0;
                        for (var it : items) {
                            if (label.equals(nameById.getOrDefault(it.getKey(), it.getKey()))) {
                                cents = it.getValue();
                                break;
                            }
                        }
                        Tooltip.install(d.getNode(), new Tooltip(label + ": " + formatMoney(cents, currencyCode)));
                    } catch (Exception ignored) {
                    }
                }
            });
        };

        year.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        kind.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        view.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsCharts.run();
            refreshChart.run();
        });
        account.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        rootCategory.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsCharts.run();
            refreshChart.run();
        });
        subCategory.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        month.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        chartType.valueProperty().addListener((obs, o, n) -> refreshChart.run());

        HBox actionsRow = new HBox(10, toggleFilters);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(12, actionsRow, filtersCard, chartPane);
        body.setPadding(new Insets(10));
        VBox.setVgrow(chartPane, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);

        refreshChart.run();
        dialog.showAndWait();
    }

    private static void showSummaryDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Resumen");
        UiDialogs.applyAppTheme(dialog, darkTheme);
        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(720);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);

                        final double normalW = Math.min(1200, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(820, bounds.getHeight() * 0.90);
                        s.maximizedProperty().addListener((o, oldV, newV) -> {
                            if (Boolean.TRUE.equals(newV)) {
                                return;
                            }
                            try {
                                s.setWidth(normalW);
                                s.setHeight(normalH);
                                s.centerOnScreen();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Resumen mensual");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Ingresos y gastos agrupados por categoría, por mes.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(96);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        ChoiceBox<Integer> year = new ChoiceBox<>();
        ChoiceBox<String> kind = new ChoiceBox<>();
        ChoiceBox<String> view = new ChoiceBox<>();
        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();

        int currentYear = LocalDate.now().getYear();
        for (int y = currentYear; y >= currentYear - 5; y--) {
            year.getItems().add(y);
        }
        year.getSelectionModel().selectFirst();

        kind.getItems().addAll("Gastos", "Ingresos");
        kind.getSelectionModel().select("Gastos");

        view.getItems().addAll("Categorías", "Subcategorías");
        view.getSelectionModel().select("Categorías");

        try {
            account.getItems().add(null);
            account.getItems().addAll(accountRepo.list(userUid));
            account.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        try {
            rootCategory.getItems().add(null);
            rootCategory.getItems().addAll(categoryRepo.listRoots(userUid));
            rootCategory.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las categorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las subcategorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshSubcatsSummary = () -> {
            boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
            CategoryRepository.Category root = rootCategory.getValue();

            subCategory.getItems().clear();
            subCategory.getItems().add(null);

            if (!bySub || root == null) {
                subCategory.setDisable(true);
                subCategory.getSelectionModel().selectFirst();
                return;
            }

            subCategory.setDisable(false);
            try {
                subCategory.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
            } catch (Exception ignored) {
            }
            subCategory.getSelectionModel().selectFirst();
        };
        refreshSubcatsSummary.run();

        Runnable refreshAccountsForSummary = () -> {
            try {
                AccountRepository.Account selected = account.getValue();

                account.getItems().clear();
                account.getItems().add(null);

                boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
                CategoryRepository.Category sub = subCategory.getValue();
                Integer y = year.getValue();
                String kindLabel = kind.getValue();
                String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";

                List<AccountRepository.Account> allAccounts;
                try {
                    allAccounts = accountRepo.list(userUid);
                } catch (Exception ignored) {
                    allAccounts = List.of();
                }

                if (bySub && sub != null) {
                    List<String> ids;
                    try {
                        ids = txRepo.listAccountIdsUsedInCategory(userUid, y == null ? currentYear : y, k, sub.id());
                    } catch (Exception ignored) {
                        ids = List.of();
                    }

                    Set<String> idSet = new HashSet<>(ids);
                    for (AccountRepository.Account a : allAccounts) {
                        if (a != null && idSet.contains(a.id())) {
                            account.getItems().add(a);
                        }
                    }
                } else {
                    account.getItems().addAll(allAccounts);
                }

                if (selected == null) {
                    account.getSelectionModel().selectFirst();
                    return;
                }
                for (AccountRepository.Account a : account.getItems()) {
                    if (a != null && selected.id().equals(a.id())) {
                        account.getSelectionModel().select(a);
                        return;
                    }
                }
                account.getSelectionModel().selectFirst();
            } catch (Exception ignored) {
            }
        };

        Label fYear = new Label("Año");
        fYear.getStyleClass().add("account-name");
        Label fKind = new Label("Tipo");
        fKind.getStyleClass().add("account-name");
        Label fView = new Label("Vista");
        fView.getStyleClass().add("account-name");
        Label fRoot = new Label("Categoría");
        fRoot.getStyleClass().add("account-name");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("account-name");
        Label fAccount = new Label("Cuenta");
        fAccount.getStyleClass().add("account-name");

        year.setPrefWidth(120);
        kind.setPrefWidth(160);
        view.setPrefWidth(180);
        rootCategory.setPrefWidth(240);
        subCategory.setPrefWidth(260);
        account.setPrefWidth(260);

        HBox pYear = new HBox(8, fYear, year);
        pYear.setAlignment(Pos.CENTER_LEFT);
        HBox pKind = new HBox(8, fKind, kind);
        pKind.setAlignment(Pos.CENTER_LEFT);
        HBox pView = new HBox(8, fView, view);
        pView.setAlignment(Pos.CENTER_LEFT);
        HBox pRoot = new HBox(8, fRoot, rootCategory);
        pRoot.setAlignment(Pos.CENTER_LEFT);
        HBox pSub = new HBox(8, fSub, subCategory);
        pSub.setAlignment(Pos.CENTER_LEFT);
        HBox pAccount = new HBox(8, fAccount, account);
        pAccount.setAlignment(Pos.CENTER_LEFT);

        FlowPane filtersRow = new FlowPane(12, 10);
        filtersRow.getChildren().addAll(pYear, pKind, pView, pRoot, pSub, pAccount);
        VBox filtersCard = new VBox(10, filtersRow);
        filtersCard.getStyleClass().addAll("card", "content-card");
        filtersCard.setPadding(new Insets(10));

        Button toggleFilters = new Button("Ocultar filtros");
        toggleFilters.getStyleClass().add("btn-secondary");
        toggleFilters.setOnAction(e -> {
            boolean show = !filtersCard.isVisible();
            filtersCard.setVisible(show);
            filtersCard.setManaged(show);
            toggleFilters.setText(show ? "Ocultar filtros" : "Mostrar filtros");
        });

        GridPane fixedTable = new GridPane();
        fixedTable.setHgap(10);
        fixedTable.setVgap(8);
        fixedTable.setPadding(new Insets(10));
        fixedTable.setMinWidth(Region.USE_PREF_SIZE);
        fixedTable.getStyleClass().add("summary-table");

        try {
            javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
            c0.setMinWidth(240);
            c0.setPrefWidth(320);
            c0.setHgrow(Priority.ALWAYS);
            javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
            c1.setMinWidth(120);
            c1.setPrefWidth(130);
            c1.setHgrow(Priority.NEVER);
            fixedTable.getColumnConstraints().setAll(c0, c1);
        } catch (Exception ignored) {
        }

        GridPane monthsTable = new GridPane();
        monthsTable.setHgap(10);
        monthsTable.setVgap(8);
        monthsTable.setPadding(new Insets(10));
        monthsTable.setMinWidth(Region.USE_PREF_SIZE);
        monthsTable.getStyleClass().add("summary-table");

        ScrollPane fixedScroll = new ScrollPane(fixedTable);
        fixedScroll.setFitToWidth(true);
        fixedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fixedScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fixedScroll.setPannable(false);
        fixedScroll.setMinViewportWidth(340);

        ScrollPane monthsScroll = new ScrollPane(monthsTable);
        monthsScroll.setFitToHeight(true);
        monthsScroll.setFitToWidth(false);
        monthsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setPannable(false);

        fixedScroll.vvalueProperty().bindBidirectional(monthsScroll.vvalueProperty());

        HBox tablesRow = new HBox(0, fixedScroll, monthsScroll);
        HBox.setHgrow(monthsScroll, Priority.ALWAYS);
        tablesRow.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(tablesRow, Priority.ALWAYS);

        AtomicReference<List<List<String>>> exportRowsRef = new AtomicReference<>(List.of());
        Map<String, Boolean> expandedAccountsBySubId = new HashMap<>();

        Runnable refreshSummary = () -> {
            fixedTable.getChildren().clear();
            monthsTable.getChildren().clear();

            Integer y = year.getValue();
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            boolean bySubcategory = "Subcategorías".equalsIgnoreCase(view.getValue());
            int currentMonth = LocalDate.now().getMonthValue();
            AccountRepository.Account a = account.getValue();
            String accountId = a == null ? null : a.id();
            String currencyCode = "COP";

            int monthsElapsed;
            int selectedYear = y == null ? currentYear : y;
            if (selectedYear == currentYear) {
                monthsElapsed = Math.max(1, Math.min(12, currentMonth - 1));
            } else {
                monthsElapsed = 12;
            }

            List<List<String>> exportRows = new ArrayList<>();

            CategoryRepository.Category rootFilter = rootCategory.getValue();
            CategoryRepository.Category subFilter = subCategory.getValue();

            List<CategoryRepository.Category> roots;
            try {
                roots = categoryRepo.listRoots(userUid);
            } catch (Exception ignored) {
                roots = List.of();
            }

            if (bySubcategory) {
                Map<String, Map<String, String>> subNameByRoot = new HashMap<>();
                Map<String, Map<String, long[]>> byRootSub = new HashMap<>();
                Map<String, Map<String, Map<String, long[]>>> byRootSubAccount = new HashMap<>();
                Map<String, String> accountNameById = new HashMap<>();
                for (CategoryRepository.Category r : roots) {
                    byRootSub.put(r.id(), new HashMap<>());
                    subNameByRoot.put(r.id(), new HashMap<>());
                    try {
                        List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, r.id());
                        for (CategoryRepository.Category c : children) {
                            subNameByRoot.get(r.id()).put(c.id(), c.name());
                            byRootSub.get(r.id()).put(c.id(), new long[13]);
                            byRootSubAccount.computeIfAbsent(r.id(), __ -> new HashMap<>()).put(c.id(), new HashMap<>());
                        }
                    } catch (Exception ignored) {
                    }
                    subNameByRoot.get(r.id()).put(r.id() + ":NONE", "(Sin subcategoría)");
                    byRootSub.get(r.id()).put(r.id() + ":NONE", new long[13]);
                    byRootSubAccount.computeIfAbsent(r.id(), __ -> new HashMap<>()).put(r.id() + ":NONE", new HashMap<>());
                }

                try {
                    if (accountId == null) {
                        List<TransactionRepository.MonthlyCategoryDetailAccountTotal> rows = txRepo.listMonthlyTotalsBySubcategoryAndAccount(userUid, selectedYear, k);
                        for (TransactionRepository.MonthlyCategoryDetailAccountTotal row : rows) {
                            Map<String, long[]> subs = byRootSub.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            long[] months = subs.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                months[m] += row.totalAmountCents();
                            }

                            Map<String, Map<String, long[]>> subsByAcc = byRootSubAccount
                                .computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            Map<String, long[]> accs = subsByAcc.computeIfAbsent(row.categoryId(), __ -> new HashMap<>());
                            long[] accMonths = accs.computeIfAbsent(row.accountId(), __ -> new long[13]);
                            if (m >= 1 && m <= 12) {
                                accMonths[m] += row.totalAmountCents();
                            }

                            subNameByRoot.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>())
                                .putIfAbsent(row.categoryId(), row.categoryName());
                            if (row.accountId() != null && row.accountName() != null) {
                                accountNameById.putIfAbsent(row.accountId(), row.accountName());
                            }
                        }
                    } else {
                        List<TransactionRepository.MonthlyCategoryDetailTotal> rows = txRepo.listMonthlyTotalsBySubcategory(userUid, accountId, selectedYear, k);
                        for (TransactionRepository.MonthlyCategoryDetailTotal row : rows) {
                            Map<String, long[]> subs = byRootSub.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            long[] months = subs.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                months[m] = row.totalAmountCents();
                            }
                            subNameByRoot.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>())
                                .putIfAbsent(row.categoryId(), row.categoryName());
                        }
                    }
                } catch (Exception ignored) {
                }

                String[] monthNames = new String[] {
                    "DESCRIPCIÓN",
                    "ENERO",
                    "FEBRERO",
                    "MARZO",
                    "ABRIL",
                    "MAYO",
                    "JUNIO",
                    "JULIO",
                    "AGOSTO",
                    "SEPTIEMBRE",
                    "OCTUBRE",
                    "NOVIEMBRE",
                    "DICIEMBRE",
                    "TOTAL",
                    "PROMEDIO"
                };

                exportRows.add(List.of(monthNames));

                for (int col = 0; col < monthNames.length; col++) {
                    Label h = new Label(monthNames[col]);
                    h.getStyleClass().add("account-name");
                    h.getStyleClass().add("summary-header-cell");
                    if (col == currentMonth) {
                        h.getStyleClass().add("summary-current-month");
                    }
                    if (col == 0) {
                        fixedTable.add(h, 0, 0);
                    } else if (col >= 1 && col <= 12) {
                        monthsTable.add(h, col - 1, 0);
                    } else if (col == 13) {
                        fixedTable.add(h, 1, 0);
                    } else if (col == 14) {
                        monthsTable.add(h, 12, 0);
                    }
                }

                long[] totalByMonth = new long[13];
                int rowIdx = 1;
                long grandTotal = 0;
                for (CategoryRepository.Category r : roots) {
                    if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                        continue;
                    }

                    Label rootName = new Label(r.name());
                    rootName.getStyleClass().add("account-name");
                    rootName.getStyleClass().add("summary-root-name");
                    String zebra = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                    rootName.getStyleClass().add(zebra);
                    fixedTable.add(rootName, 0, rowIdx);
                    for (int m = 1; m <= 12; m++) {
                        Label v = new Label(" ");
                        v.getStyleClass().add(zebra);
                        if (m == currentMonth) {
                            v.getStyleClass().add("summary-current-month");
                        }
                        monthsTable.add(v, m - 1, rowIdx);
                    }
                    Label rootTotalCell = new Label(" ");
                    rootTotalCell.getStyleClass().add(zebra);
                    rootTotalCell.getStyleClass().add("summary-total-col");
                    fixedTable.add(rootTotalCell, 1, rowIdx);
                    Label rootAvgCell = new Label(" ");
                    rootAvgCell.getStyleClass().add(zebra);
                    rootAvgCell.getStyleClass().add("summary-avg-col");
                    monthsTable.add(rootAvgCell, 12, rowIdx);

                    rowIdx++;

                    List<String> keys = new ArrayList<>(subNameByRoot.getOrDefault(r.id(), Map.of()).keySet());
                    keys.sort((a1, a2) -> {
                        String n1 = subNameByRoot.get(r.id()).getOrDefault(a1, a1);
                        String n2 = subNameByRoot.get(r.id()).getOrDefault(a2, a2);
                        return n1.compareToIgnoreCase(n2);
                    });

                    for (String subId : keys) {
                        if (subFilter != null && !subFilter.id().equals(subId)) {
                            continue;
                        }
                        long[] months = byRootSub.get(r.id()).getOrDefault(subId, new long[13]);
                        boolean any = false;
                        for (int m = 1; m <= 12; m++) {
                            if (months[m] != 0) {
                                any = true;
                                break;
                            }
                        }
                        if (!any) {
                            continue;
                        }

                        String subLabel = subNameByRoot.get(r.id()).getOrDefault(subId, subId);
                        boolean canToggleAccounts = accountId == null;
                        boolean expanded = expandedAccountsBySubId.getOrDefault(subId, false);
                        String chevron = canToggleAccounts ? (expanded ? "▼" : "▶") : "";
                        Label name = new Label("  - " + (canToggleAccounts ? (chevron + " ") : "") + subLabel);
                        name.getStyleClass().add("text-secondary");
                        name.getStyleClass().add("summary-sub-name");
                        name.setMaxWidth(320);
                        name.setTextOverrun(OverrunStyle.ELLIPSIS);
                        Tooltip.install(name, new Tooltip(subLabel));
                        String zebraSub = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                        name.getStyleClass().add(zebraSub);
                        fixedTable.add(name, 0, rowIdx);

                        long rowTotal = 0;
                        for (int m = 1; m <= 12; m++) {
                            totalByMonth[m] += months[m];
                            rowTotal += months[m];
                            Label v = new Label(formatMoney(months[m], currencyCode));
                            v.setMinWidth(100);
                            v.setAlignment(Pos.CENTER_RIGHT);
                            v.getStyleClass().add("summary-amount-cell");
                            v.getStyleClass().add(zebraSub);
                            if (m == currentMonth) {
                                v.getStyleClass().add("summary-current-month");
                            }
                            monthsTable.add(v, m - 1, rowIdx);
                        }

                        grandTotal += rowTotal;
                        Label totalCell = new Label(formatMoney(rowTotal, currencyCode));
                        totalCell.setMinWidth(100);
                        totalCell.setAlignment(Pos.CENTER_RIGHT);
                        totalCell.getStyleClass().add("summary-amount-cell");
                        totalCell.getStyleClass().add("summary-total-col");
                        totalCell.getStyleClass().add(zebraSub);
                        fixedTable.add(totalCell, 1, rowIdx);

                        long avgCents = monthsElapsed <= 0 ? 0 : (rowTotal / monthsElapsed);
                        Label avgCell = new Label(formatMoney(avgCents, currencyCode));
                        avgCell.setMinWidth(100);
                        avgCell.setAlignment(Pos.CENTER_RIGHT);
                        avgCell.getStyleClass().add("summary-amount-cell");
                        avgCell.getStyleClass().add("summary-avg-col");
                        avgCell.getStyleClass().add(zebraSub);
                        monthsTable.add(avgCell, 12, rowIdx);

                        List<String> exportRow = new ArrayList<>();
                        exportRow.add(subNameByRoot.get(r.id()).getOrDefault(subId, subId));
                        for (int m = 1; m <= 12; m++) {
                            exportRow.add(formatMoney(months[m], currencyCode));
                        }
                        exportRow.add(formatMoney(rowTotal, currencyCode));
                        exportRow.add(formatMoney(avgCents, currencyCode));
                        exportRows.add(exportRow);
                        rowIdx++;

                        if (accountId == null) {
                            List<Node> accountRowNodes = new ArrayList<>();
                            Map<String, long[]> accs = byRootSubAccount
                                .getOrDefault(r.id(), Map.of())
                                .getOrDefault(subId, Map.of());
                            List<String> accountIds = new ArrayList<>(accs.keySet());
                            accountIds.sort((a1, a2) -> {
                                String n1 = accountNameById.getOrDefault(a1, a1);
                                String n2 = accountNameById.getOrDefault(a2, a2);
                                return n1.compareToIgnoreCase(n2);
                            });
                            for (String accId : accountIds) {
                                long[] am = accs.getOrDefault(accId, new long[13]);
                                boolean anyAcc = false;
                                for (int m = 1; m <= 12; m++) {
                                    if (am[m] != 0) {
                                        anyAcc = true;
                                        break;
                                    }
                                }
                                if (!anyAcc) {
                                    continue;
                                }

                                String accName = accountNameById.getOrDefault(accId, accId);
                                Label accLabel = new Label("      • " + accName);
                                accLabel.getStyleClass().add("text-secondary");
                                accLabel.getStyleClass().add("summary-sub-name");
                                accLabel.setMaxWidth(320);
                                accLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                                Tooltip.install(accLabel, new Tooltip(accName));
                                String zebraAcc = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                                accLabel.getStyleClass().add(zebraAcc);
                                fixedTable.add(accLabel, 0, rowIdx);
                                accountRowNodes.add(accLabel);

                                long accTotal = 0;
                                for (int m = 1; m <= 12; m++) {
                                    accTotal += am[m];
                                    Label vv = new Label(formatMoney(am[m], currencyCode));
                                    vv.setMinWidth(100);
                                    vv.setAlignment(Pos.CENTER_RIGHT);
                                    vv.getStyleClass().add("summary-amount-cell");
                                    vv.getStyleClass().add(zebraAcc);
                                    if (m == currentMonth) {
                                        vv.getStyleClass().add("summary-current-month");
                                    }
                                    monthsTable.add(vv, m - 1, rowIdx);
                                    accountRowNodes.add(vv);
                                }

                                Label accTotalCell = new Label(formatMoney(accTotal, currencyCode));
                                accTotalCell.setMinWidth(100);
                                accTotalCell.setAlignment(Pos.CENTER_RIGHT);
                                accTotalCell.getStyleClass().add("summary-amount-cell");
                                accTotalCell.getStyleClass().add("summary-total-col");
                                accTotalCell.getStyleClass().add(zebraAcc);
                                fixedTable.add(accTotalCell, 1, rowIdx);
                                accountRowNodes.add(accTotalCell);

                                long accAvg = monthsElapsed <= 0 ? 0 : (accTotal / monthsElapsed);
                                Label accAvgCell = new Label(formatMoney(accAvg, currencyCode));
                                accAvgCell.setMinWidth(100);
                                accAvgCell.setAlignment(Pos.CENTER_RIGHT);
                                accAvgCell.getStyleClass().add("summary-amount-cell");
                                accAvgCell.getStyleClass().add("summary-avg-col");
                                accAvgCell.getStyleClass().add(zebraAcc);
                                monthsTable.add(accAvgCell, 12, rowIdx);
                                accountRowNodes.add(accAvgCell);

                                rowIdx++;
                            }

                            boolean initialExpanded = expandedAccountsBySubId.getOrDefault(subId, false);
                            for (Node n : accountRowNodes) {
                                n.setVisible(initialExpanded);
                                n.setManaged(initialExpanded);
                            }

                            if (canToggleAccounts) {
                                name.setOnMouseClicked(ev -> {
                                    boolean cur = expandedAccountsBySubId.getOrDefault(subId, false);
                                    boolean newV = !cur;
                                    expandedAccountsBySubId.put(subId, newV);
                                    String ch = newV ? "▼" : "▶";
                                    name.setText("  - " + ch + " " + subLabel);
                                    for (Node n : accountRowNodes) {
                                        n.setVisible(newV);
                                        n.setManaged(newV);
                                    }
                                });
                            }
                        }
                    }
                }

                Label totalName = new Label("TOTAL");
                totalName.getStyleClass().add("account-name");
                totalName.getStyleClass().add("summary-total-name");
                fixedTable.add(totalName, 0, rowIdx);
                for (int m = 1; m <= 12; m++) {
                    Label v = new Label(formatMoney(totalByMonth[m], currencyCode));
                    v.getStyleClass().add("account-name");
                    v.setMinWidth(100);
                    v.setAlignment(Pos.CENTER_RIGHT);
                    v.getStyleClass().add("summary-total-amount");
                    if (m == currentMonth) {
                        v.getStyleClass().add("summary-current-month");
                    }
                    monthsTable.add(v, m - 1, rowIdx);
                }

                Label grand = new Label(formatMoney(grandTotal, currencyCode));
                grand.getStyleClass().add("account-name");
                grand.setMinWidth(100);
                grand.setAlignment(Pos.CENTER_RIGHT);
                grand.getStyleClass().add("summary-total-amount");
                grand.getStyleClass().add("summary-total-col");
                fixedTable.add(grand, 1, rowIdx);

                long avgTotal = monthsElapsed <= 0 ? 0 : (grandTotal / monthsElapsed);
                Label grandAvg = new Label(formatMoney(avgTotal, currencyCode));
                grandAvg.getStyleClass().add("account-name");
                grandAvg.setMinWidth(100);
                grandAvg.setAlignment(Pos.CENTER_RIGHT);
                grandAvg.getStyleClass().add("summary-total-amount");
                grandAvg.getStyleClass().add("summary-avg-col");
                monthsTable.add(grandAvg, 12, rowIdx);

                List<String> totalExportRow = new ArrayList<>();
                totalExportRow.add("TOTAL");
                for (int m = 1; m <= 12; m++) {
                    totalExportRow.add(formatMoney(totalByMonth[m], currencyCode));
                }
                totalExportRow.add(formatMoney(grandTotal, currencyCode));
                totalExportRow.add(formatMoney(avgTotal, currencyCode));
                exportRows.add(totalExportRow);

                exportRowsRef.set(exportRows);

                applySummaryRowHover(fixedTable, monthsTable);

                return;
            }

            Map<String, long[]> byRoot = new HashMap<>();
            for (CategoryRepository.Category r : roots) {
                byRoot.put(r.id(), new long[13]);
            }

            try {
                List<TransactionRepository.MonthlyCategoryTotal> rows = txRepo.listMonthlyTotalsByRootCategory(userUid, accountId, y == null ? currentYear : y, k);
                for (TransactionRepository.MonthlyCategoryTotal row : rows) {
                    long[] months = byRoot.computeIfAbsent(row.rootCategoryId(), __ -> new long[13]);
                    int m = row.month();
                    if (m >= 1 && m <= 12) {
                        months[m] = row.totalAmountCents();
                    }
                }
            } catch (Exception ignored) {
            }

            String[] monthNames = new String[] {
                "DESCRIPCIÓN",
                "ENERO",
                "FEBRERO",
                "MARZO",
                "ABRIL",
                "MAYO",
                "JUNIO",
                "JULIO",
                "AGOSTO",
                "SEPTIEMBRE",
                "OCTUBRE",
                "NOVIEMBRE",
                "DICIEMBRE",
                "TOTAL",
                "PROMEDIO"
            };

            exportRows.add(List.of(monthNames));

            for (int col = 0; col < monthNames.length; col++) {
                Label h = new Label(monthNames[col]);
                h.getStyleClass().add("account-name");
                h.getStyleClass().add("summary-header-cell");
                if (col == currentMonth) {
                    h.getStyleClass().add("summary-current-month");
                }
                if (col == 0) {
                    fixedTable.add(h, 0, 0);
                } else if (col >= 1 && col <= 12) {
                    monthsTable.add(h, col - 1, 0);
                } else if (col == 13) {
                    fixedTable.add(h, 1, 0);
                } else if (col == 14) {
                    monthsTable.add(h, 12, 0);
                }
            }

            long[] totalByMonth = new long[13];
            int rowIdx = 1;
            long grandTotal = 0;
            for (CategoryRepository.Category r : roots) {
                if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                    continue;
                }
                long[] months = byRoot.getOrDefault(r.id(), new long[13]);

                Label name = new Label(r.name());
                name.getStyleClass().add("account-name");
                name.getStyleClass().add("summary-root-name");
                String zebra = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                name.getStyleClass().add(zebra);
                fixedTable.add(name, 0, rowIdx);

                long rowTotal = 0;
                for (int m = 1; m <= 12; m++) {
                    totalByMonth[m] += months[m];
                    rowTotal += months[m];
                    Label v = new Label(formatMoney(months[m], currencyCode));
                    v.setMinWidth(100);
                    v.setAlignment(Pos.CENTER_RIGHT);
                    v.getStyleClass().add("summary-amount-cell");
                    v.getStyleClass().add(zebra);
                    if (m == currentMonth) {
                        v.getStyleClass().add("summary-current-month");
                    }
                    monthsTable.add(v, m - 1, rowIdx);
                }

                grandTotal += rowTotal;
                Label totalCell = new Label(formatMoney(rowTotal, currencyCode));
                totalCell.setMinWidth(100);
                totalCell.setAlignment(Pos.CENTER_RIGHT);
                totalCell.getStyleClass().add("summary-amount-cell");
                totalCell.getStyleClass().add("summary-total-col");
                totalCell.getStyleClass().add(zebra);
                fixedTable.add(totalCell, 1, rowIdx);

                long avgCents = monthsElapsed <= 0 ? 0 : (rowTotal / monthsElapsed);
                Label avgCell = new Label(formatMoney(avgCents, currencyCode));
                avgCell.setMinWidth(100);
                avgCell.setAlignment(Pos.CENTER_RIGHT);
                avgCell.getStyleClass().add("summary-amount-cell");
                avgCell.getStyleClass().add("summary-avg-col");
                avgCell.getStyleClass().add(zebra);
                monthsTable.add(avgCell, 12, rowIdx);

                List<String> exportRow = new ArrayList<>();
                exportRow.add(r.name());
                for (int m = 1; m <= 12; m++) {
                    exportRow.add(formatMoney(months[m], currencyCode));
                }
                exportRow.add(formatMoney(rowTotal, currencyCode));
                exportRow.add(formatMoney(avgCents, currencyCode));
                exportRows.add(exportRow);
                rowIdx++;
            }

            Label totalName = new Label("TOTAL");
            totalName.getStyleClass().add("account-name");
            totalName.getStyleClass().add("summary-total-name");
            fixedTable.add(totalName, 0, rowIdx);
            for (int m = 1; m <= 12; m++) {
                Label v = new Label(formatMoney(totalByMonth[m], currencyCode));
                v.getStyleClass().add("account-name");
                v.setMinWidth(100);
                v.setAlignment(Pos.CENTER_RIGHT);
                v.getStyleClass().add("summary-total-amount");
                if (m == currentMonth) {
                    v.getStyleClass().add("summary-current-month");
                }
                monthsTable.add(v, m - 1, rowIdx);
            }

            Label grand = new Label(formatMoney(grandTotal, currencyCode));
            grand.getStyleClass().add("account-name");
            grand.setMinWidth(100);
            grand.setAlignment(Pos.CENTER_RIGHT);
            grand.getStyleClass().add("summary-total-amount");
            grand.getStyleClass().add("summary-total-col");
            fixedTable.add(grand, 1, rowIdx);

            int totalMonthCount = 0;
            for (int m = 1; m <= 12; m++) {
                if (totalByMonth[m] != 0) {
                    totalMonthCount++;
                }
            }
            long avgTotal = totalMonthCount == 0 ? 0 : (grandTotal / totalMonthCount);
            Label grandAvg = new Label(formatMoney(avgTotal, currencyCode));
            grandAvg.getStyleClass().add("account-name");
            grandAvg.setMinWidth(100);
            grandAvg.setAlignment(Pos.CENTER_RIGHT);
            grandAvg.getStyleClass().add("summary-total-amount");
            grandAvg.getStyleClass().add("summary-avg-col");
            monthsTable.add(grandAvg, 12, rowIdx);

            List<String> totalExportRow = new ArrayList<>();
            totalExportRow.add("TOTAL");
            for (int m = 1; m <= 12; m++) {
                totalExportRow.add(formatMoney(totalByMonth[m], currencyCode));
            }
            totalExportRow.add(formatMoney(grandTotal, currencyCode));
            totalExportRow.add(formatMoney(avgTotal, currencyCode));
            exportRows.add(totalExportRow);

            exportRowsRef.set(exportRows);

            applySummaryRowHover(fixedTable, monthsTable);
        };

        year.valueProperty().addListener((obs, o, n) -> refreshSummary.run());
        kind.valueProperty().addListener((obs, o, n) -> refreshSummary.run());
        view.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        account.valueProperty().addListener((obs, o, n) -> refreshSummary.run());
        rootCategory.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        subCategory.valueProperty().addListener((obs, o, n) -> {
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        year.valueProperty().addListener((obs, o, n) -> refreshAccountsForSummary.run());
        kind.valueProperty().addListener((obs, o, n) -> refreshAccountsForSummary.run());

        refreshAccountsForSummary.run();

        Button exportCsv = new Button("Exportar CSV");
        exportCsv.getStyleClass().add("btn-secondary");
        exportCsv.setOnAction(e -> {
            try {
                String y = year.getValue() == null ? String.valueOf(currentYear) : String.valueOf(year.getValue());
                String k = kind.getValue() == null ? "" : kind.getValue();
                String v = view.getValue() == null ? "" : view.getValue();

                FileChooser chooser = new FileChooser();
                chooser.setTitle("Exportar resumen a CSV");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
                chooser.setInitialFileName("resumen_" + y + "_" + k + "_" + v + ".csv");
                java.io.File out = chooser.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
                if (out == null) {
                    return;
                }

                List<List<String>> rows = exportRowsRef.get();
                StringBuilder sb = new StringBuilder();
                for (List<String> row : rows) {
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) {
                            sb.append(';');
                        }
                        String cell = row.get(i) == null ? "" : row.get(i);
                        cell = cell.replace("\"", "\"\"");
                        sb.append('"').append(cell).append('"');
                    }
                    sb.append("\r\n");
                }
                Path p = out.toPath();
                Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        });

        HBox actionsRow = new HBox(10, toggleFilters, exportCsv);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(12, actionsRow, filtersCard, tablesRow);
        body.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(body);

        refreshSummary.run();
        dialog.showAndWait();
    }

    private static void applySummaryRowHover(GridPane fixedTable, GridPane monthsTable) {
        Map<Integer, List<Node>> nodesByRow = new HashMap<>();

        for (Node n : fixedTable.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            int row = r == null ? 0 : r;
            nodesByRow.computeIfAbsent(row, __ -> new ArrayList<>()).add(n);
        }

        for (Node n : monthsTable.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            int row = r == null ? 0 : r;
            nodesByRow.computeIfAbsent(row, __ -> new ArrayList<>()).add(n);
        }

        for (Map.Entry<Integer, List<Node>> e : nodesByRow.entrySet()) {
            int row = e.getKey();
            if (row <= 0) {
                continue;
            }
            List<Node> rowNodes = e.getValue();
            for (Node n : rowNodes) {
                n.setOnMouseEntered(ev -> {
                    for (Node x : rowNodes) {
                        if (!x.getStyleClass().contains("summary-row-hover")) {
                            x.getStyleClass().add("summary-row-hover");
                        }
                    }
                });
                n.setOnMouseExited(ev -> {
                    for (Node x : rowNodes) {
                        x.getStyleClass().remove("summary-row-hover");
                    }
                });
            }
        }
    }

    private enum EditAccountAction {
        SAVE,
        VIEW_SUMMARY,
        DELETE
    }

    private record EditAccountResult(EditAccountAction action, String newName) {
    }

    private static Alert buildAlert(AlertType type, String title, String header, String content, boolean darkTheme) {
        Alert a = new Alert(type);
        a.setTitle(title);

        FontIcon icon;
        String iconClass;
        if (type == AlertType.ERROR) {
            icon = new FontIcon("fas-times-circle");
            iconClass = "text-danger";
        } else if (type == AlertType.WARNING) {
            icon = new FontIcon("fas-exclamation-triangle");
            iconClass = "text-danger";
        } else if (type == AlertType.CONFIRMATION) {
            icon = new FontIcon("fas-question-circle");
            iconClass = "text-secondary";
        } else {
            icon = new FontIcon("fas-info-circle");
            iconClass = "text-secondary";
        }
        icon.setIconSize(22);
        icon.getStyleClass().add(iconClass);

        Label headerLabel = new Label(header == null ? "" : header);
        headerLabel.getStyleClass().add("account-name");

        HBox headerBox = new HBox(10, icon, headerLabel);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        Label contentLabel = new Label(content == null ? "" : content);
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(520);

        VBox body = new VBox(10, headerBox, contentLabel);
        body.setPadding(new Insets(4, 0, 0, 0));

        a.setHeaderText(null);
        a.setContentText(null);
        a.getDialogPane().setContent(body);

        a.getDialogPane().setMinWidth(560);
        a.getDialogPane().setPrefWidth(560);
        a.getDialogPane().setGraphic(null);
        UiDialogs.applyAppTheme(a, darkTheme);
        return a;
    }

    private static void showAccountSummaryDialog(
        String userUid,
        AccountRepository.Account account,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        AccountRepository accountRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Resumen de cuenta");
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().getStyleClass().add("account-summary-dialog");
        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(600);
        dialog.getDialogPane().setPrefHeight(650);

        Label headerTitle = new Label("Resumen de cuenta");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label(account == null ? "" : account.name());
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(84);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        DatePicker fromDate = new DatePicker();
        DatePicker toDate = new DatePicker();
        fromDate.setPrefWidth(170);
        toDate.setPrefWidth(170);

        Label lFrom = new Label("Desde");
        lFrom.getStyleClass().add("account-name");
        Label lTo = new Label("Hasta");
        lTo.getStyleClass().add("account-name");
        HBox pFrom = new HBox(8, lFrom, fromDate);
        pFrom.setAlignment(Pos.CENTER_LEFT);
        HBox pTo = new HBox(8, lTo, toDate);
        pTo.setAlignment(Pos.CENTER_LEFT);

        FlowPane filtersRow = new FlowPane(12, 10);
        filtersRow.getChildren().addAll(pFrom, pTo);
        VBox filtersCard = new VBox(10, filtersRow);
        filtersCard.getStyleClass().addAll("card", "content-card");
        filtersCard.setPadding(new Insets(10));

        VBox txBox = new VBox(6);
        txBox.getStyleClass().add("accounts-list");
        ScrollPane txScroll = new ScrollPane(txBox);
        txScroll.setFitToWidth(true);
        txScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        txScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(txScroll, Priority.ALWAYS);

        VBox trBox = new VBox(6);
        trBox.getStyleClass().add("accounts-list");
        ScrollPane trScroll = new ScrollPane(trBox);
        trScroll.setFitToWidth(true);
        trScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        trScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(trScroll, Priority.ALWAYS);

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("account-summary-tabs");
        Tab txTab = new Tab("Transacciones", txScroll);
        txTab.setClosable(false);
        Tab trTab = new Tab("Transferencias", trScroll);
        trTab.setClosable(false);
        tabs.getTabs().addAll(txTab, trTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Runnable refresh = () -> {
            txBox.getChildren().clear();
            trBox.getChildren().clear();

            String accountId = account == null ? null : account.id();
            Long fromEpoch = fromDate.getValue() == null ? null : fromDate.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
            Long toEpoch = toDate.getValue() == null ? null : toDate.getValue().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond();

            Map<String, String> accountCurrency = new HashMap<>();
            try {
                for (AccountRepository.Account a : accountRepo.list(userUid)) {
                    accountCurrency.put(a.id(), a.currency());
                }
            } catch (Exception ignored) {
            }

            try {
                List<TransactionRepository.TransactionRow> txs = txRepo.listFiltered(userUid, accountId, (List<String>) null, fromEpoch, toEpoch, 200);
                if (txs.isEmpty()) {
                    Label empty = new Label("No hay transacciones en este rango.");
                    empty.getStyleClass().add("text-secondary");
                    txBox.getChildren().add(empty);
                } else {
                    for (TransactionRepository.TransactionRow t : txs) {
                        LocalDate d = Instant.ofEpochSecond(t.occurredAtEpochSec()).atZone(ZoneId.systemDefault()).toLocalDate();

                        Label left = new Label(d + " · " + t.categoryName());
                        left.getStyleClass().add("account-name");

                        Label note = null;
                        if (t.note() != null && !t.note().trim().isBlank()) {
                            note = new Label(t.note().trim());
                            note.getStyleClass().add("text-secondary");
                            note.setWrapText(true);
                        }

                        long signed = "EXPENSE".equalsIgnoreCase(t.kind()) ? -t.amountCents() : t.amountCents();
                        String currency = accountCurrency.get(t.accountId());

                        Label right = new Label(formatMoney(signed, currency));
                        if (signed > 0) {
                            right.getStyleClass().add("money-positive");
                        } else if (signed < 0) {
                            right.getStyleClass().add("money-negative");
                        } else {
                            right.getStyleClass().add("money-neutral");
                        }

                        Region spacer = new Region();
                        HBox.setHgrow(spacer, Priority.ALWAYS);
                        HBox top = new HBox(10, left, spacer, right);
                        VBox row = note == null ? new VBox(2, top) : new VBox(2, top, note);
                        row.getStyleClass().add("account-item");
                        txBox.getChildren().add(row);
                    }
                }
            } catch (Exception ex) {
                txBox.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
            }

            try {
                List<TransferRepository.TransferRow> trs = transferRepo.listFiltered(userUid, accountId, fromEpoch, toEpoch, 200);
                if (trs.isEmpty()) {
                    Label empty = new Label("No hay transferencias en este rango.");
                    empty.getStyleClass().add("text-secondary");
                    trBox.getChildren().add(empty);
                } else {
                    for (TransferRepository.TransferRow tr : trs) {
                        LocalDate d = Instant.ofEpochSecond(tr.occurredAtEpochSec()).atZone(ZoneId.systemDefault()).toLocalDate();

                        boolean outgoing = accountId != null && accountId.equals(tr.fromAccountId());
                        String arrow = outgoing ? "→" : "←";
                        String other = outgoing ? tr.toAccountName() : tr.fromAccountName();

                        Label left = new Label(d + " · " + arrow + " " + other);
                        left.getStyleClass().add("account-name");

                        Label note = null;
                        if (tr.note() != null && !tr.note().trim().isBlank()) {
                            note = new Label(tr.note().trim());
                            note.getStyleClass().add("text-secondary");
                            note.setWrapText(true);
                        }

                        String cur = accountCurrency.get(outgoing ? tr.fromAccountId() : tr.toAccountId());
                        long signed = outgoing ? -tr.amountCents() : tr.amountCents();
                        Label right = new Label(formatMoney(signed, cur));
                        right.getStyleClass().add("money-neutral");

                        Region spacer = new Region();
                        HBox.setHgrow(spacer, Priority.ALWAYS);

                        HBox top = new HBox(10, left, spacer, right);
                        VBox row = note == null ? new VBox(2, top) : new VBox(2, top, note);
                        row.getStyleClass().add("account-item");
                        trBox.getChildren().add(row);
                    }
                }
            } catch (Exception ex) {
                trBox.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
            }
        };

        fromDate.valueProperty().addListener((obs, o, n) -> refresh.run());
        toDate.valueProperty().addListener((obs, o, n) -> refresh.run());

        VBox body = new VBox(12, filtersCard, tabs);
        body.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(body);

        refresh.run();
        dialog.showAndWait();
    }

    private static void refreshTransactions(
        AuthSession session,
        String userUid,
        TransactionRepository txRepo,
        VBox txBox,
        String accountId,
        String rootCategoryId,
        String subCategoryId,
        LocalDate fromDate,
        LocalDate toDate,
        boolean darkTheme,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        Runnable refreshBalances
    ) {
        txBox.getChildren().clear();
        try {
            Map<String, String> accountCurrency = new HashMap<>();
            try {
                for (AccountRepository.Account a : accountRepo.list(userUid)) {
                    accountCurrency.put(a.id(), a.currency());
                }
            } catch (Exception ignored) {
            }

            Long fromEpoch = fromDate == null ? null : fromDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
            Long toEpoch = toDate == null ? null : toDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond();

            List<String> categoryIds = null;
            if (subCategoryId != null && !subCategoryId.isBlank()) {
                categoryIds = new ArrayList<>();
                categoryIds.add(subCategoryId);
            } else if (rootCategoryId != null && !rootCategoryId.isBlank()) {
                categoryIds = new ArrayList<>();
                categoryIds.add(rootCategoryId);
                try {
                    for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, rootCategoryId)) {
                        categoryIds.add(c.id());
                    }
                } catch (Exception ignored) {
                }
            }

            List<TransactionRepository.TransactionRow> txs = txRepo.listFiltered(userUid, accountId, categoryIds, fromEpoch, toEpoch, 80);
            if (txs.isEmpty()) {
                Label empty = new Label("No hay transacciones aún.");
                empty.getStyleClass().add("text-secondary");
                txBox.getChildren().add(empty);
                return;
            }

            for (TransactionRepository.TransactionRow t : txs) {
                LocalDate d = Instant.ofEpochSecond(t.occurredAtEpochSec()).atZone(ZoneId.systemDefault()).toLocalDate();

                Label left = new Label(d + " · " + t.accountName() + " · " + t.categoryName());
                left.getStyleClass().add("account-name");

                Label note = null;
                if (t.note() != null && !t.note().trim().isBlank()) {
                    note = new Label(t.note().trim());
                    note.getStyleClass().add("text-secondary");
                    note.setWrapText(true);
                }

                long signed = "EXPENSE".equalsIgnoreCase(t.kind()) ? -t.amountCents() : t.amountCents();
                String currency = accountCurrency.get(t.accountId());

                Label right = new Label(formatMoney(signed, currency));
                if (signed > 0) {
                    right.getStyleClass().add("money-positive");
                } else if (signed < 0) {
                    right.getStyleClass().add("money-negative");
                } else {
                    right.getStyleClass().add("money-neutral");
                }

                Runnable doEdit = () -> {
                    Optional<NewTransaction> updated = showEditTransactionDialog(
                        t,
                        userUid,
                        accountRepo,
                        categoryRepo,
                        darkTheme
                    );
                    if (updated.isEmpty()) {
                        return;
                    }
                    try {
                        NewTransaction ut = updated.get();
                        txRepo.update(userUid, t.id(), ut.accountId(), ut.categoryId(), ut.kind(), ut.amountCents(), ut.occurredAtEpochSec(), ut.note());
                        try {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncTransaction(session, txRepo.getForSyncById(userUid, t.id()));
                        } catch (Exception ignored) {
                        }
                        refreshBalances.run();
                        refreshTransactions(session, userUid, txRepo, txBox, accountId, rootCategoryId, subCategoryId, fromDate, toDate, darkTheme, accountRepo, categoryRepo, refreshBalances);
                    } catch (Exception ignored) {
                    }
                };

                Runnable doDelete = () -> {
                    Dialog<ButtonType> confirm = new Dialog<>();
                    confirm.setTitle("Eliminar");
                    UiDialogs.applyAppTheme(confirm, darkTheme);
                    confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
                    confirm.setContentText("¿Eliminar esta transacción?");
                    confirm.showAndWait().ifPresent(btn -> {
                        if (btn != ButtonType.OK) {
                            return;
                        }
                        try {
                            txRepo.delete(userUid, t.id());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.deleteTransaction(session, t.id());
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            refreshTransactions(session, userUid, txRepo, txBox, accountId, rootCategoryId, subCategoryId, fromDate, toDate, darkTheme, accountRepo, categoryRepo, refreshBalances);
                        } catch (Exception ignored) {
                        }
                    });
                };

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Button editBtn = new Button("Editar");
                editBtn.getStyleClass().add("btn-secondary");
                editBtn.setOnAction(ev -> doEdit.run());

                Button delBtn = new Button("Eliminar");
                delBtn.getStyleClass().add("btn-danger");
                delBtn.setOnAction(ev -> doDelete.run());

                HBox actions = new HBox(8, editBtn, delBtn);
                actions.setAlignment(Pos.CENTER_RIGHT);

                HBox top = new HBox(10, left, spacer, right, actions);
                VBox row = note == null ? new VBox(2, top) : new VBox(2, top, note);
                row.getStyleClass().add("account-item");

                MenuItem edit = new MenuItem("Editar");
                edit.setOnAction(ev -> doEdit.run());

                MenuItem del = new MenuItem("Eliminar");
                del.setOnAction(ev -> doDelete.run());

                ContextMenu menu = new ContextMenu(edit, del);
                row.setOnContextMenuRequested(ev -> menu.show(row, ev.getScreenX(), ev.getScreenY()));
                txBox.getChildren().add(row);
            }
        } catch (Exception ex) {
            txBox.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
        }
    }

    private static void showTransfersDialog(
        AuthSession session,
        TransferRepository transferRepo,
        AccountRepository accountRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Transferencias");
        UiDialogs.applyAppTheme(dialog, darkTheme);
        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(720);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);

                        final double normalW = Math.min(1100, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(760, bounds.getHeight() * 0.90);
                        s.maximizedProperty().addListener((o, oldV, newV) -> {
                            if (Boolean.TRUE.equals(newV)) {
                                return;
                            }
                            try {
                                s.setWidth(normalW);
                                s.setHeight(normalH);
                                s.centerOnScreen();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Transferencias");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Mueve dinero entre tus cuentas (origen → destino). Esto afecta saldos.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(96);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        VBox listBox = new VBox(6);
        listBox.getStyleClass().add("accounts-list");
        ScrollPane listScroll = new ScrollPane(listBox);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        ChoiceBox<AccountRepository.Account> accountFilter = new ChoiceBox<>();
        DatePicker fromDate = new DatePicker();
        DatePicker toDate = new DatePicker();

        try {
            accountFilter.getItems().add(null);
            accountFilter.getItems().addAll(accountRepo.list(userUid));
            accountFilter.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        accountFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        Runnable refresh = () -> refreshTransfers(
            session,
            userUid,
            transferRepo,
            listBox,
            accountFilter.getValue() == null ? null : accountFilter.getValue().id(),
            fromDate.getValue(),
            toDate.getValue(),
            darkTheme,
            accountRepo,
            refreshBalances
        );

        accountFilter.valueProperty().addListener((obs, o, n) -> refresh.run());
        fromDate.valueProperty().addListener((obs, o, n) -> refresh.run());
        toDate.valueProperty().addListener((obs, o, n) -> refresh.run());

        Label lAccount = new Label("Cuenta");
        lAccount.getStyleClass().add("account-name");
        Label lFrom = new Label("Desde");
        lFrom.getStyleClass().add("account-name");
        Label lTo = new Label("Hasta");
        lTo.getStyleClass().add("account-name");

        accountFilter.setPrefWidth(240);
        fromDate.setPrefWidth(170);
        toDate.setPrefWidth(170);

        HBox pAcc = new HBox(8, lAccount, accountFilter);
        pAcc.setAlignment(Pos.CENTER_LEFT);
        HBox pFrom = new HBox(8, lFrom, fromDate);
        pFrom.setAlignment(Pos.CENTER_LEFT);
        HBox pTo = new HBox(8, lTo, toDate);
        pTo.setAlignment(Pos.CENTER_LEFT);

        FlowPane filtersRow = new FlowPane(12, 10);
        filtersRow.getChildren().addAll(pAcc, pFrom, pTo);
        VBox filtersCard = new VBox(10, filtersRow);
        filtersCard.getStyleClass().addAll("card", "content-card");
        filtersCard.setPadding(new Insets(10));

        Button newTransfer = new Button("Nueva transferencia");
        newTransfer.getStyleClass().add("btn-primary");
        newTransfer.setOnAction(e -> {
            Optional<NewTransfer> t = showCreateTransferDialog(userUid, accountRepo, darkTheme);
            if (t.isEmpty()) {
                return;
            }
            try {
                NewTransfer tr = t.get();
                String transferId = transferRepo.create(userUid, tr.fromAccountId(), tr.toAccountId(), tr.amountCents(), tr.occurredAtEpochSec(), tr.note());
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                } catch (Exception ignored) {
                }
                refreshBalances.run();
                refresh.run();
            } catch (Exception ignored) {
            }
        });

        VBox body = new VBox(12, newTransfer, filtersCard, listScroll);
        body.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(body);

        Runnable doRefreshNow = () -> {
            new Thread(() -> {
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    List<TransferRepository.TransferSyncRow> remote = sync.pullTransfers(session);

                    Set<String> remoteIds = new HashSet<>();
                    for (TransferRepository.TransferSyncRow tr : remote) {
                        remoteIds.add(tr.id());
                        if (accountRepo.getById(userUid, tr.fromAccountId()) == null) {
                            continue;
                        }
                        if (accountRepo.getById(userUid, tr.toAccountId()) == null) {
                            continue;
                        }
                        try {
                            transferRepo.upsertFromRemote(userUid, tr);
                        } catch (Exception ignored) {
                        }
                    }

                    List<TransferRepository.TransferSyncRow> localAll = transferRepo.listAllForSync(userUid);
                    for (TransferRepository.TransferSyncRow tr : localAll) {
                        if (remoteIds.contains(tr.id())) {
                            continue;
                        }
                        try {
                            transferRepo.delete(userUid, tr.id());
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }

                Platform.runLater(() -> {
                    refreshBalances.run();
                    refresh.run();
                });
            }).start();
        };

        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F5) {
                doRefreshNow.run();
                ev.consume();
            }
        });

        refresh.run();
        dialog.showAndWait();
    }

    private static void refreshTransfers(
        AuthSession session,
        String userUid,
        TransferRepository transferRepo,
        VBox box,
        String accountId,
        LocalDate fromDate,
        LocalDate toDate,
        boolean darkTheme,
        AccountRepository accountRepo,
        Runnable refreshBalances
    ) {
        box.getChildren().clear();
        try {
            Map<String, String> accountCurrency = new HashMap<>();
            try {
                for (AccountRepository.Account a : accountRepo.list(userUid)) {
                    accountCurrency.put(a.id(), a.currency());
                }
            } catch (Exception ignored) {
            }

            Long fromEpoch = fromDate == null ? null : fromDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
            Long toEpoch = toDate == null ? null : toDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond();
            List<TransferRepository.TransferRow> rows = transferRepo.listFiltered(userUid, accountId, fromEpoch, toEpoch, 80);

            if (rows.isEmpty()) {
                Label empty = new Label("No hay transferencias aún.");
                empty.getStyleClass().add("text-secondary");
                box.getChildren().add(empty);
                return;
            }

            for (TransferRepository.TransferRow tr : rows) {
                LocalDate d = Instant.ofEpochSecond(tr.occurredAtEpochSec()).atZone(ZoneId.systemDefault()).toLocalDate();
                Label left = new Label(d + " · " + tr.fromAccountName() + " → " + tr.toAccountName());
                left.getStyleClass().add("account-name");

                Label note = null;
                if (tr.note() != null && !tr.note().trim().isBlank()) {
                    note = new Label(tr.note().trim());
                    note.getStyleClass().add("text-secondary");
                    note.setWrapText(true);
                }

                String cur = accountCurrency.get(tr.fromAccountId());

                Label right = new Label(formatMoney(tr.amountCents(), cur));
                right.getStyleClass().add("money-neutral");

                Runnable doEdit = () -> {
                    Optional<NewTransfer> updated = showEditTransferDialog(tr, userUid, accountRepo, darkTheme);
                    if (updated.isEmpty()) {
                        return;
                    }
                    try {
                        NewTransfer ut = updated.get();
                        transferRepo.update(userUid, tr.id(), ut.fromAccountId(), ut.toAccountId(), ut.amountCents(), ut.occurredAtEpochSec(), ut.note());
                        try {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncTransfer(session, transferRepo.getForSyncById(userUid, tr.id()));
                        } catch (Exception ignored) {
                        }
                        refreshBalances.run();
                        refreshTransfers(session, userUid, transferRepo, box, accountId, fromDate, toDate, darkTheme, accountRepo, refreshBalances);
                    } catch (Exception ignored) {
                    }
                };

                Runnable doDelete = () -> {
                    Dialog<ButtonType> confirm = new Dialog<>();
                    confirm.setTitle("Eliminar");
                    UiDialogs.applyAppTheme(confirm, darkTheme);
                    confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
                    confirm.setContentText("¿Eliminar esta transferencia?");
                    confirm.showAndWait().ifPresent(btn -> {
                        if (btn != ButtonType.OK) {
                            return;
                        }
                        try {
                            transferRepo.delete(userUid, tr.id());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.deleteTransfer(session, tr.id());
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            refreshTransfers(session, userUid, transferRepo, box, accountId, fromDate, toDate, darkTheme, accountRepo, refreshBalances);
                        } catch (Exception ignored) {
                        }
                    });
                };

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Button editBtn = new Button("Editar");
                editBtn.getStyleClass().add("btn-secondary");
                editBtn.setOnAction(ev -> doEdit.run());

                Button delBtn = new Button("Eliminar");
                delBtn.getStyleClass().add("btn-danger");
                delBtn.setOnAction(ev -> doDelete.run());

                HBox actions = new HBox(8, editBtn, delBtn);
                actions.setAlignment(Pos.CENTER_RIGHT);

                HBox top = new HBox(10, left, spacer, right, actions);
                VBox row = note == null ? new VBox(2, top) : new VBox(2, top, note);
                row.getStyleClass().add("account-item");

                MenuItem edit = new MenuItem("Editar");
                edit.setOnAction(ev -> doEdit.run());
                MenuItem del = new MenuItem("Eliminar");
                del.setOnAction(ev -> doDelete.run());
                ContextMenu menu = new ContextMenu(edit, del);
                row.setOnContextMenuRequested(ev -> menu.show(row, ev.getScreenX(), ev.getScreenY()));

                box.getChildren().add(row);
            }
        } catch (Exception ex) {
            box.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
        }
    }

    private record NewTransfer(
        String fromAccountId,
        String toAccountId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    private static Optional<NewTransfer> showCreateTransferDialog(
        String userUid,
        AccountRepository accountRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Nueva transferencia");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(620);
        dialog.getDialogPane().setPrefWidth(620);

        DatePicker date = new DatePicker(LocalDate.now());

        ChoiceBox<AccountRepository.Account> from = new ChoiceBox<>();
        ChoiceBox<AccountRepository.Account> to = new ChoiceBox<>();
        TextField amount = new TextField();
        amount.setPromptText("Ej: 10000.00");
        amount.setPrefWidth(340);
        TextField note = new TextField();
        note.setPromptText("Nota (opcional)");
        note.setPrefWidth(340);

        Label balanceLabel = new Label();
        balanceLabel.getStyleClass().add("account-name");

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Runnable refreshBalance = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);

            AccountRepository.Account a = from.getValue();
            if (a == null) {
                balanceLabel.setText("");
                return;
            }
            try {
                long bal = accountRepo.computeBalanceCents(userUid, a.id());
                balanceLabel.setText("Saldo disponible: " + formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };

        try {
            List<AccountRepository.Account> accounts = accountRepo.list(userUid);
            from.getItems().setAll(accounts);
            to.getItems().setAll(accounts);
            if (!accounts.isEmpty()) {
                from.getSelectionModel().selectFirst();
                to.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }

        from.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshBalance.run());
        refreshBalance.run();

        from.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });
        to.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(580);

        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 0);
        grid.add(date, 1, 0);

        Label lFrom = new Label("Origen");
        lFrom.getStyleClass().add("account-name");
        grid.add(lFrom, 0, 1);
        grid.add(from, 1, 1);

        Label lTo = new Label("Destino");
        lTo.getStyleClass().add("account-name");
        grid.add(lTo, 0, 2);
        grid.add(to, 1, 2);

        Label lBalance = new Label("Disponible");
        lBalance.getStyleClass().add("account-name");
        grid.add(lBalance, 0, 3);
        grid.add(balanceLabel, 1, 3);

        Label lAmount = new Label("Monto");
        lAmount.getStyleClass().add("account-name");
        grid.add(lAmount, 0, 4);
        grid.add(amount, 1, 4);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 5);
        grid.add(note, 1, 5);

        grid.add(error, 0, 6, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }
        if (date.getValue() == null || from.getValue() == null || to.getValue() == null) {
            return Optional.empty();
        }
        if (from.getValue().id().equals(to.getValue().id())) {
            return Optional.empty();
        }

        String raw = amount.getText() == null ? "" : amount.getText().trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(raw);
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents < 0) {
            return Optional.empty();
        }

        try {
            long available = accountRepo.computeBalanceCents(userUid, from.getValue().id());
            if (cents > available) {
                error.setText("El monto supera el saldo disponible.");
                error.setVisible(true);
                error.setManaged(true);
                return Optional.empty();
            }
        } catch (Exception ignored) {
        }

        long occurredAt = date.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return Optional.of(new NewTransfer(
            from.getValue().id(),
            to.getValue().id(),
            cents,
            occurredAt,
            note.getText() == null ? null : note.getText().trim()
        ));
    }

    private static Optional<NewTransfer> showEditTransferDialog(
        TransferRepository.TransferRow existing,
        String userUid,
        AccountRepository accountRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Editar transferencia");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(620);
        dialog.getDialogPane().setPrefWidth(620);

        LocalDate existingDate = Instant.ofEpochSecond(existing.occurredAtEpochSec()).atZone(ZoneId.systemDefault()).toLocalDate();
        DatePicker date = new DatePicker(existingDate);
        ChoiceBox<AccountRepository.Account> from = new ChoiceBox<>();
        ChoiceBox<AccountRepository.Account> to = new ChoiceBox<>();
        TextField amount = new TextField(new java.math.BigDecimal(existing.amountCents()).movePointLeft(2).toPlainString());
        amount.setPrefWidth(340);
        TextField note = new TextField(existing.note() == null ? "" : existing.note());
        note.setPrefWidth(340);

        Label balanceLabel = new Label();
        balanceLabel.getStyleClass().add("account-name");

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        try {
            List<AccountRepository.Account> accounts = accountRepo.list(userUid);
            from.getItems().setAll(accounts);
            to.getItems().setAll(accounts);
            for (AccountRepository.Account a : accounts) {
                if (a.id().equals(existing.fromAccountId())) {
                    from.getSelectionModel().select(a);
                }
                if (a.id().equals(existing.toAccountId())) {
                    to.getSelectionModel().select(a);
                }
            }
        } catch (Exception ignored) {
        }

        from.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });
        to.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(580);

        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 0);
        grid.add(date, 1, 0);

        Label lFrom = new Label("Origen");
        lFrom.getStyleClass().add("account-name");
        grid.add(lFrom, 0, 1);
        grid.add(from, 1, 1);

        Label lTo = new Label("Destino");
        lTo.getStyleClass().add("account-name");
        grid.add(lTo, 0, 2);
        grid.add(to, 1, 2);

        Label lBalance = new Label("Disponible");
        lBalance.getStyleClass().add("account-name");
        grid.add(lBalance, 0, 3);
        grid.add(balanceLabel, 1, 3);

        Label lAmount = new Label("Monto");
        lAmount.getStyleClass().add("account-name");
        grid.add(lAmount, 0, 4);
        grid.add(amount, 1, 4);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 5);
        grid.add(note, 1, 5);

        grid.add(error, 0, 6, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }
        if (date.getValue() == null || from.getValue() == null || to.getValue() == null) {
            return Optional.empty();
        }
        if (from.getValue().id().equals(to.getValue().id())) {
            return Optional.empty();
        }

        String raw = amount.getText() == null ? "" : amount.getText().trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(raw);
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents < 0) {
            return Optional.empty();
        }

        long occurredAt = date.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return Optional.of(new NewTransfer(
            from.getValue().id(),
            to.getValue().id(),
            cents,
            occurredAt,
            note.getText() == null ? null : note.getText().trim()
        ));
    }

    private static void showCategoriesDialog(AuthSession session, CategoryRepository categoryRepo, boolean darkTheme) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Categorías");
        UiDialogs.applyAppTheme(dialog, darkTheme);
        ButtonType closeBtn = new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);

        Label headerTitle = new Label("Categorías");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Crea, edita o elimina categorías y subcategorías para organizar tus movimientos.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setWrapText(true);
        VBox header = new VBox(4, headerTitle, headerDesc);
        header.getStyleClass().add("dialog-header");
        header.setAlignment(Pos.CENTER);
        header.setMaxWidth(Double.MAX_VALUE);
        dialog.getDialogPane().setHeader(header);

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        ListView<CategoryRepository.Category> roots = new ListView<>();
        ListView<CategoryRepository.Category> children = new ListView<>();
        roots.getStyleClass().add("categories-list");
        children.getStyleClass().add("categories-list");
        roots.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        children.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        roots.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            {
                getStyleClass().add("account-name");
            }
            @Override
            protected void updateItem(CategoryRepository.Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        children.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            {
                getStyleClass().add("account-name");
            }
            @Override
            protected void updateItem(CategoryRepository.Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });

        Runnable clearError = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
        };

        Runnable showError = () -> {
            error.setVisible(true);
            error.setManaged(true);
        };

        AtomicReference<Runnable> refreshRootsRef = new AtomicReference<>();
        AtomicReference<Runnable> refreshChildrenRef = new AtomicReference<>();

        Runnable pullCategories = () -> {
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<CategoryRepository.Category> remote = sync.pullCategories(session);

                Set<String> remoteIds = new HashSet<>();
                for (CategoryRepository.Category c : remote) {
                    remoteIds.add(c.id());
                }

                List<CategoryRepository.Category> rootRemote = new ArrayList<>();
                List<CategoryRepository.Category> childRemote = new ArrayList<>();
                for (CategoryRepository.Category c : remote) {
                    if (c.parentId() == null || c.parentId().isBlank()) {
                        rootRemote.add(c);
                    } else {
                        childRemote.add(c);
                    }
                }

                for (CategoryRepository.Category c : rootRemote) {
                    categoryRepo.upsertFromRemote(userUid, c);
                }

                for (int pass = 0; pass < 5; pass++) {
                    boolean progressed = false;
                    for (CategoryRepository.Category c : childRemote) {
                        if (c.parentId() == null || c.parentId().isBlank()) {
                            continue;
                        }
                        if (categoryRepo.getById(userUid, c.parentId()) == null) {
                            continue;
                        }
                        categoryRepo.upsertFromRemote(userUid, c);
                        progressed = true;
                    }
                    if (!progressed) {
                        break;
                    }
                }

                List<CategoryRepository.Category> localAll = categoryRepo.listAll(userUid);
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
                        categoryRepo.delete(userUid, id);
                    } catch (Exception ignored) {
                    }
                }
                for (String id : toDeleteRoots) {
                    try {
                        categoryRepo.delete(userUid, id);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        };

        Runnable doRefreshNow = () -> {
            new Thread(() -> {
                pullCategories.run();
                Platform.runLater(() -> {
                    Runnable rr = refreshRootsRef.get();
                    if (rr != null) {
                        rr.run();
                    }
                    Runnable rc = refreshChildrenRef.get();
                    if (rc != null) {
                        rc.run();
                    }
                });
            }).start();
        };

        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F5) {
                doRefreshNow.run();
                ev.consume();
            }
        });

        Runnable refreshRoots = () -> {
            try {
                String selectedId = roots.getSelectionModel().getSelectedItem() == null
                    ? null
                    : roots.getSelectionModel().getSelectedItem().id();
                roots.getItems().setAll(categoryRepo.listRoots(userUid));
                if (selectedId != null) {
                    for (CategoryRepository.Category c : roots.getItems()) {
                        if (selectedId.equals(c.id())) {
                            roots.getSelectionModel().select(c);
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                roots.getItems().clear();
                error.setText(ex.getMessage() == null ? "No se pudieron cargar las categorías." : ex.getMessage());
                showError.run();
            }
        };

        refreshRootsRef.set(refreshRoots);

        Runnable refreshChildren = () -> {
            CategoryRepository.Category selected = roots.getSelectionModel().getSelectedItem();
            if (selected == null) {
                children.getItems().clear();
                return;
            }
            try {
                String selectedId = children.getSelectionModel().getSelectedItem() == null
                    ? null
                    : children.getSelectionModel().getSelectedItem().id();
                children.getItems().setAll(categoryRepo.listChildren(userUid, selected.id()));
                if (selectedId != null) {
                    for (CategoryRepository.Category c : children.getItems()) {
                        if (selectedId.equals(c.id())) {
                            children.getSelectionModel().select(c);
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                children.getItems().clear();
                error.setText(ex.getMessage() == null ? "No se pudieron cargar las subcategorías." : ex.getMessage());
                showError.run();
            }
        };

        refreshChildrenRef.set(refreshChildren);

        roots.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            children.getSelectionModel().clearSelection();
            refreshChildren.run();
        });

        java.util.function.Function<Exception, String> friendlyDeleteError = ex -> {
            String m = ex == null ? null : ex.getMessage();
            if (m == null || m.isBlank()) {
                return "No se puede eliminar porque tiene saldo o movimientos asociados. Deja el saldo en cero y vuelve a intentar.";
            }
            String u = m.toUpperCase(java.util.Locale.ROOT);
            if (u.contains("SQLITE_CONSTRAINT") || u.contains("CONSTRAINT") || u.contains("FOREIGN") || u.contains("TRIGGER")) {
                return "No se puede eliminar porque tiene saldo o movimientos asociados. Deja el saldo en cero y vuelve a intentar.";
            }
            return m;
        };

        refreshRoots.run();

        Button newRoot = new Button("Nueva categoría");
        newRoot.getStyleClass().add("btn-primary");
        newRoot.setOnAction(e -> {
            clearError.run();
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Nueva categoría");
            d.setHeaderText(null);
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    CategoryRepository.Category created = categoryRepo.create(userUid, n, null);
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.syncCategory(session, created);
                    } catch (Exception ignored) {
                    }
                    refreshRoots.run();
                    roots.getSelectionModel().select(created);
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo crear la categoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button editRoot = new Button("Editar");
        editRoot.getStyleClass().add("btn-secondary");
        editRoot.disableProperty().bind(roots.getSelectionModel().selectedItemProperty().isNull());
        editRoot.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = roots.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            TextInputDialog d = new TextInputDialog(selected.name());
            d.setTitle("Editar categoría");
            d.setHeaderText(null);
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    categoryRepo.rename(userUid, selected.id(), n);
                    try {
                        CategoryRepository.Category updated = categoryRepo.getById(userUid, selected.id());
                        if (updated != null) {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncCategory(session, updated);
                        }
                    } catch (Exception ignored) {
                    }
                    refreshRoots.run();
                    for (CategoryRepository.Category c : roots.getItems()) {
                        if (selected.id().equals(c.id())) {
                            roots.getSelectionModel().select(c);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo editar la categoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button deleteRoot = new Button("Eliminar");
        deleteRoot.getStyleClass().add("btn-danger");
        deleteRoot.disableProperty().bind(roots.getSelectionModel().selectedItemProperty().isNull());
        deleteRoot.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = roots.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            Dialog<ButtonType> confirm = new Dialog<>();
            confirm.setTitle("Eliminar");
            confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            confirm.setContentText("¿Eliminar la categoría '" + selected.name() + "' y sus subcategorías?");
            UiDialogs.applyAppTheme(confirm, darkTheme);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                try {
                    categoryRepo.delete(userUid, selected.id());
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.deleteCategory(session, selected.id());
                    } catch (Exception ignored) {
                    }
                    refreshRoots.run();
                    children.getItems().clear();
                } catch (Exception ignored) {
                    Alert alert = new Alert(AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("No se pudo eliminar la categoría.");
                    alert.setContentText(friendlyDeleteError.apply(ignored));
                    UiDialogs.applyAppTheme(alert, darkTheme);
                    alert.showAndWait();
                }
            });
        });

        Button newChild = new Button("Nueva subcategoría");
        newChild.getStyleClass().add("btn-primary");
        newChild.disableProperty().bind(roots.getSelectionModel().selectedItemProperty().isNull());
        newChild.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category parent = roots.getSelectionModel().getSelectedItem();
            if (parent == null) {
                return;
            }
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Nueva subcategoría");
            d.setHeaderText(parent.name());
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    CategoryRepository.Category created = categoryRepo.create(userUid, n, parent.id());
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.syncCategory(session, created);
                    } catch (Exception ignored) {
                    }
                    refreshChildren.run();
                    children.getSelectionModel().select(created);
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo crear la subcategoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button editChild = new Button("Editar");
        editChild.getStyleClass().add("btn-secondary");
        editChild.disableProperty().bind(children.getSelectionModel().selectedItemProperty().isNull());
        editChild.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = children.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            TextInputDialog d = new TextInputDialog(selected.name());
            d.setTitle("Editar subcategoría");
            d.setHeaderText(null);
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    categoryRepo.rename(userUid, selected.id(), n);
                    try {
                        CategoryRepository.Category updated = categoryRepo.getById(userUid, selected.id());
                        if (updated != null) {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncCategory(session, updated);
                        }
                    } catch (Exception ignored) {
                    }
                    refreshChildren.run();
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo editar la subcategoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button deleteChild = new Button("Eliminar");
        deleteChild.getStyleClass().add("btn-danger");
        deleteChild.disableProperty().bind(children.getSelectionModel().selectedItemProperty().isNull());
        deleteChild.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = children.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            Dialog<ButtonType> confirm = new Dialog<>();
            confirm.setTitle("Eliminar");
            confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            confirm.setContentText("¿Eliminar la subcategoría '" + selected.name() + "'?");
            UiDialogs.applyAppTheme(confirm, darkTheme);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                try {
                    categoryRepo.delete(userUid, selected.id());
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.deleteCategory(session, selected.id());
                    } catch (Exception ignored) {
                    }
                    refreshChildren.run();
                } catch (Exception ignored) {
                    Alert alert = new Alert(AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("No se pudo eliminar la subcategoría.");
                    alert.setContentText(friendlyDeleteError.apply(ignored));
                    UiDialogs.applyAppTheme(alert, darkTheme);
                    alert.showAndWait();
                }
            });
        });

        VBox left = new VBox(10, newRoot, editRoot, deleteRoot, roots);
        VBox right = new VBox(10, newChild, editChild, deleteChild, children);
        left.setPrefWidth(260);
        right.setPrefWidth(260);
        VBox.setVgrow(roots, Priority.ALWAYS);
        VBox.setVgrow(children, Priority.ALWAYS);

        HBox lists = new HBox(14, left, right);
        lists.setAlignment(Pos.CENTER);

        VBox content = new VBox(10, lists, error);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);

        var existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            var n = dialog.getDialogPane().lookupButton(closeBtn);
            if (n instanceof Button b) {
                b.getStyleClass().add("btn-secondary");
            }
        });

        dialog.showAndWait();
    }

    private record NewAccount(String name, String type, String currency) {
    }

    private record NewTransaction(
        String accountId,
        String categoryId,
        String kind,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    private static Optional<NewTransaction> showCreateTransactionDialog(
        String userUid,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Nueva transacción");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);

        dialog.getDialogPane().setMinWidth(640);
        dialog.getDialogPane().setPrefWidth(640);

        DatePicker date = new DatePicker(LocalDate.now());

        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();
        ChoiceBox<String> kind = new ChoiceBox<>();
        kind.getItems().addAll("INCOME", "EXPENSE");
        kind.getSelectionModel().selectFirst();

        Label balanceLabel = new Label();
        balanceLabel.getStyleClass().add("account-name");

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Runnable refreshBalance = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);

            AccountRepository.Account a = account.getValue();
            if (a == null) {
                balanceLabel.setText("");
                return;
            }
            if (!"EXPENSE".equalsIgnoreCase(kind.getValue())) {
                balanceLabel.setText("");
                return;
            }
            try {
                long bal = accountRepo.computeBalanceCents(userUid, a.id());
                balanceLabel.setText("Saldo disponible: " + formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };

        try {
            account.getItems().add(null);
            account.getItems().addAll(accountRepo.list(userUid));
            if (!account.getItems().isEmpty()) {
                account.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }

        Runnable refreshSubcats = () -> {
            CategoryRepository.Category root = rootCategory.getValue();
            subCategory.getItems().clear();
            subCategory.setDisable(root == null);
            if (root == null) {
                return;
            }
            try {
                subCategory.getItems().add(null);
                subCategory.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
                subCategory.getSelectionModel().selectFirst();
            } catch (Exception ignored) {
            }
        };

        try {
            rootCategory.getItems().add(null);
            rootCategory.getItems().addAll(categoryRepo.listRoots(userUid));
            if (!rootCategory.getItems().isEmpty()) {
                rootCategory.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }
        rootCategory.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> refreshSubcats.run());
        refreshSubcats.run();

        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Sin subcategoría)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        TextField amount = new TextField();
        amount.setPromptText("Ej: 10000.00");
        amount.setPrefWidth(340);

        TextField note = new TextField();
        note.setPromptText("Nota (opcional)");
        note.setPrefWidth(340);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(600);
        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 0);
        grid.add(date, 1, 0);
        Label lAccount = new Label("Cuenta");
        lAccount.getStyleClass().add("account-name");
        grid.add(lAccount, 0, 1);
        grid.add(account, 1, 1);
        Label lRoot = new Label("Categoría");
        lRoot.getStyleClass().add("account-name");
        grid.add(lRoot, 0, 2);
        grid.add(rootCategory, 1, 2);
        Label lSub = new Label("Subcategoría");
        lSub.getStyleClass().add("account-name");
        grid.add(lSub, 0, 3);
        grid.add(subCategory, 1, 3);
        Label lKind = new Label("Tipo");
        lKind.getStyleClass().add("account-name");
        grid.add(lKind, 0, 4);
        grid.add(kind, 1, 4);

        Label lBalance = new Label("Disponible");
        lBalance.getStyleClass().add("account-name");
        grid.add(lBalance, 0, 5);
        grid.add(balanceLabel, 1, 5);

        Label lAmount = new Label("Monto");
        lAmount.getStyleClass().add("account-name");
        grid.add(lAmount, 0, 6);
        grid.add(amount, 1, 6);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 7);
        grid.add(note, 1, 7);

        grid.add(error, 0, 8, 2, 1);
        dialog.getDialogPane().setContent(grid);

        account.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshBalance.run());
        kind.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshBalance.run());
        refreshBalance.run();

        javafx.scene.Node okNode = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okNode instanceof Button okBtn) {
            okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                error.setText("");
                error.setVisible(false);
                error.setManaged(false);

                if (date.getValue() == null || account.getValue() == null || rootCategory.getValue() == null) {
                    ev.consume();
                    return;
                }

                String raw = amount.getText() == null ? "" : amount.getText().trim();
                if (raw.isBlank()) {
                    ev.consume();
                    return;
                }

                long cents;
                try {
                    BigDecimal v = parseAmount(raw);
                    cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
                } catch (Exception ignored) {
                    ev.consume();
                    return;
                }
                if (cents < 0) {
                    ev.consume();
                    return;
                }

                if ("EXPENSE".equalsIgnoreCase(kind.getValue())) {
                    AccountRepository.Account a = account.getValue();
                    if (a != null) {
                        try {
                            long available = accountRepo.computeBalanceCents(userUid, a.id());
                            if (cents > available) {
                                error.setText("El monto supera el saldo disponible.");
                                error.setVisible(true);
                                error.setManaged(true);
                                ev.consume();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        if (date.getValue() == null || account.getValue() == null || rootCategory.getValue() == null) {
            return Optional.empty();
        }

        String raw = amount.getText() == null ? "" : amount.getText().trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(raw);
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }

        if (cents < 0) {
            return Optional.empty();
        }

        long occurredAt = date.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();

        CategoryRepository.Category chosen = subCategory.getValue() != null ? subCategory.getValue() : rootCategory.getValue();
        return Optional.of(new NewTransaction(
            account.getValue().id(),
            chosen.id(),
            kind.getValue(),
            cents,
            occurredAt,
            note.getText() == null ? null : note.getText().trim()
        ));
    }

    private static Optional<NewTransaction> showEditTransactionDialog(
        TransactionRepository.TransactionRow existing,
        String userUid,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Editar transacción");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(640);
        dialog.getDialogPane().setPrefWidth(640);

        LocalDate existingDate = Instant.ofEpochSecond(existing.occurredAtEpochSec()).atZone(ZoneId.systemDefault()).toLocalDate();
        DatePicker date = new DatePicker(existingDate);
        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();
        ChoiceBox<String> kind = new ChoiceBox<>();
        kind.getItems().addAll("INCOME", "EXPENSE");
        kind.getSelectionModel().select("EXPENSE".equalsIgnoreCase(existing.kind()) ? "EXPENSE" : "INCOME");

        Label balanceLabel = new Label();
        balanceLabel.getStyleClass().add("account-name");

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        try {
            account.getItems().setAll(accountRepo.list(userUid));
            for (AccountRepository.Account a : account.getItems()) {
                if (a.id().equals(existing.accountId())) {
                    account.getSelectionModel().select(a);
                }
            }
        } catch (Exception ignored) {
        }

        Runnable refreshSubcats = () -> {
            CategoryRepository.Category root = rootCategory.getValue();
            subCategory.getItems().clear();
            subCategory.setDisable(root == null);
            if (root == null) {
                return;
            }
            try {
                subCategory.getItems().add(null);
                subCategory.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
            } catch (Exception ignored) {
            }
            subCategory.getSelectionModel().selectFirst();
        };

        try {
            rootCategory.getItems().setAll(categoryRepo.listRoots(userUid));
            if (!rootCategory.getItems().isEmpty()) {
                rootCategory.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }
        rootCategory.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> refreshSubcats.run());
        refreshSubcats.run();

        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });
        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Sin subcategoría)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        TextField amount = new TextField(BigDecimal.valueOf(existing.amountCents(), 2).toPlainString());
        amount.setPrefWidth(340);

        TextField note = new TextField(existing.note() == null ? "" : existing.note());
        note.setPromptText("Nota (opcional)");
        note.setPrefWidth(340);

        Runnable refreshBalance = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);

            AccountRepository.Account a = account.getValue();
            if (a == null) {
                balanceLabel.setText("");
                return;
            }
            if (!"EXPENSE".equalsIgnoreCase(kind.getValue())) {
                balanceLabel.setText("");
                return;
            }
            try {
                long bal = accountRepo.computeBalanceCents(userUid, a.id());
                if (existing.accountId() != null && existing.accountId().equals(a.id())) {
                    long effect = "EXPENSE".equalsIgnoreCase(existing.kind()) ? -existing.amountCents() : existing.amountCents();
                    bal -= effect;
                }
                balanceLabel.setText("Saldo disponible: " + formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPrefWidth(480);

        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 0);
        grid.add(date, 1, 0);
        Label lAccount = new Label("Cuenta");
        lAccount.getStyleClass().add("account-name");
        grid.add(lAccount, 0, 1);
        grid.add(account, 1, 1);
        Label lRoot = new Label("Categoría");
        lRoot.getStyleClass().add("account-name");
        grid.add(lRoot, 0, 2);
        grid.add(rootCategory, 1, 2);
        Label lSub = new Label("Subcategoría");
        lSub.getStyleClass().add("account-name");
        grid.add(lSub, 0, 3);
        grid.add(subCategory, 1, 3);
        Label lKind = new Label("Tipo");
        lKind.getStyleClass().add("account-name");
        grid.add(lKind, 0, 4);
        grid.add(kind, 1, 4);

        Label lBalance = new Label("Disponible");
        lBalance.getStyleClass().add("account-name");
        grid.add(lBalance, 0, 5);
        grid.add(balanceLabel, 1, 5);

        Label lAmount = new Label("Monto");
        lAmount.getStyleClass().add("account-name");
        grid.add(lAmount, 0, 6);
        grid.add(amount, 1, 6);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 7);
        grid.add(note, 1, 7);

        grid.add(error, 0, 8, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Runnable selectExistingCategory = () -> {
            try {
                List<CategoryRepository.Category> all = categoryRepo.listAll(userUid);
                CategoryRepository.Category current = null;
                for (CategoryRepository.Category c : all) {
                    if (existing.categoryId().equals(c.id())) {
                        current = c;
                        break;
                    }
                }
                if (current == null) {
                    return;
                }

                if (current.parentId() == null) {
                    for (CategoryRepository.Category r : rootCategory.getItems()) {
                        if (r.id().equals(current.id())) {
                            rootCategory.getSelectionModel().select(r);
                            break;
                        }
                    }
                    refreshSubcats.run();
                    subCategory.getSelectionModel().selectFirst();
                    return;
                }

                CategoryRepository.Category root = null;
                for (CategoryRepository.Category c : all) {
                    if (current.parentId().equals(c.id())) {
                        root = c;
                        break;
                    }
                }
                if (root == null) {
                    return;
                }
                for (CategoryRepository.Category r : rootCategory.getItems()) {
                    if (r.id().equals(root.id())) {
                        rootCategory.getSelectionModel().select(r);
                        break;
                    }
                }
                refreshSubcats.run();
                for (CategoryRepository.Category sc : subCategory.getItems()) {
                    if (sc != null && sc.id().equals(current.id())) {
                        subCategory.getSelectionModel().select(sc);
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        };
        selectExistingCategory.run();

        account.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshBalance.run());
        kind.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshBalance.run());
        refreshBalance.run();

        javafx.scene.Node okNode = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okNode instanceof Button okBtn) {
            okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                error.setText("");
                error.setVisible(false);
                error.setManaged(false);

                if (date.getValue() == null || account.getValue() == null || rootCategory.getValue() == null) {
                    ev.consume();
                    return;
                }

                String raw = amount.getText() == null ? "" : amount.getText().trim();
                if (raw.isBlank()) {
                    ev.consume();
                    return;
                }

                long cents;
                try {
                    BigDecimal v = parseAmount(raw);
                    cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
                } catch (Exception ignored) {
                    ev.consume();
                    return;
                }
                if (cents < 0) {
                    ev.consume();
                    return;
                }

                if ("EXPENSE".equalsIgnoreCase(kind.getValue())) {
                    AccountRepository.Account a = account.getValue();
                    if (a != null) {
                        try {
                            long available = accountRepo.computeBalanceCents(userUid, a.id());
                            if (existing.accountId() != null && existing.accountId().equals(a.id())) {
                                long effect = "EXPENSE".equalsIgnoreCase(existing.kind()) ? -existing.amountCents() : existing.amountCents();
                                available -= effect;
                            }
                            if (cents > available) {
                                error.setText("El monto supera el saldo disponible.");
                                error.setVisible(true);
                                error.setManaged(true);
                                ev.consume();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        if (date.getValue() == null || account.getValue() == null || rootCategory.getValue() == null) {
            return Optional.empty();
        }

        String raw = amount.getText() == null ? "" : amount.getText().trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(raw);
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents < 0) {
            return Optional.empty();
        }

        long occurredAt = date.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        CategoryRepository.Category chosen = subCategory.getValue() != null ? subCategory.getValue() : rootCategory.getValue();
        return Optional.of(new NewTransaction(
            account.getValue().id(),
            chosen.id(),
            kind.getValue(),
            cents,
            occurredAt,
            note.getText() == null ? null : note.getText().trim()
        ));
    }

    private static Optional<NewAccount> showCreateAccountDialog(boolean darkTheme) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Nueva cuenta");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);

        dialog.getDialogPane().setMinWidth(560);
        dialog.getDialogPane().setPrefWidth(560);

        TextField name = new TextField();
        name.setPromptText("Ej: Banco X - Ahorros / Efectivo");
        name.setPrefWidth(360);

        ChoiceBox<String> type = new ChoiceBox<>();
        type.getItems().addAll("BANK", "CASH", "SAVINGS", "CREDIT", "INVESTMENT", "OTHER");
        type.getSelectionModel().selectFirst();

        ComboBox<String> currency = new ComboBox<>();
        currency.getItems().addAll(
            "COP",
            "USD",
            "EUR",
            "GBP",
            "MXN",
            "ARS",
            "CLP",
            "PEN",
            "VES"
        );
        currency.getSelectionModel().select("COP");
        currency.setPrefWidth(200);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(520);
        Label lName = new Label("Nombre");
        lName.getStyleClass().add("account-name");
        grid.add(lName, 0, 0);
        grid.add(name, 1, 0);
        Label lType = new Label("Tipo");
        lType.getStyleClass().add("account-name");
        grid.add(lType, 0, 1);
        grid.add(type, 1, 1);
        Label lCurrency = new Label("Moneda");
        lCurrency.getStyleClass().add("account-name");
        grid.add(lCurrency, 0, 2);
        grid.add(currency, 1, 2);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        String n = name.getText() == null ? "" : name.getText().trim();
        if (n.isBlank()) {
            return Optional.empty();
        }

        String t = type.getValue() == null ? "BANK" : type.getValue();
        String cur = currency.getValue() == null ? "" : currency.getValue().trim();
        if (cur.isBlank()) {
            cur = "COP";
        }

        return Optional.of(new NewAccount(n, t, cur));
    }

    private record NewGoal(
        String name,
        String currency,
        long targetCents,
        long targetDateEpochSec
    ) {
    }

    private record GoalTransfer(
        String otherAccountId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    private static void showGoalsDialog(
        AuthSession session,
        GoalRepository goalRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        String userUid = session.uid();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Metas");
        UiDialogs.applyAppTheme(dialog, darkTheme);

        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(720);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);

                        final double normalW = Math.min(1100, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(760, bounds.getHeight() * 0.90);
                        s.maximizedProperty().addListener((o, oldV, newV) -> {
                            if (Boolean.TRUE.equals(newV)) {
                                return;
                            }
                            try {
                                s.setWidth(normalW);
                                s.setHeight(normalH);
                                s.centerOnScreen();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Metas");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Crea metas con dinero real: cada meta se vincula a una cuenta de ahorro.");
        headerDesc.getStyleClass().add("text-secondary");
        VBox header = new VBox(2, headerTitle, headerDesc);

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox list = new VBox(10);
        list.getStyleClass().add("accounts-list");
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        AtomicReference<Runnable> refreshListRef = new AtomicReference<>();
        Runnable refreshList = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            list.getChildren().clear();

            try {
                List<GoalRepository.Goal> goals = goalRepo.listByUser(userUid);
                if (goals.isEmpty()) {
                    Label empty = new Label("Aún no tienes metas. Crea tu primera meta.");
                    empty.getStyleClass().add("text-secondary");
                    list.getChildren().add(empty);
                    return;
                }

                for (GoalRepository.Goal g : goals) {
                    final long savedCents = safeComputeBalanceCents(accountRepo, userUid, g.accountId());
                    long remaining = g.targetCents() - savedCents;

                    Label name = new Label(g.name());
                    name.getStyleClass().add("account-name");

                    Label goalAmount = new Label(formatMoney(g.targetCents(), g.currency()));
                    goalAmount.getStyleClass().addAll("account-name", "money-neutral");

                    Label savedAmount = new Label(formatMoney(savedCents, g.currency()));
                    savedAmount.getStyleClass().addAll("account-name", savedCents > 0 ? "money-positive" : "money-neutral");

                    Label remainingAmount = new Label(formatMoney(Math.max(0L, remaining), g.currency()));
                    remainingAmount.getStyleClass().addAll("account-name", remaining > 0 ? "money-negative" : "money-positive");

                    VBox goalBlock = new VBox(2, new Label("Objetivo"), goalAmount);
                    goalBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    goalBlock.getStyleClass().add("loan-amount-block");

                    VBox savedBlock = new VBox(2, new Label("Guardado"), savedAmount);
                    savedBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    savedBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-paid");

                    VBox remainingBlock = new VBox(2, new Label("Falta"), remainingAmount);
                    remainingBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    remainingBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-pending");

                    HBox amounts = new HBox(18, goalBlock, savedBlock, remainingBlock);
                    amounts.setAlignment(Pos.CENTER_LEFT);

                    Button deposit = new Button("Depositar");
                    deposit.getStyleClass().add("btn-primary");
                    deposit.setOnAction(ev -> {
                        Optional<GoalTransfer> t = showGoalDepositDialog(userUid, g, accountRepo, darkTheme);
                        if (t.isEmpty()) {
                            return;
                        }
                        try {
                            GoalTransfer gt = t.get();
                            String transferId = transferRepo.create(userUid, gt.otherAccountId(), g.accountId(), gt.amountCents(), gt.occurredAtEpochSec(), gt.note());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            Runnable r = refreshListRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    Button withdraw = new Button("Retirar");
                    withdraw.getStyleClass().add("btn-secondary");
                    withdraw.setOnAction(ev -> {
                        Optional<GoalTransfer> t = showGoalWithdrawDialog(userUid, g, accountRepo, darkTheme);
                        if (t.isEmpty()) {
                            return;
                        }
                        try {
                            GoalTransfer gt = t.get();
                            String transferId = transferRepo.create(userUid, g.accountId(), gt.otherAccountId(), gt.amountCents(), gt.occurredAtEpochSec(), gt.note());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            Runnable r = refreshListRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    Button delete = new Button("Eliminar");
                    delete.getStyleClass().add("btn-danger");
                    boolean canDelete = savedCents == 0L || savedCents >= g.targetCents();
                    delete.setDisable(!canDelete);
                    delete.setOnAction(ev -> {
                        if (!canDelete) {
                            Alert alert = buildAlert(
                                AlertType.WARNING,
                                "No se puede eliminar",
                                "Primero retira todo el dinero",
                                "Para eliminar la meta, el saldo guardado debe estar en 0 o la meta debe estar completada.\n\n" +
                                    "Guardado: " + formatMoney(savedCents, g.currency()),
                                darkTheme
                            );
                            alert.showAndWait();
                            return;
                        }
                        Dialog<ButtonType> confirm = new Dialog<>();
                        confirm.setTitle("Eliminar");
                        UiDialogs.applyAppTheme(confirm, darkTheme);
                        confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
                        confirm.setContentText("¿Eliminar la meta '" + g.name() + "'?\n\nNota: la cuenta vinculada no se eliminará automáticamente.");
                        confirm.showAndWait().ifPresent(btn -> {
                            if (btn != ButtonType.OK) {
                                return;
                            }
                            try {
                                goalRepo.delete(userUid, g.id());
                                try {
                                    AppConfig cfg = AppConfig.loadDefault();
                                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                    sync.deleteGoal(session, g.id());
                                } catch (Exception ignored) {
                                }
                                refreshBalances.run();
                                Runnable r = refreshListRef.get();
                                if (r != null) {
                                    r.run();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                    });

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox actions = new HBox(8, deposit, withdraw, delete);
                    actions.setAlignment(Pos.CENTER_RIGHT);

                    HBox top = new HBox(10, spacer, actions);
                    top.setAlignment(Pos.CENTER_LEFT);

                    VBox row = new VBox(8, name, amounts, top);
                    row.getStyleClass().add("account-item");
                    list.getChildren().add(row);
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "Error" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };

        Button create = new Button("Nueva meta");
        create.getStyleClass().add("btn-primary");
        create.setOnAction(e -> {
            Optional<NewGoal> ng = showCreateGoalDialog(darkTheme);
            if (ng.isEmpty()) {
                return;
            }
            try {
                NewGoal g = ng.get();
                AccountRepository.Account savings = accountRepo.create(userUid, "Meta: " + g.name(), "SAVINGS", g.currency());
                GoalRepository.Goal created = goalRepo.create(userUid, g.name(), g.currency(), g.targetCents(), g.targetDateEpochSec(), savings.id());

                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    sync.syncAccount(session, savings);
                    sync.syncGoal(session, created);
                } catch (Exception ignored) {
                }

                refreshBalances.run();
                Runnable r = refreshListRef.get();
                if (r != null) {
                    r.run();
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo crear la meta" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerBar = new HBox(12, header, headerSpacer, create);
        headerBar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, headerBar, scroll, error);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);

        refreshListRef.set(refreshList);
        refreshList.run();
        dialog.showAndWait();
    }

    private static Optional<NewGoal> showCreateGoalDialog(boolean darkTheme) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Nueva meta");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(640);
        dialog.getDialogPane().setPrefWidth(640);

        TextField name = new TextField();
        name.setPromptText("Ej: Viaje, Ahorro, Emergencias");

        ComboBox<String> currency = new ComboBox<>();
        currency.getItems().addAll(
            "COP",
            "USD",
            "EUR",
            "GBP",
            "MXN",
            "ARS",
            "CLP",
            "PEN",
            "VES"
        );
        currency.getSelectionModel().select("COP");

        TextField target = new TextField();
        target.setPromptText("Ej: 1000000.00");

        DatePicker targetDate = new DatePicker(LocalDate.now().plusMonths(1));

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(600);

        Label lName = new Label("Nombre");
        lName.getStyleClass().add("account-name");
        grid.add(lName, 0, 0);
        grid.add(name, 1, 0);

        Label lCurrency = new Label("Moneda");
        lCurrency.getStyleClass().add("account-name");
        grid.add(lCurrency, 0, 1);
        grid.add(currency, 1, 1);

        Label lTarget = new Label("Objetivo");
        lTarget.getStyleClass().add("account-name");
        grid.add(lTarget, 0, 2);
        grid.add(target, 1, 2);

        Label lDate = new Label("Fecha objetivo");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 3);
        grid.add(targetDate, 1, 3);

        grid.add(error, 0, 4, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> btn);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        String n = name.getText() == null ? "" : name.getText().trim();
        String cur = currency.getValue() == null ? "" : currency.getValue().trim().toUpperCase(Locale.ROOT);
        if (n.isBlank() || cur.isBlank() || targetDate.getValue() == null) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(target.getText());
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }

        long epoch = targetDate.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return Optional.of(new NewGoal(n, cur, cents, epoch));
    }

    private static void showLoansDialog(
        AuthSession session,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        AccountRepository accountRepo,
        boolean darkTheme
    ) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Préstamos");
        UiDialogs.applyAppTheme(dialog, darkTheme);

        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(720);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);

                        final double normalW = Math.min(1100, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(760, bounds.getHeight() * 0.90);
                        s.maximizedProperty().addListener((o, oldV, newV) -> {
                            if (Boolean.TRUE.equals(newV)) {
                                return;
                            }
                            try {
                                s.setWidth(normalW);
                                s.setHeight(normalH);
                                s.centerOnScreen();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Préstamos");
        headerTitle.getStyleClass().add("app-title");
        headerTitle.setStyle("-fx-font-size: 30px; -fx-font-weight: 800;");
        Label headerDesc = new Label("Registra préstamos y devoluciones.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setStyle("-fx-font-size: 14px;");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(96);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox lentList = new VBox(10);
        lentList.getStyleClass().add("accounts-list");
        ScrollPane lentScroll = new ScrollPane(lentList);
        lentScroll.setFitToWidth(true);
        lentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        lentScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(lentScroll, Priority.ALWAYS);

        VBox borrowedList = new VBox(10);
        borrowedList.getStyleClass().add("accounts-list");
        ScrollPane borrowedScroll = new ScrollPane(borrowedList);
        borrowedScroll.setFitToWidth(true);
        borrowedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        borrowedScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(borrowedScroll, Priority.ALWAYS);

        TabPane tabs = new TabPane();
        Tab tabLent = new Tab("Me deben", lentScroll);
        tabLent.setClosable(false);
        Tab tabBorrowed = new Tab("Yo debo", borrowedScroll);
        tabBorrowed.setClosable(false);

        VBox historyList = new VBox(10);
        historyList.getStyleClass().add("accounts-list");
        ScrollPane historyScroll = new ScrollPane(historyList);
        historyScroll.setFitToWidth(true);
        historyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        historyScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(historyScroll, Priority.ALWAYS);
        Tab tabHistory = new Tab("Historial", historyScroll);
        tabHistory.setClosable(false);

        tabs.getTabs().addAll(tabLent, tabBorrowed, tabHistory);
        tabs.getStyleClass().add("account-summary-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        AtomicReference<Runnable> refreshRef = new AtomicReference<>();
        Runnable refresh = () -> {
            lentList.getChildren().clear();
            borrowedList.getChildren().clear();
            historyList.getChildren().clear();
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            try {
                List<LoanRepository.Loan> lent = loanRepo.listByType(userUid, LoanRepository.TYPE_LENT, null, true);
                List<LoanRepository.Loan> borrowed = loanRepo.listByType(userUid, LoanRepository.TYPE_BORROWED, null, true);

                java.util.function.Consumer<List<LoanRepository.Loan>> render = (items) -> {
                    for (LoanRepository.Loan l : items) {
                        try {
                            long paidCents = loanPaymentRepo.sumPrincipalPaidCents(userUid, l.id());
                            long pendingCents = Math.max(0L, l.principalCents() - paidCents);
                            if (pendingCents <= 0L) {
                                continue;
                            }

                            Label name = new Label(l.counterpartyName());
                            name.getStyleClass().add("account-name");

                            Label totalValue = new Label(formatMoney(l.principalCents(), l.currency()));
                            totalValue.getStyleClass().addAll("account-name", "money-neutral");

                            Label paidValue = new Label(formatMoney(paidCents, l.currency()));
                            paidValue.getStyleClass().addAll("account-name", paidCents > 0 ? "money-positive" : "money-neutral");

                            Label pendingValue = new Label(formatMoney(pendingCents, l.currency()));
                            pendingValue.getStyleClass().addAll("account-name", pendingCents > 0 ? "money-negative" : "money-positive");

                            VBox totalBlock = new VBox(2, new Label("Total"), totalValue);
                            totalBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                            totalBlock.getStyleClass().add("loan-amount-block");
                            VBox paidBlock = new VBox(2, new Label("Pagado"), paidValue);
                            paidBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                            paidBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-paid");
                            VBox pendingBlock = new VBox(2, new Label("Pendiente"), pendingValue);
                            pendingBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                            pendingBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-pending");

                            HBox amounts = new HBox(18, totalBlock, paidBlock, pendingBlock);
                            amounts.setAlignment(Pos.CENTER_LEFT);

                            Button addPayment = new Button("Registrar pago");
                            addPayment.getStyleClass().add("btn-secondary");
                            addPayment.setOnAction(ev -> {
                                Optional<LoanPaymentDraft> draft = showRegisterLoanPaymentDialog(userUid, l, accountRepo, darkTheme, pendingCents);
                                if (draft.isEmpty()) {
                                    return;
                                }
                                try {
                                    LoanPaymentDraft d = draft.get();
                                    String paymentId = loanPaymentRepo.create(userUid, l.id(), d.accountId(), d.principalCents(), d.occurredAtEpochSec(), null, d.note());

                                    long newPaid = loanPaymentRepo.sumPrincipalPaidCents(userUid, l.id());
                                    long newPending = Math.max(0L, l.principalCents() - newPaid);
                                    if (newPending <= 0L) {
                                        loanRepo.update(userUid, l.id(), l.type(), l.counterpartyName(), l.principalCents(), l.currency(), LoanRepository.STATUS_CLOSED, l.notes());
                                    }

                                    try {
                                        AppConfig cfg = AppConfig.loadDefault();
                                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                        LoanPaymentRepository.LoanPayment p = loanPaymentRepo.getByIdOrNull(userUid, paymentId);
                                        if (p != null) {
                                            sync.syncLoanPayment(session, p);
                                        }
                                        LoanRepository.Loan updatedLoan = loanRepo.getByIdOrNull(userUid, l.id());
                                        if (updatedLoan != null) {
                                            sync.syncLoan(session, updatedLoan);
                                        }
                                    } catch (Exception ignored) {
                                    }

                                    Runnable r = refreshRef.get();
                                    if (r != null) {
                                        r.run();
                                    }
                                } catch (Exception ex) {
                                    error.setText(ex.getMessage() == null ? "No se pudo registrar el pago" : ex.getMessage());
                                    error.setVisible(true);
                                    error.setManaged(true);
                                }
                            });

                            Region spacer = new Region();
                            HBox.setHgrow(spacer, Priority.ALWAYS);
                            HBox actions = new HBox(10, spacer, addPayment);
                            actions.setAlignment(Pos.CENTER_RIGHT);

                            VBox row = new VBox(8, name, amounts, actions);
                            row.getStyleClass().add("account-item");

                            if (LoanRepository.TYPE_LENT.equals(l.type())) {
                                lentList.getChildren().add(row);
                            } else {
                                borrowedList.getChildren().add(row);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                };

                render.accept(lent);
                render.accept(borrowed);

                if (lentList.getChildren().isEmpty()) {
                    Label empty = new Label("No hay préstamos");
                    empty.getStyleClass().add("text-secondary");
                    lentList.getChildren().add(empty);
                }

                if (borrowedList.getChildren().isEmpty()) {
                    Label empty = new Label("No hay préstamos");
                    empty.getStyleClass().add("text-secondary");
                    borrowedList.getChildren().add(empty);
                }

                try {
                    List<LoanPaymentRepository.LoanPayment> payments = loanPaymentRepo.listAllByUser(userUid);
                    if (payments.isEmpty()) {
                        Label empty = new Label("No hay pagos registrados");
                        empty.getStyleClass().add("text-secondary");
                        historyList.getChildren().add(empty);
                    } else {
                        for (LoanPaymentRepository.LoanPayment p : payments) {
                            LoanRepository.Loan loan;
                            try {
                                loan = loanRepo.getByIdOrNull(userUid, p.loanId());
                            } catch (Exception ignored) {
                                loan = null;
                            }
                            String title = loan == null ? p.loanId() : loan.counterpartyName();
                            Label name = new Label(title);
                            name.getStyleClass().add("account-name");

                            String cur = loan == null ? "COP" : loan.currency();
                            Label amount = new Label(formatMoney(p.principalCents(), cur));
                            amount.getStyleClass().addAll("account-name", "money-neutral");

                            Region pSpacer = new Region();
                            HBox.setHgrow(pSpacer, Priority.ALWAYS);
                            HBox row = new HBox(10, name, pSpacer, amount);
                            row.getStyleClass().add("account-item");
                            row.setMinHeight(Region.USE_PREF_SIZE);
                            historyList.getChildren().add(row);
                        }
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudieron cargar los préstamos" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };

        refreshRef.set(refresh);

        Button create = new Button("Nuevo préstamo");
        create.getStyleClass().add("btn-primary");
        create.setOnAction(e -> {
            Optional<LoanDraft> draft = showCreateLoanDialog(darkTheme);
            if (draft.isEmpty()) {
                return;
            }
            try {
                LoanDraft d = draft.get();
                loanRepo.create(
                    userUid,
                    d.type(),
                    d.counterpartyName(),
                    null,
                    d.principalCents(),
                    d.currency(),
                    Instant.now().getEpochSecond(),
                    d.notes()
                );
                Runnable r = refreshRef.get();
                if (r != null) {
                    r.run();
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo crear el préstamo" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        VBox tabsWrap = new VBox(tabs);
        tabsWrap.getStyleClass().addAll("card", "content-card");
        tabsWrap.setPadding(new Insets(8, 10, 0, 10));
        VBox.setVgrow(tabsWrap, Priority.ALWAYS);

        HBox headerBar = new HBox(12, headerSpacer, create);
        headerBar.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, headerBar, tabsWrap, error);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);
        Runnable r = refreshRef.get();
        if (r != null) {
            r.run();
        }
        dialog.showAndWait();
    }

    private record LoanPaymentDraft(
        String accountId,
        long principalCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    private static Optional<LoanPaymentDraft> showRegisterLoanPaymentDialog(
        String userUid,
        LoanRepository.Loan loan,
        AccountRepository accountRepo,
        boolean darkTheme,
        long maxPendingCents
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Registrar pago");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(680);
        dialog.getDialogPane().setPrefWidth(680);

        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        try {
            account.getItems().addAll(accountRepo.list(userUid));
            if (!account.getItems().isEmpty()) {
                account.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                if (object == null) {
                    return "";
                }
                return object.name() + " · " + object.currency();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        DatePicker date = new DatePicker(LocalDate.now());

        TextField amount = new TextField();
        amount.setPromptText("Ej: 50000.00");

        TextField note = new TextField();
        note.setPromptText("Nota (opcional)");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(640);

        Label lLoan = new Label("Préstamo");
        lLoan.getStyleClass().add("account-name");
        grid.add(lLoan, 0, 0);
        grid.add(new Label(loan.counterpartyName() + " · " + loan.currency()), 1, 0);

        Label lAccount = new Label("Cuenta");
        lAccount.getStyleClass().add("account-name");
        grid.add(lAccount, 0, 1);
        grid.add(account, 1, 1);

        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 2);
        grid.add(date, 1, 2);

        Label lAmt = new Label("Monto");
        lAmt.getStyleClass().add("account-name");
        grid.add(lAmt, 0, 3);
        grid.add(amount, 1, 3);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 4);
        grid.add(note, 1, 4);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        if (account.getValue() == null || date.getValue() == null) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(amount.getText());
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents <= 0L || cents > maxPendingCents) {
            return Optional.empty();
        }

        long epoch = date.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        String n = note.getText() == null ? null : note.getText().trim();
        if (n != null && n.isBlank()) {
            n = null;
        }
        return Optional.of(new LoanPaymentDraft(account.getValue().id(), cents, epoch, n));
    }

    private record LoanDraft(
        String type,
        String counterpartyName,
        String currency,
        long principalCents,
        String notes
    ) {
    }

    private static Optional<LoanDraft> showCreateLoanDialog(boolean darkTheme) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Nuevo préstamo");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(640);
        dialog.getDialogPane().setPrefWidth(640);

        ChoiceBox<String> type = new ChoiceBox<>();
        type.getItems().addAll(LoanRepository.TYPE_LENT, LoanRepository.TYPE_BORROWED);
        type.getSelectionModel().selectFirst();

        TextField counterparty = new TextField();
        counterparty.setPromptText("Ej: Juan / Banco X");

        ComboBox<String> currency = new ComboBox<>();
        currency.getItems().addAll("COP", "USD", "EUR", "GBP", "MXN", "ARS", "CLP", "PEN", "VES");
        currency.getSelectionModel().select("COP");

        TextField amount = new TextField();
        amount.setPromptText("Ej: 100000.00");

        TextField notes = new TextField();
        notes.setPromptText("Nota (opcional)");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(600);

        Label lType = new Label("Tipo");
        lType.getStyleClass().add("account-name");
        grid.add(lType, 0, 0);
        grid.add(type, 1, 0);
        Label lCp = new Label("Persona/Entidad");
        lCp.getStyleClass().add("account-name");
        grid.add(lCp, 0, 1);
        grid.add(counterparty, 1, 1);
        Label lCur = new Label("Moneda");
        lCur.getStyleClass().add("account-name");
        grid.add(lCur, 0, 2);
        grid.add(currency, 1, 2);
        Label lAmt = new Label("Monto");
        lAmt.getStyleClass().add("account-name");
        grid.add(lAmt, 0, 3);
        grid.add(amount, 1, 3);
        Label lNotes = new Label("Nota");
        lNotes.getStyleClass().add("account-name");
        grid.add(lNotes, 0, 4);
        grid.add(notes, 1, 4);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        String t = type.getValue() == null ? LoanRepository.TYPE_LENT : type.getValue();
        String cp = counterparty.getText() == null ? "" : counterparty.getText().trim();
        String cur = currency.getValue() == null ? "" : currency.getValue().trim().toUpperCase(Locale.ROOT);
        if (cp.isBlank() || cur.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(amount.getText());
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents < 0) {
            return Optional.empty();
        }

        String n = notes.getText() == null ? null : notes.getText().trim();
        if (n != null && n.isBlank()) {
            n = null;
        }
        return Optional.of(new LoanDraft(t, cp, cur, cents, n));
    }

    private static void showBudgetDialog(
        AuthSession session,
        BudgetRepository budgetRepo,
        GoalRepository goalRepo,
        CategoryRepository categoryRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        showBudgetDialog(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme, refreshBalances, 0);
    }

    private static void showBudgetDialog(
        AuthSession session,
        BudgetRepository budgetRepo,
        GoalRepository goalRepo,
        CategoryRepository categoryRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        boolean darkTheme,
        Runnable refreshBalances,
        int initialTabIndex
    ) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Presupuesto");
        UiDialogs.applyAppTheme(dialog, darkTheme);

        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(720);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Presupuesto");
        headerTitle.getStyleClass().add("app-title");
        headerTitle.setStyle("-fx-font-size: 34px; -fx-font-weight: 800;");
        Label headerDesc = new Label("Presupuesto mensual y metas.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setStyle("-fx-font-size: 16px;");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(96);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("account-summary-tabs");
        VBox monthly = buildMonthlyBudgetPane(userUid, budgetRepo, categoryRepo);
        VBox goals = buildGoalsPane(session, goalRepo, accountRepo, transferRepo, darkTheme, refreshBalances);
        Tab tabMonthly = new Tab("Mensual", monthly);
        tabMonthly.setClosable(false);
        Tab tabGoals = new Tab("Metas", goals);
        tabGoals.setClosable(false);
        tabs.getTabs().addAll(tabMonthly, tabGoals);

        if (initialTabIndex >= 0 && initialTabIndex < tabs.getTabs().size()) {
            tabs.getSelectionModel().select(initialTabIndex);
        }

        VBox tabsWrap = new VBox(tabs);
        tabsWrap.getStyleClass().addAll("card", "content-card");
        tabsWrap.setPadding(new Insets(8, 10, 0, 10));
        VBox.setVgrow(tabsWrap, Priority.ALWAYS);

        VBox content = new VBox(12, tabsWrap);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private static VBox buildMonthlyBudgetPane(
        String userUid,
        BudgetRepository budgetRepo,
        CategoryRepository categoryRepo
    ) {
        ChoiceBox<String> month = new ChoiceBox<>();
        LocalDate now = LocalDate.now();
        String currentMonth = String.format("%04d-%02d", now.getYear(), now.getMonthValue());
        month.getItems().add(currentMonth);
        month.getSelectionModel().selectFirst();

        ComboBox<String> currency = new ComboBox<>();
        currency.getItems().addAll("COP", "USD", "EUR", "GBP", "MXN", "ARS", "CLP", "PEN", "VES");
        currency.getSelectionModel().select("COP");

        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();
        try {
            rootCategory.getItems().addAll(categoryRepo.listRoots(userUid));
            if (!rootCategory.getItems().isEmpty()) {
                rootCategory.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }

        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });
        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Categoría raíz)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshSubcategories = () -> {
            subCategory.getItems().clear();
            subCategory.getItems().add(null);
            CategoryRepository.Category root = rootCategory.getValue();
            if (root == null) {
                subCategory.getSelectionModel().selectFirst();
                return;
            }
            try {
                subCategory.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
            } catch (Exception ignored) {
            }
            subCategory.getSelectionModel().selectFirst();
        };

        TextField limit = new TextField();
        limit.setPromptText("Ej: 500000.00");

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox list = new VBox(10);
        list.getStyleClass().add("accounts-list");
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        AtomicReference<Runnable> refreshRef = new AtomicReference<>();
        Runnable refresh = () -> {
            list.getChildren().clear();
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            try {
                String m = month.getValue() == null ? currentMonth : month.getValue();
                String cur = currency.getValue() == null ? "COP" : currency.getValue();
                List<BudgetRepository.BudgetProgress> rows = budgetRepo.listProgressByMonthAndCurrency(userUid, m, cur);
                if (rows.isEmpty()) {
                    Label empty = new Label("No hay presupuestos");
                    empty.getStyleClass().add("text-secondary");
                    list.getChildren().add(empty);
                    return;
                }

                for (BudgetRepository.BudgetProgress p : rows) {
                    CategoryRepository.Category cat;
                    try {
                        cat = categoryRepo.getById(userUid, p.budget().categoryId());
                    } catch (Exception ex) {
                        cat = null;
                    }
                    String catName;
                    if (cat == null) {
                        catName = p.budget().categoryId();
                    } else if (cat.parentId() != null && !cat.parentId().isBlank()) {
                        try {
                            CategoryRepository.Category parent = categoryRepo.getById(userUid, cat.parentId());
                            String parentName = parent == null ? cat.parentId() : parent.name();
                            catName = parentName + " / " + cat.name();
                        } catch (Exception ignored) {
                            catName = cat.name();
                        }
                    } else {
                        catName = cat.name();
                    }

                    Label name = new Label(catName);
                    name.getStyleClass().add("account-name");

                    Label limitLabel = new Label(formatMoney(p.budget().limitCents(), p.budget().currency()));
                    limitLabel.getStyleClass().addAll("account-name", "money-neutral");

                    Label spentLabel = new Label(formatMoney(p.spentCents(), p.budget().currency()));
                    spentLabel.getStyleClass().addAll("account-name", p.spentCents() > 0 ? "money-negative" : "money-neutral");

                    long remaining = p.remainingCents();
                    Label remainingLabel = new Label(formatMoney(remaining, p.budget().currency()));
                    remainingLabel.getStyleClass().addAll("account-name", remaining >= 0 ? "money-positive" : "money-negative");

                    VBox limitBlock = new VBox(2, new Label("Límite"), limitLabel);
                    limitBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    limitBlock.getStyleClass().add("loan-amount-block");

                    VBox spentBlock = new VBox(2, new Label("Gastado"), spentLabel);
                    spentBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    spentBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-pending");

                    VBox remainingBlock = new VBox(2, new Label("Disponible"), remainingLabel);
                    remainingBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    remainingBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-paid");

                    HBox amounts = new HBox(18, limitBlock, spentBlock, remainingBlock);
                    amounts.setAlignment(Pos.CENTER_LEFT);

                    Button del = new Button("Eliminar");
                    del.getStyleClass().add("btn-danger");
                    del.setOnAction(ev -> {
                        try {
                            budgetRepo.delete(userUid, p.budget().id());
                            Runnable r = refreshRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ex) {
                            error.setText(ex.getMessage() == null ? "No se pudo eliminar el límite" : ex.getMessage());
                            error.setVisible(true);
                            error.setManaged(true);
                        }
                    });
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox top = new HBox(10, name, spacer, del);
                    top.setAlignment(Pos.CENTER_LEFT);

                    VBox row = new VBox(8, top, amounts);
                    row.getStyleClass().add("account-item");
                    list.getChildren().add(row);
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo cargar el presupuesto" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };

        refreshRef.set(refresh);

        Button upsert = new Button("Guardar límite");
        upsert.getStyleClass().add("btn-primary");
        upsert.setOnAction(e -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            try {
                CategoryRepository.Category root = rootCategory.getValue();
                if (root == null) {
                    return;
                }
                String m = month.getValue() == null ? currentMonth : month.getValue();
                String cur = currency.getValue() == null ? "COP" : currency.getValue().trim().toUpperCase(Locale.ROOT);
                BigDecimal v = parseAmount(limit.getText());
                long cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
                if (cents < 0) {
                    return;
                }
                CategoryRepository.Category sub = subCategory.getValue();
                String categoryId = (sub == null) ? root.id() : sub.id();
                BudgetRepository.Budget existing = budgetRepo.getByUniqueKeyOrNull(userUid, m, cur, categoryId);
                if (existing == null) {
                    budgetRepo.create(userUid, m, categoryId, cents, cur);
                } else {
                    budgetRepo.update(userUid, existing.id(), m, categoryId, cents, cur);
                }
                refresh.run();
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo guardar el límite" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });

        Label fMonth = new Label("Mes");
        fMonth.getStyleClass().add("account-name");
        Label fCur = new Label("Moneda");
        fCur.getStyleClass().add("account-name");
        Label fRoot = new Label("Categoría");
        fRoot.getStyleClass().add("account-name");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("account-name");
        Label fLimit = new Label("Límite");
        fLimit.getStyleClass().add("account-name");

        month.setPrefWidth(120);
        currency.setPrefWidth(110);
        rootCategory.setPrefWidth(240);
        subCategory.setPrefWidth(240);
        limit.setPrefWidth(160);

        HBox filters = new HBox(10,
            fMonth, month,
            fCur, currency,
            fRoot, rootCategory,
            fSub, subCategory,
            fLimit, limit,
            upsert
        );
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setPadding(new Insets(10));
        filters.getStyleClass().addAll("card", "content-card");

        month.valueProperty().addListener((obs, o, n) -> refresh.run());
        currency.valueProperty().addListener((obs, o, n) -> refresh.run());
        rootCategory.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            refreshSubcategories.run();
            refresh.run();
        });
        subCategory.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refresh.run());

        refreshSubcategories.run();
        refresh.run();
        VBox out = new VBox(12, filters, scroll, error);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return out;
    }

    private static VBox buildGoalsPane(
        AuthSession session,
        GoalRepository goalRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        String userUid = session.uid();

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox list = new VBox(10);
        list.getStyleClass().add("accounts-list");
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        AtomicReference<Runnable> refreshListRef = new AtomicReference<>();
        Runnable refreshList = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            list.getChildren().clear();

            try {
                List<GoalRepository.Goal> goals = goalRepo.listByUser(userUid);
                if (goals.isEmpty()) {
                    Label empty = new Label("Aún no tienes metas. Crea tu primera meta.");
                    empty.getStyleClass().add("text-secondary");
                    list.getChildren().add(empty);
                    return;
                }

                for (GoalRepository.Goal g : goals) {
                    final long savedCents = safeComputeBalanceCents(accountRepo, userUid, g.accountId());
                    long remaining = g.targetCents() - savedCents;

                    Label name = new Label(g.name());
                    name.getStyleClass().add("account-name");

                    Label goalAmount = new Label(formatMoney(g.targetCents(), g.currency()));
                    goalAmount.getStyleClass().addAll("account-name", "money-neutral");

                    Label savedAmount = new Label(formatMoney(savedCents, g.currency()));
                    savedAmount.getStyleClass().addAll("account-name", savedCents > 0 ? "money-positive" : "money-neutral");

                    Label remainingAmount = new Label(formatMoney(Math.max(0L, remaining), g.currency()));
                    remainingAmount.getStyleClass().addAll("account-name", remaining > 0 ? "money-negative" : "money-positive");

                    VBox goalBlock = new VBox(2, new Label("Objetivo"), goalAmount);
                    goalBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    goalBlock.getStyleClass().add("loan-amount-block");

                    VBox savedBlock = new VBox(2, new Label("Guardado"), savedAmount);
                    savedBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    savedBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-paid");

                    VBox remainingBlock = new VBox(2, new Label("Falta"), remainingAmount);
                    remainingBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    remainingBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-pending");

                    HBox amounts = new HBox(18, goalBlock, savedBlock, remainingBlock);
                    amounts.setAlignment(Pos.CENTER_LEFT);

                    Button deposit = new Button("Depositar");
                    deposit.getStyleClass().add("btn-primary");
                    deposit.setOnAction(ev -> {
                        Optional<GoalTransfer> t = showGoalDepositDialog(userUid, g, accountRepo, darkTheme);
                        if (t.isEmpty()) {
                            return;
                        }
                        try {
                            GoalTransfer gt = t.get();
                            String transferId = transferRepo.create(userUid, gt.otherAccountId(), g.accountId(), gt.amountCents(), gt.occurredAtEpochSec(), gt.note());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            Runnable r = refreshListRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    Button withdraw = new Button("Retirar");
                    withdraw.getStyleClass().add("btn-secondary");
                    withdraw.setOnAction(ev -> {
                        Optional<GoalTransfer> t = showGoalWithdrawDialog(userUid, g, accountRepo, darkTheme);
                        if (t.isEmpty()) {
                            return;
                        }
                        try {
                            GoalTransfer gt = t.get();
                            String transferId = transferRepo.create(userUid, g.accountId(), gt.otherAccountId(), gt.amountCents(), gt.occurredAtEpochSec(), gt.note());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            Runnable r = refreshListRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    Button delete = new Button("Eliminar");
                    delete.getStyleClass().add("btn-danger");
                    boolean canDelete = savedCents == 0L || savedCents >= g.targetCents();
                    delete.setDisable(!canDelete);
                    delete.setOnAction(ev -> {
                        if (!canDelete) {
                            Alert alert = buildAlert(
                                AlertType.WARNING,
                                "No se puede eliminar",
                                "Primero retira todo el dinero",
                                "Para eliminar la meta, el saldo guardado debe estar en 0 o la meta debe estar completada.\n\n" +
                                    "Guardado: " + formatMoney(savedCents, g.currency()),
                                darkTheme
                            );
                            alert.showAndWait();
                            return;
                        }
                        Dialog<ButtonType> confirm = new Dialog<>();
                        confirm.setTitle("Eliminar");
                        UiDialogs.applyAppTheme(confirm, darkTheme);
                        confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
                        confirm.setContentText("¿Eliminar la meta '" + g.name() + "'?\n\nNota: la cuenta vinculada no se eliminará automáticamente.");
                        confirm.showAndWait().ifPresent(btn -> {
                            if (btn != ButtonType.OK) {
                                return;
                            }
                            try {
                                goalRepo.delete(userUid, g.id());
                                try {
                                    AppConfig cfg = AppConfig.loadDefault();
                                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                    sync.deleteGoal(session, g.id());
                                } catch (Exception ignored) {
                                }
                                refreshBalances.run();
                                Runnable r = refreshListRef.get();
                                if (r != null) {
                                    r.run();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                    });

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox actions = new HBox(8, deposit, withdraw, delete);
                    actions.setAlignment(Pos.CENTER_RIGHT);

                    HBox top = new HBox(10, spacer, actions);
                    top.setAlignment(Pos.CENTER_LEFT);

                    VBox row = new VBox(8, name, amounts, top);
                    row.getStyleClass().add("account-item");
                    list.getChildren().add(row);
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "Error" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };
        refreshListRef.set(refreshList);

        Button create = new Button("Nueva meta");
        create.getStyleClass().add("btn-primary");
        create.setOnAction(e -> {
            Optional<NewGoal> ng = showCreateGoalDialog(darkTheme);
            if (ng.isEmpty()) {
                return;
            }
            try {
                NewGoal g = ng.get();
                AccountRepository.Account savings = accountRepo.create(userUid, "Meta: " + g.name(), "SAVINGS", g.currency());
                GoalRepository.Goal created = goalRepo.create(userUid, g.name(), g.currency(), g.targetCents(), g.targetDateEpochSec(), savings.id());
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    sync.syncAccount(session, savings);
                    sync.syncGoal(session, created);
                } catch (Exception ignored) {
                }
                refreshBalances.run();
                Runnable r = refreshListRef.get();
                if (r != null) {
                    r.run();
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo crear la meta" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerBar = new HBox(12, headerSpacer, create);
        headerBar.setAlignment(Pos.CENTER_RIGHT);

        refreshList.run();
        VBox out = new VBox(12, headerBar, scroll, error);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return out;
    }

    private static Optional<GoalTransfer> showGoalDepositDialog(
        String userUid,
        GoalRepository.Goal goal,
        AccountRepository accountRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Depositar a meta");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(700);
        dialog.getDialogPane().setPrefWidth(700);

        DatePicker date = new DatePicker(LocalDate.now());
        ChoiceBox<AccountRepository.Account> from = new ChoiceBox<>();
        Label balance = new Label("");
        balance.getStyleClass().add("text-secondary");
        TextField amount = new TextField();
        amount.setPromptText("Ej: 10000.00");
        TextField note = new TextField();
        note.setPromptText("Nota (opcional)");

        try {
            List<AccountRepository.Account> accounts = accountRepo.list(userUid);
            for (AccountRepository.Account a : accounts) {
                if (a == null) {
                    continue;
                }
                if (goal.accountId().equals(a.id())) {
                    continue;
                }
                if (!goal.currency().equalsIgnoreCase(a.currency())) {
                    continue;
                }
                from.getItems().add(a);
            }
            if (!from.getItems().isEmpty()) {
                from.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }

        from.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));

        Label lGoal = new Label("Meta");
        lGoal.getStyleClass().add("account-name");
        grid.add(lGoal, 0, 0);
        grid.add(new Label(goal.name()), 1, 0);

        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 1);
        grid.add(date, 1, 1);

        Label lFrom = new Label("Desde");
        lFrom.getStyleClass().add("account-name");
        grid.add(lFrom, 0, 2);
        grid.add(from, 1, 2);

        grid.add(balance, 1, 3);

        Label lAmount = new Label("Monto");
        lAmount.getStyleClass().add("account-name");
        grid.add(lAmount, 0, 4);
        grid.add(amount, 1, 4);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 5);
        grid.add(note, 1, 5);

        dialog.getDialogPane().setContent(grid);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }
        if (date.getValue() == null || from.getValue() == null) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(amount.getText());
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }

        long epoch = date.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return Optional.of(new GoalTransfer(from.getValue().id(), cents, epoch, note.getText()));
    }

    private static Optional<GoalTransfer> showGoalWithdrawDialog(
        String userUid,
        GoalRepository.Goal goal,
        AccountRepository accountRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Retirar de meta");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(700);
        dialog.getDialogPane().setPrefWidth(700);

        DatePicker date = new DatePicker(LocalDate.now());
        ChoiceBox<AccountRepository.Account> to = new ChoiceBox<>();
        TextField amount = new TextField();
        amount.setPromptText("Ej: 10000.00");
        TextField note = new TextField();
        note.setPromptText("Nota (opcional)");

        try {
            List<AccountRepository.Account> accounts = accountRepo.list(userUid);
            for (AccountRepository.Account a : accounts) {
                if (a == null) {
                    continue;
                }
                if (goal.accountId().equals(a.id())) {
                    continue;
                }
                if (!goal.currency().equalsIgnoreCase(a.currency())) {
                    continue;
                }
                to.getItems().add(a);
            }
            if (!to.getItems().isEmpty()) {
                to.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }

        to.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));

        Label lGoal = new Label("Meta");
        lGoal.getStyleClass().add("account-name");
        grid.add(lGoal, 0, 0);
        grid.add(new Label(goal.name()), 1, 0);

        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 1);
        grid.add(date, 1, 1);

        Label lTo = new Label("Hacia");
        lTo.getStyleClass().add("account-name");
        grid.add(lTo, 0, 2);
        grid.add(to, 1, 2);

        Label lAmount = new Label("Monto");
        lAmount.getStyleClass().add("account-name");
        grid.add(lAmount, 0, 3);
        grid.add(amount, 1, 3);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 4);
        grid.add(note, 1, 4);

        dialog.getDialogPane().setContent(grid);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }
        if (date.getValue() == null || to.getValue() == null) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = parseAmount(amount.getText());
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }

        long epoch = date.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return Optional.of(new GoalTransfer(to.getValue().id(), cents, epoch, note.getText()));
    }

    private static Optional<EditAccountResult> showEditAccountDialog(AccountRepository.Account existing, boolean darkTheme) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Editar cuenta");
        ButtonType deleteBtn = new ButtonType("Eliminar", ButtonBar.ButtonData.LEFT);
        ButtonType summaryBtn = new ButtonType("Resumen", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(deleteBtn, summaryBtn, ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);

        Platform.runLater(() -> {
            try {
                Button bDelete = (Button) dialog.getDialogPane().lookupButton(deleteBtn);
                if (bDelete != null) {
                    bDelete.getStyleClass().removeAll("btn-primary", "btn-secondary", "btn-accent");
                    bDelete.getStyleClass().add("btn-danger");
                    bDelete.setMinWidth(130);
                }

                Button bSummary = (Button) dialog.getDialogPane().lookupButton(summaryBtn);
                if (bSummary != null) {
                    bSummary.getStyleClass().removeAll("btn-primary", "btn-danger");
                    bSummary.getStyleClass().addAll("btn-secondary", "btn-accent");
                    bSummary.setMinWidth(150);
                }
            } catch (Exception ignored) {
            }
        });

        dialog.getDialogPane().setMinWidth(560);
        dialog.getDialogPane().setPrefWidth(560);

        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("Ej: Banco X - Ahorros / Efectivo");
        name.setPrefWidth(360);

        ChoiceBox<String> type = new ChoiceBox<>();
        type.getItems().addAll("BANK", "CASH");
        if (existing != null && existing.type() != null && type.getItems().contains(existing.type())) {
            type.getSelectionModel().select(existing.type());
        } else {
            type.getSelectionModel().selectFirst();
        }
        type.setDisable(true);

        TextField currency = new TextField(existing == null ? "COP" : existing.currency());
        currency.setPrefWidth(200);
        currency.setDisable(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(520);
        Label lName = new Label("Nombre");
        lName.getStyleClass().add("account-name");
        grid.add(lName, 0, 0);
        grid.add(name, 1, 0);
        Label lType = new Label("Tipo");
        lType.getStyleClass().add("account-name");
        grid.add(lType, 0, 1);
        grid.add(type, 1, 1);
        Label lCurrency = new Label("Moneda");
        lCurrency.getStyleClass().add("account-name");
        grid.add(lCurrency, 0, 2);
        grid.add(currency, 1, 2);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        if (result.get() == deleteBtn) {
            return Optional.of(new EditAccountResult(EditAccountAction.DELETE, null));
        }
        if (result.get() == summaryBtn) {
            return Optional.of(new EditAccountResult(EditAccountAction.VIEW_SUMMARY, null));
        }
        if (result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        String n = name.getText() == null ? "" : name.getText().trim();
        if (n.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new EditAccountResult(EditAccountAction.SAVE, n));
    }

    private static void refreshBalances(
        AuthSession session,
        AccountRepository accountRepo,
        GoalRepository goalRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        Label totalValue,
        VBox accountsBox,
        VBox goalsBox,
        BooleanProperty darkTheme,
        AtomicBoolean hideTotalBalance,
        Runnable openBudgetGoalsTab
    ) {
        accountsBox.getChildren().clear();
        goalsBox.getChildren().clear();

        Parent goalsParent = goalsBox.getParent();
        if (goalsParent instanceof ScrollPane sp) {
            sp.setVisible(false);
            sp.setManaged(false);
        }
        try {
            List<AccountRepository.Account> accounts = accountRepo.list(session.uid());
            if (accounts.isEmpty()) {
                totalValue.setText(hideTotalBalance.get() ? "••••" : formatMoney(0));
                totalValue.getStyleClass().setAll("account-name", "dashboard-total-value", "money-neutral");
                Label empty = new Label("No hay cuentas creadas aún.");
                empty.getStyleClass().add("text-secondary");
                accountsBox.getChildren().add(empty);
                return;
            }

            java.util.Set<String> goalAccountIds = new java.util.HashSet<>();
            java.util.Map<String, GoalRepository.Goal> goalByAccount = new java.util.HashMap<>();
            try {
                List<GoalRepository.Goal> goals = goalRepo.listByUser(session.uid());
                for (GoalRepository.Goal g : goals) {
                    if (g == null || g.accountId() == null || g.accountId().isBlank()) {
                        continue;
                    }
                    goalAccountIds.add(g.accountId());
                    goalByAccount.put(g.accountId(), g);
                }
            } catch (Exception ignored) {
            }

            long totalCents = 0L;
            for (AccountRepository.Account a : accounts) {
                long balance = accountRepo.computeBalanceCents(session.uid(), a.id());
                totalCents += balance;

                if (goalAccountIds.contains(a.id())) {
                    continue;
                }

                if (accountsBox.getChildren().isEmpty()) {
                    Label hdr = new Label("Cuentas");
                    hdr.getStyleClass().add("account-name");
                    accountsBox.getChildren().add(hdr);
                }

                Label name = new Label(a.name());
                name.getStyleClass().add("account-name");

                Label amount = new Label(formatMoney(balance, a.currency()));
                if (balance > 0) {
                    amount.getStyleClass().add("money-positive");
                } else if (balance < 0) {
                    amount.getStyleClass().add("money-negative");
                } else {
                    amount.getStyleClass().add("money-neutral");
                }

                TextField realAmount = new TextField();
                realAmount.setPromptText("0,00");
                realAmount.setPrefWidth(140);
                realAmount.getStyleClass().add("text-field");

                Button diff = new Button("");
                diff.getStyleClass().addAll("account-name", "money-neutral");
                diff.setFocusTraversable(false);
                diff.setTooltip(new Tooltip("Clic para copiar"));

                final long[] lastDeltaCents = new long[] { 0L };
                final boolean[] lastDeltaValid = new boolean[] { false };

                Runnable copyDelta = () -> {
                    if (!lastDeltaValid[0]) {
                        return;
                    }
                    String txt = formatSignedUserDecimal(lastDeltaCents[0]);
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(txt);
                    boolean ok = Clipboard.getSystemClipboard().setContent(cc);
                    if (!ok) {
                        return;
                    }
                    Tooltip copied = new Tooltip("Copiado");
                    var p = diff.localToScreen(diff.getWidth() / 2.0, diff.getHeight() / 2.0);
                    if (p != null) {
                        copied.show(diff, p.getX(), p.getY());
                        PauseTransition pt = new PauseTransition(Duration.seconds(1.0));
                        pt.setOnFinished(e -> copied.hide());
                        pt.play();
                    }
                };

                diff.setOnAction(ae -> copyDelta.run());

                ContextMenu diffMenu = new ContextMenu();
                MenuItem copyDiff = new MenuItem("Copiar");
                copyDiff.setOnAction(ae -> copyDelta.run());
                diffMenu.getItems().add(copyDiff);
                diff.setContextMenu(diffMenu);

                UnaryOperator<TextFormatter.Change> filter = ch -> {
                    String next = ch.getControlNewText();
                    if (next == null || next.isEmpty()) {
                        return ch;
                    }
                    if (!next.matches("[0-9.,]*")) {
                        return null;
                    }

                    int dot = next.indexOf('.');
                    int comma = next.indexOf(',');
                    if (dot >= 0 && comma >= 0) {
                        return null;
                    }
                    int sep = Math.max(dot, comma);
                    if (sep >= 0) {
                        String decimals = next.substring(sep + 1);
                        if (decimals.length() > 2) {
                            return null;
                        }
                    }
                    return ch;
                };
                realAmount.setTextFormatter(new TextFormatter<>(filter));

                Runnable refreshDiff = () -> {
                    Long entered = parseUserDecimalToCents(realAmount.getText());
                    if (entered == null) {
                        diff.setText("");
                        diff.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                        diff.getStyleClass().add("money-neutral");
                        lastDeltaValid[0] = false;
                        return;
                    }
                    long delta = entered - balance;
                    diff.setText(formatMoney(delta, a.currency()));
                    lastDeltaCents[0] = delta;
                    lastDeltaValid[0] = true;
                    diff.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                    if (delta > 0) {
                        diff.getStyleClass().add("money-positive");
                    } else if (delta < 0) {
                        diff.getStyleClass().add("money-negative");
                    } else {
                        diff.getStyleClass().add("money-neutral");
                    }
                };
                realAmount.textProperty().addListener((obs, o, n) -> refreshDiff.run());
                realAmount.focusedProperty().addListener((obs, o, focused) -> {
                    if (Boolean.TRUE.equals(focused)) {
                        return;
                    }
                    Long entered = parseUserDecimalToCents(realAmount.getText());
                    if (entered == null) {
                        return;
                    }
                    realAmount.setText(formatUserDecimal(entered));
                });
                refreshDiff.run();

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox row = new HBox(10, name, spacer, realAmount, diff, amount);
                row.getStyleClass().add("account-item");
                row.setMinHeight(Region.USE_PREF_SIZE);
                row.setOnMouseClicked(ev -> {
                    if (ev.getTarget() instanceof TextField) {
                        return;
                    }
                    if (ev.getButton() != MouseButton.PRIMARY || ev.getClickCount() != 2) {
                        return;
                    }
                    Optional<EditAccountResult> res = showEditAccountDialog(a, darkTheme.get());
                    if (res.isEmpty()) {
                        return;
                    }
                    try {
                        if (res.get().action() == EditAccountAction.SAVE) {
                            AccountRepository.Account updated = accountRepo.updateName(session.uid(), a.id(), res.get().newName());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncAccount(session, updated);
                            } catch (Exception ignored) {
                            }
                            refreshBalances(session, accountRepo, goalRepo, txRepo, transferRepo, totalValue, accountsBox, goalsBox, darkTheme, hideTotalBalance, openBudgetGoalsTab);
                        } else if (res.get().action() == EditAccountAction.VIEW_SUMMARY) {
                            showAccountSummaryDialog(session.uid(), a, txRepo, transferRepo, accountRepo, darkTheme.get());
                        } else if (res.get().action() == EditAccountAction.DELETE) {
                            Alert confirm = buildAlert(
                                AlertType.CONFIRMATION,
                                "Eliminar cuenta",
                                "¿Eliminar la cuenta?",
                                "Cuenta: " + a.name() + "\n\n" +
                                    "Esta acción no se puede deshacer.",
                                darkTheme.get()
                            );
                            Optional<ButtonType> c = confirm.showAndWait();
                            if (c.isEmpty() || c.get() != ButtonType.OK) {
                                return;
                            }
                            accountRepo.delete(session.uid(), a.id());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.deleteAccount(session, a.id());
                            } catch (Exception ignored) {
                            }
                            refreshBalances(session, accountRepo, goalRepo, txRepo, transferRepo, totalValue, accountsBox, goalsBox, darkTheme, hideTotalBalance, openBudgetGoalsTab);
                        }
                    } catch (IllegalStateException ex) {
                        if ("account_has_movements".equals(ex.getMessage())) {
                            Alert err = buildAlert(
                                AlertType.ERROR,
                                "No se puede eliminar",
                                "Esta cuenta tiene movimientos",
                                "Cuenta: " + a.name() + "\n\n" +
                                    "Para eliminarla, primero borra sus transacciones y transferencias asociadas, " +
                                    "o mueve el saldo a otra cuenta.",
                                darkTheme.get()
                            );
                            err.showAndWait();
                        }
                    } catch (Exception ex) {
                        Alert err = buildAlert(
                            AlertType.ERROR,
                            "Error",
                            "No se pudo actualizar la cuenta",
                            ex.getMessage() == null ? "Error" : ex.getMessage(),
                            darkTheme.get()
                        );
                        err.showAndWait();
                    }
                });

                accountsBox.getChildren().add(row);
            }

            if (!goalAccountIds.isEmpty()) {
                Label hdrGoals = new Label("Metas");
                hdrGoals.getStyleClass().add("account-name");
                goalsBox.getChildren().add(hdrGoals);

                for (AccountRepository.Account a : accounts) {
                    if (!goalAccountIds.contains(a.id())) {
                        continue;
                    }
                    long balance = accountRepo.computeBalanceCents(session.uid(), a.id());

                    GoalRepository.Goal g = goalByAccount.get(a.id());
                    String displayName = g == null ? a.name() : g.name();

                    Label name = new Label(displayName);
                    name.getStyleClass().add("account-name");

                    Label amount = new Label(formatMoney(balance, a.currency()));
                    amount.getStyleClass().add(balance > 0 ? "money-positive" : (balance < 0 ? "money-negative" : "money-neutral"));

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox row = new HBox(10, name, spacer, amount);
                    row.getStyleClass().add("account-item");
                    row.setMinHeight(Region.USE_PREF_SIZE);
                    row.setOnMouseClicked(ev -> {
                        if (ev.getButton() != MouseButton.PRIMARY || ev.getClickCount() != 2) {
                            return;
                        }
                        openBudgetGoalsTab.run();
                    });
                    goalsBox.getChildren().add(row);
                }
            }

            if (goalsParent instanceof ScrollPane sp) {
                boolean showGoals = !goalAccountIds.isEmpty();
                sp.setVisible(showGoals);
                sp.setManaged(showGoals);
            }

            String totalCurrency = null;
            boolean mixed = false;
            for (AccountRepository.Account a : accounts) {
                if (totalCurrency == null) {
                    totalCurrency = a.currency();
                } else if (a.currency() != null && !a.currency().equalsIgnoreCase(totalCurrency)) {
                    mixed = true;
                    break;
                }
            }
            totalValue.setText(hideTotalBalance.get() ? "••••" : formatMoney(totalCents, mixed ? null : totalCurrency));
            totalValue.getStyleClass().setAll("account-name", "dashboard-total-value");
            if (totalCents > 0) {
                totalValue.getStyleClass().add("money-positive");
            } else if (totalCents < 0) {
                totalValue.getStyleClass().add("money-negative");
            } else {
                totalValue.getStyleClass().add("money-neutral");
            }
            if (hideTotalBalance.get()) {
                totalValue.getStyleClass().removeAll("money-positive", "money-negative");
                totalValue.getStyleClass().add("money-neutral");
            }
        } catch (Exception ex) {
            totalValue.setText("No se pudo cargar saldos.");
            totalValue.getStyleClass().setAll("account-name", "dashboard-total-value", "text-danger");
            accountsBox.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
        }
    }

    private static Long parseUserDecimalToCents(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }

        s = s.replace(" ", "");
        boolean hasDot = s.indexOf('.') >= 0;
        boolean hasComma = s.indexOf(',') >= 0;
        if (hasDot && hasComma) {
            return null;
        }

        char decSep = hasComma ? ',' : (hasDot ? '.' : 0);
        String intPart;
        String decPart;
        if (decSep == 0) {
            intPart = s;
            decPart = "";
        } else {
            int idx = s.indexOf(decSep);
            intPart = s.substring(0, idx);
            decPart = s.substring(idx + 1);
        }

        if (intPart.isEmpty()) {
            intPart = "0";
        }
        if (!intPart.matches("[0-9]+")) {
            return null;
        }
        if (!decPart.matches("[0-9]*")) {
            return null;
        }
        if (decPart.length() > 2) {
            return null;
        }

        long whole;
        try {
            whole = Long.parseLong(intPart);
        } catch (NumberFormatException ex) {
            return null;
        }

        int dec = 0;
        if (decPart.length() == 1) {
            dec = Integer.parseInt(decPart) * 10;
        } else if (decPart.length() == 2) {
            dec = Integer.parseInt(decPart);
        }
        return whole * 100L + dec;
    }

    private static String formatUserDecimal(long cents) {
        BigDecimal v = BigDecimal.valueOf(Math.abs(cents), 2);
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.forLanguageTag("es-CO"));
        DecimalFormat df = new DecimalFormat("#,##0.00", sym);
        df.setGroupingUsed(true);
        return df.format(v);
    }

    private static String formatSignedUserDecimal(long cents) {
        if (cents < 0) {
            return "-" + formatUserDecimal(cents);
        }
        return formatUserDecimal(cents);
    }

    private static String formatMoney(long cents) {
        return formatMoney(cents, "COP");
    }

    private static String formatMoney(long cents, String currencyCode) {
        boolean neg = cents < 0;
        BigDecimal v = BigDecimal.valueOf(Math.abs(cents), 2);

        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.forLanguageTag("es-CO"));
        DecimalFormat df = new DecimalFormat("#,##0.00", sym);
        df.setGroupingUsed(true);

        String symbol = currencySymbol(currencyCode);
        return (neg ? "-" : "") + symbol + df.format(v);
    }

    private static String currencySymbol(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return "$";
        }
        String c = currencyCode.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "COP" -> "$";
            case "USD" -> "US$";
            case "EUR" -> "€";
            case "GBP" -> "£";
            case "MXN" -> "MX$";
            case "ARS" -> "AR$";
            case "CLP" -> "CL$";
            case "PEN" -> "S/";
            default -> c + " ";
        };
    }

    private static long safeComputeBalanceCents(AccountRepository accountRepo, String userUid, String accountId) {
        try {
            if (accountRepo == null || userUid == null || accountId == null) {
                return 0L;
            }
            return accountRepo.computeBalanceCents(userUid, accountId);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String colorFromKey(String key) {
        String k = key == null ? "" : key;
        int h = k.hashCode();
        double hue = (h & 0x7fffffff) % 360;
        double s = 0.68;
        double l = 0.52;
        int rgb = hslToRgb(hue / 360.0, s, l);
        return String.format("#%06X", (0xFFFFFF & rgb));
    }

    private static int hslToRgb(double h, double s, double l) {
        double r;
        double g;
        double b;

        if (s == 0) {
            r = g = b = l;
        } else {
            double q = l < 0.5 ? (l * (1 + s)) : (l + s - l * s);
            double p = 2 * l - q;
            r = hueToRgb(p, q, h + 1.0 / 3.0);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1.0 / 3.0);
        }

        int ri = (int) Math.round(r * 255);
        int gi = (int) Math.round(g * 255);
        int bi = (int) Math.round(b * 255);
        ri = Math.max(0, Math.min(255, ri));
        gi = Math.max(0, Math.min(255, gi));
        bi = Math.max(0, Math.min(255, bi));
        return (ri << 16) | (gi << 8) | bi;
    }

    private static double hueToRgb(double p, double q, double t) {
        double tt = t;
        if (tt < 0) {
            tt += 1;
        }
        if (tt > 1) {
            tt -= 1;
        }
        if (tt < 1.0 / 6.0) {
            return p + (q - p) * 6 * tt;
        }
        if (tt < 1.0 / 2.0) {
            return q;
        }
        if (tt < 2.0 / 3.0) {
            return p + (q - p) * (2.0 / 3.0 - tt) * 6;
        }
        return p;
    }

    private static BigDecimal parseAmount(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isBlank()) {
            throw new IllegalArgumentException("amount");
        }

        s = s.replace(" ", "");
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                s = s.replace(".", "");
                s = s.replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (lastComma >= 0) {
            s = s.replace(',', '.');
        }

        return new BigDecimal(s);
    }
}
