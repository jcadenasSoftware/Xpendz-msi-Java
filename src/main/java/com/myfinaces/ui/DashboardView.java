package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanMovementRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.config.AccountStyles;
import com.myfinaces.config.AppConfig;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.animation.PauseTransition;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import org.kordamp.ikonli.javafx.FontIcon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.time.ZoneId;
import java.time.Instant;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

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
        LoanMovementRepository loanMovementRepo,
        BudgetRepository budgetRepo,
        BooleanProperty darkTheme
    ) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<ScheduledFuture<?>> autoSyncRef = new AtomicReference<>();
        AtomicBoolean syncInProgress = new AtomicBoolean(false);
        AtomicLong lastSyncMs = new AtomicLong(0L);
        AtomicLong syncBlockedUntilMs = new AtomicLong(0L);

        String displayName = session.displayName() == null || session.displayName().isBlank() ? "" : session.displayName().trim();
        String emailName = nameFromEmail(session.email());
        String nameToShow;
        if (displayName.isBlank()) {
            nameToShow = emailName;
        } else {
            int dnTokens = displayName.trim().isEmpty() ? 0 : displayName.trim().split("\\s+").length;
            int emTokens = emailName.trim().isEmpty() ? 0 : emailName.trim().split("\\s+").length;
            nameToShow = emTokens > dnTokens ? emailName : displayName;
        }

        String wave = greetingEmoji();
        String greetText = nameToShow.isBlank() ? "Hola" : ("Hola, " + nameToShow);
        Label greeting = new Label(greetText);
        try {
            if (wave != null && !wave.isBlank()) {
                FontIcon greetIcon = new FontIcon(wave);
                greetIcon.getStyleClass().add("dashboard-greeting-icon");
                greetIcon.setIconSize(16);
                greeting.setGraphic(greetIcon);
                greeting.setGraphicTextGap(6);
            }
        } catch (Exception ignored) {
        }
        greeting.getStyleClass().add("dashboard-greeting");
        String monthText = LocalDate.now().getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-CO"));
        int year = LocalDate.now().getYear();
        Label month = new Label(capitalize(monthText) + " " + year);
        month.getStyleClass().add("dashboard-month");

        AtomicReference<Runnable> refreshBalancesRef = new AtomicReference<>();
        AtomicReference<java.util.function.Consumer<String>> onViewAllMovementsRef = new AtomicReference<>();
        DashboardBalancesPane.Parts balancesUi = DashboardBalancesPane.build(refreshBalancesRef);
        Label totalValue = balancesUi.totalValue();
        HBox totalTrend = balancesUi.totalTrend();
        Pane totalTrendBackdrop = balancesUi.totalTrendBackdrop();
        Polyline totalWave1 = balancesUi.totalWave1();
        Polyline totalWave2 = balancesUi.totalWave2();
        Polygon totalSparkArea = balancesUi.totalSparkArea();
        Polyline totalSparkGlow = balancesUi.totalSparkGlow();
        Polyline totalSparkline = balancesUi.totalSparkline();
        Circle totalSparkDot = balancesUi.totalSparkDot();
        java.util.concurrent.atomic.AtomicReference<FontIcon> trendIconRef = balancesUi.trendIconRef();
        java.util.concurrent.atomic.AtomicReference<Label> trendTextRef = balancesUi.trendTextRef();
        java.util.concurrent.atomic.AtomicReference<Label> trendBadgeRef = balancesUi.trendBadgeRef();
        javafx.scene.layout.StackPane totalCard = balancesUi.totalCard();
        VBox accountsBox = balancesUi.accountsBox();
        VBox goalsBox = balancesUi.goalsBox();
        ScrollPane accountsScroll = balancesUi.accountsScroll();
        ScrollPane goalsScroll = balancesUi.goalsScroll();
        AtomicBoolean hideTotalBalance = balancesUi.hideTotalBalance();

        Label monthIncomeValue = new Label();
        monthIncomeValue.getStyleClass().addAll("dashboard-monthly-value", "money-positive");
        Label monthExpenseValue = new Label();
        monthExpenseValue.getStyleClass().addAll("dashboard-monthly-value", "money-negative");
        Label monthBalanceValue = new Label();
        monthBalanceValue.getStyleClass().addAll("dashboard-monthly-balance", "money-neutral");

        Label monthCompareValue = new Label();
        monthCompareValue.getStyleClass().addAll("dashboard-monthly-compare", "money-neutral");
        monthCompareValue.setWrapText(true);

        VBox monthlyHistoryPrimaryBox = new VBox(8);
        VBox monthlyHistoryExtraBox = new VBox(8);
        monthlyHistoryExtraBox.setVisible(false);
        monthlyHistoryExtraBox.setManaged(false);
        AtomicBoolean monthlyHistoryExpanded = new AtomicBoolean(false);
        Hyperlink monthlyHistoryToggle = new Hyperlink("Ver detalles  >");
        monthlyHistoryToggle.getStyleClass().add("dashboard-monthly-details");
        monthlyHistoryToggle.setOnAction(e -> {
            boolean next = !monthlyHistoryExpanded.get();
            monthlyHistoryExpanded.set(next);

            if (next) {
                monthlyHistoryExtraBox.setManaged(true);
                monthlyHistoryExtraBox.setVisible(true);
                monthlyHistoryExtraBox.setOpacity(0);
                monthlyHistoryExtraBox.setTranslateY(-6);

                FadeTransition ft = new FadeTransition(Duration.millis(160), monthlyHistoryExtraBox);
                ft.setFromValue(0);
                ft.setToValue(1);

                TranslateTransition tt = new TranslateTransition(Duration.millis(160), monthlyHistoryExtraBox);
                tt.setFromY(-6);
                tt.setToY(0);

                ft.play();
                tt.play();
            } else {
                FadeTransition ft = new FadeTransition(Duration.millis(140), monthlyHistoryExtraBox);
                ft.setFromValue(monthlyHistoryExtraBox.getOpacity());
                ft.setToValue(0);

                TranslateTransition tt = new TranslateTransition(Duration.millis(140), monthlyHistoryExtraBox);
                tt.setFromY(monthlyHistoryExtraBox.getTranslateY());
                tt.setToY(-6);

                ft.setOnFinished(ev -> {
                    monthlyHistoryExtraBox.setVisible(false);
                    monthlyHistoryExtraBox.setManaged(false);
                    monthlyHistoryExtraBox.setTranslateY(0);
                    monthlyHistoryExtraBox.setOpacity(1);
                });
                ft.play();
                tt.play();
            }

            monthlyHistoryToggle.setText(next ? "Ver menos" : "Ver detalles  >");
        });

        Region monthlySummaryCard = buildMonthlySummaryCard(
            monthIncomeValue,
            monthExpenseValue,
            monthBalanceValue,
            monthCompareValue,
            monthlyHistoryPrimaryBox,
            monthlyHistoryExtraBox,
            monthlyHistoryToggle
        );

        Button toggleVisibility = new Button();
        toggleVisibility.getStyleClass().addAll("btn-secondary", "nav-button", "dashboard-icon-toggle");
        FontIcon visIcon = new FontIcon(hideTotalBalance.get() ? "far-eye" : "far-eye-slash");
        setButtonIcon(toggleVisibility, visIcon);
        toggleVisibility.setText("");
        toggleVisibility.setOnAction(e -> {
            hideTotalBalance.set(!hideTotalBalance.get());
            visIcon.setIconLiteral(hideTotalBalance.get() ? "far-eye" : "far-eye-slash");
            Runnable r = refreshBalancesRef.get();
            if (r != null) {
                r.run();
            }
        });

        Button toggleTheme = new Button();
        toggleTheme.getStyleClass().add("btn-secondary");
        toggleTheme.getStyleClass().add("nav-button");
        FontIcon themeIcon = new FontIcon(darkTheme.get() ? "fas-moon" : "fas-sun");
        setButtonIcon(toggleTheme, themeIcon);
        toggleTheme.setText("");
        darkTheme.addListener((obs, oldV, newV) -> {
            themeIcon.setIconLiteral(Boolean.TRUE.equals(newV) ? "fas-moon" : "fas-sun");
        });
        toggleTheme.setOnAction(e -> darkTheme.set(!darkTheme.get()));

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
            categoryRepo,
            totalValue,
            totalTrend,
            totalTrendBackdrop,
            totalWave1,
            totalWave2,
            totalSparkArea,
            totalSparkGlow,
            totalSparkline,
            totalSparkDot,
            trendIconRef,
            trendTextRef,
            trendBadgeRef,
            monthIncomeValue,
            monthExpenseValue,
            monthBalanceValue,
            monthCompareValue,
            monthlyHistoryPrimaryBox,
            monthlyHistoryExtraBox,
            monthlyHistoryToggle,
            monthlyHistoryExpanded,
            accountsBox,
            goalsBox,
            darkTheme,
            hideTotalBalance,
            openBudgetGoalsTab,
            onViewAllMovementsRef
        );
        refreshBalancesRef.set(refreshBalances);
        refreshBalances.run();

        Label syncStatus = new Label("Sincronización pendiente");
        syncStatus.setWrapText(true);
        syncStatus.getStyleClass().add("sync-status-label");

        DashboardSyncCoordinator.SyncActions syncActions = DashboardSyncCoordinator.setup(
            session,
            accountRepo,
            goalRepo,
            categoryRepo,
            loanRepo,
            loanPaymentRepo,
            txRepo,
            transferRepo,
            budgetRepo,
            refreshBalances,
            syncInProgress,
            lastSyncMs,
            syncBlockedUntilMs,
            () -> syncStatus.setText("Sincronizando..."),
            () -> syncStatus.setText(
                "Actualizado: "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            ),
            (msg) -> syncStatus.setText(msg)
        );
        Runnable runSyncNow = syncActions.runSyncNow();
        Runnable doRefreshNow = syncActions.doRefreshNow();

        Runnable shutdownSyncScheduler = () -> {
            try {
                ScheduledFuture<?> f = autoSyncRef.getAndSet(null);
                if (f != null) {
                    f.cancel(true);
                }
                scheduler.shutdownNow();
            } catch (Exception ignored) {
            }
        };

        Button addAccount = new Button("Agregar cuenta");
        addAccount.getStyleClass().add("btn-primary");
        addAccount.getStyleClass().add("nav-button");
        addAccount.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(addAccount, new FontIcon("fas-plus-circle"));
        // Handler de addAccount se asigna más abajo, tras la declaración de contentHost y sideDrawer

        HBox headerBar = DashboardHeaderPane.build(greeting, month, toggleVisibility, toggleTheme);

        VBox dashboardContent = new VBox(14, headerBar, totalCard, monthlySummaryCard, accountsScroll, goalsScroll);
        dashboardContent.getStyleClass().add("content");
        dashboardContent.setPadding(new Insets(20));
        dashboardContent.setMinWidth(0);
        VBox.setVgrow(totalCard, Priority.NEVER);
        VBox.setVgrow(accountsScroll, Priority.ALWAYS);
        VBox.setVgrow(goalsScroll, Priority.SOMETIMES);

        StackPane contentHost = new StackPane(dashboardContent);
        contentHost.setMinWidth(0);

        onViewAllMovementsRef.set(accountId -> {
            javafx.scene.Node txPane = DashboardTransactionsDialog.buildTransactionsPane(
                session, txRepo, accountRepo, categoryRepo, darkTheme::get, refreshBalancesRef.get() != null ? refreshBalancesRef.get() : () -> {}, accountId
            );
            contentHost.getChildren().setAll(txPane);
        });

        Runnable showHome = () -> contentHost.getChildren().setAll(dashboardContent);

        Button home = new Button("Inicio");
        home.getStyleClass().add("btn-primary");
        home.getStyleClass().add("nav-button");
        home.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(home, new FontIcon("fas-home"));
        home.setOnAction(e -> showHome.run());

        Button transactions = new Button("Transacciones");
        transactions.getStyleClass().add("btn-primary");
        transactions.getStyleClass().add("nav-button");
        transactions.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(transactions, new FontIcon("fas-receipt"));
        transactions.setOnAction(e -> {
            javafx.scene.Node txPane = DashboardTransactionsDialog.buildTransactionsPane(
                session,
                txRepo,
                accountRepo,
                categoryRepo,
                darkTheme::get,
                refreshBalances
            );
            contentHost.getChildren().setAll(txPane);
        });

        Button summary = new Button("Resumen");
        summary.getStyleClass().add("btn-primary");
        summary.getStyleClass().add("nav-button");
        summary.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(summary, new FontIcon("fas-clipboard-list"));
        summary.setOnAction(e -> showSummaryDialog(session.uid(), txRepo, accountRepo, categoryRepo, goalRepo, darkTheme.get()));

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
        loans.setOnAction(e -> {
            Node loansPane = LoansView.buildLoansView(session, loanRepo, loanPaymentRepo, loanMovementRepo, accountRepo, categoryRepo, txRepo, darkTheme::get, refreshBalances);
            contentHost.getChildren().setAll(loansPane);
        });

        Button budget = new Button("Presupuesto");
        budget.getStyleClass().add("btn-primary");
        budget.getStyleClass().add("nav-button");
        budget.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(budget, new FontIcon("fas-piggy-bank"));
        budget.setOnAction(e -> {
            Node budgetPane = BudgetView.buildBudgetView(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme::get, refreshBalances);
            contentHost.getChildren().setAll(budgetPane);
        });

        Button charts = new Button("Gráficos");
        charts.getStyleClass().add("btn-primary");
        charts.getStyleClass().add("nav-button");
        charts.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(charts, new FontIcon("fas-chart-pie"));
        charts.setOnAction(e -> showChartsDialog(session.uid(), txRepo, accountRepo, categoryRepo, goalRepo, transferRepo, darkTheme.get()));

        Button transfers = new Button("Transferencias");
        transfers.getStyleClass().add("btn-primary");
        transfers.getStyleClass().add("nav-button");
        transfers.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(transfers, new FontIcon("fas-exchange-alt"));
        Button categories = new Button("Categorías");
        categories.getStyleClass().add("btn-primary");
        categories.getStyleClass().add("nav-button");
        categories.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(categories, new FontIcon("fas-tags"));
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

        Button exit = new Button("Salir");
        exit.getStyleClass().add("btn-danger");
        exit.getStyleClass().add("nav-button");
        exit.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(exit, new FontIcon("fas-times-circle"));

        // Helper method to set active button
        java.util.function.Consumer<Button> setActiveButton = (activeBtn) -> {
            Button[] navButtons = {home, transactions, transfers, summary, loans, budget, charts, categories};
            for (Button btn : navButtons) {
                btn.getStyleClass().remove("active");
            }
            activeBtn.getStyleClass().add("active");
        };

        // Reassign event handlers to use setActiveButton
        home.setOnAction(e -> {
            setActiveButton.accept(home);
            showHome.run();
        });

        transactions.setOnAction(e -> {
            setActiveButton.accept(transactions);
            javafx.scene.Node txPane = DashboardTransactionsDialog.buildTransactionsPane(
                session,
                txRepo,
                accountRepo,
                categoryRepo,
                darkTheme::get,
                refreshBalances
            );
            contentHost.getChildren().setAll(txPane);
        });

        summary.setOnAction(e -> {
            setActiveButton.accept(summary);
            showSummaryDialog(session.uid(), txRepo, accountRepo, categoryRepo, goalRepo, darkTheme.get());
        });

        loans.setOnAction(e -> {
            setActiveButton.accept(loans);
            Node loansPane = LoansView.buildLoansView(session, loanRepo, loanPaymentRepo, loanMovementRepo, accountRepo, categoryRepo, txRepo, darkTheme::get, refreshBalances);
            contentHost.getChildren().setAll(loansPane);
        });

        budget.setOnAction(e -> {
            setActiveButton.accept(budget);
            Node budgetPane = BudgetView.buildBudgetView(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme::get, refreshBalances);
            contentHost.getChildren().setAll(budgetPane);
        });

        charts.setOnAction(e -> {
            setActiveButton.accept(charts);
            showChartsDialog(session.uid(), txRepo, accountRepo, categoryRepo, goalRepo, transferRepo, darkTheme.get());
        });

        transfers.setOnAction(e -> {
            setActiveButton.accept(transfers);
            Node transfersPane = TransfersView.buildTransfersView(session, transferRepo, accountRepo, darkTheme::get, refreshBalances);
            contentHost.getChildren().setAll(transfersPane);
        });

        categories.setOnAction(e -> {
            setActiveButton.accept(categories);
            Node categoriesPane = CategoriesView.buildCategoriesView(session, categoryRepo, darkTheme::get, refreshBalances);
            contentHost.getChildren().setAll(categoriesPane);
        });

        // Set home button as active by default
        setActiveButton.accept(home);

        SideDrawer accountDrawer = new SideDrawer();
        accountDrawer.setDarkTheme(darkTheme.get());
        darkTheme.addListener((obs, oldV, newV) -> accountDrawer.setDarkTheme(Boolean.TRUE.equals(newV)));
        NewAccountDrawer.install(accountDrawer, session, accountRepo, () -> {
            refreshBalances.run();
        }, darkTheme.get());
        StackPane contentWithAccountDrawer = accountDrawer.wrapContent(contentHost);

        addAccount.setOnAction(e -> {
            accountDrawer.show("new-account");
        });

        java.util.function.Consumer<Runnable> flushAndThen = (after) -> {
            logout.setDisable(true);
            exit.setDisable(true);
            syncStatus.setText("Sincronizando antes de salir...");
            new Thread(() -> {
                long timeoutMs = 60_000L;
                long startMs = System.currentTimeMillis();

                try {
                    while (syncInProgress.get() && (System.currentTimeMillis() - startMs) < 10_000L) {
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }

                try {
                    Thread worker = new Thread(() -> {
                        try {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncAccounts(session, accountRepo);
                            sync.syncCategories(session, categoryRepo);
                            sync.syncGoals(session, goalRepo);
                            sync.syncBudgets(session, budgetRepo);
                            sync.syncTransactions(session, txRepo);
                            sync.syncTransfers(session, transferRepo);

                            try {
                                List<LoanRepository.Loan> pendingLoans = loanRepo.listPendingForSync(session.uid());
                                for (LoanRepository.Loan l : pendingLoans) {
                                    sync.syncLoan(session, l);
                                    try {
                                        loanRepo.markSynced(session.uid(), l.id());
                                    } catch (Exception ignored) {
                                    }
                                }
                            } catch (Exception ignored) {
                            }

                            try {
                                List<LoanPaymentRepository.LoanPayment> payments = loanPaymentRepo.listPendingForSync(session.uid());
                                for (LoanPaymentRepository.LoanPayment p : payments) {
                                    sync.syncLoanPayment(session, p);
                                    try {
                                        loanPaymentRepo.markSynced(session.uid(), p.id());
                                    } catch (Exception ignored) {
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        } catch (Exception ignored) {
                        }
                    }, "final-sync-worker");
                    worker.setDaemon(true);
                    worker.start();

                    long elapsed = System.currentTimeMillis() - startMs;
                    long remaining = Math.max(1_000L, timeoutMs - elapsed);
                    try {
                        worker.join(remaining);
                    } catch (InterruptedException ignored) {
                    }

                    if (worker.isAlive()) {
                        try {
                            worker.interrupt();
                        } catch (Exception ignored) {
                        }
                        Platform.runLater(() -> syncStatus.setText("Sincronización tardó demasiado. Cerrando igual..."));
                    }
                } catch (Exception ignored) {
                }

                Platform.runLater(() -> {
                    try {
                        after.run();
                    } finally {
                        logout.setDisable(false);
                        exit.setDisable(false);
                    }
                });
            }, "final-sync-before-close").start();
        };

        logout.setOnAction(e -> {
            Alert confirm = buildAlert(
                AlertType.CONFIRMATION,
                "Cerrar sesión",
                "¿Cerrar sesión?",
                "Se guardarán tus cambios antes de cerrar sesión.",
                darkTheme.get(),
                "fas-sign-out-alt"
            );
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                flushAndThen.accept(() -> {
                    shutdownSyncScheduler.run();
                    listener.onLogout();
                });
            });
        });

        exit.setOnAction(e -> {
            Alert confirm = buildAlert(
                AlertType.CONFIRMATION,
                "Salir",
                "¿Salir de la aplicación?",
                "Se guardarán tus cambios antes de salir.",
                darkTheme.get(),
                "fas-power-off"
            );
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                flushAndThen.accept(() -> {
                    shutdownSyncScheduler.run();
                    Platform.exit();
                    System.exit(0);
                });
            });
        });

        autoSyncRef.set(scheduler.scheduleAtFixedRate(() -> {
            runSyncNow.run();
        }, 120, 1800, TimeUnit.SECONDS));

        ScrollPane sidebarScroll = DashboardSidebarPane.build(
            session.displayName(),
            session.email(),
            home,
            transactions,
            transfers,
            summary,
            loans,
            budget,
            charts,
            addAccount,
            categories,
            syncNow,
            syncStatus,
            logout,
            exit
        );

        Platform.runLater(() -> {
            PauseTransition pt = new PauseTransition(Duration.seconds(0.25));
            pt.setOnFinished(e -> doRefreshNow.run());
            pt.play();
        });

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(sidebarScroll);
        root.setCenter(contentWithAccountDrawer);
        root.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F5) {
                doRefreshNow.run();
            }
        });
        return root;
    }

    private static String greetingEmoji() {
        try {
            int h = java.time.LocalTime.now().getHour();
            if (h < 19) {
                return "fas-hand-paper";
            }
            return "fas-moon";
        } catch (Exception ignored) {
            return "fas-hand-paper";
        }
    }

    private static String nameFromEmail(String email) {
        if (email == null) {
            return "";
        }
        String e = email.trim();
        if (e.isEmpty() || !e.contains("@")) {
            return "";
        }
        String local = e.substring(0, e.indexOf('@'));
        if (local.isBlank()) {
            return "";
        }

        String[] parts = local.split("[._\\-]+", -1);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String token = p.replaceAll("[^A-Za-zÁÉÍÓÚÜÑáéíóúüñ]", "").trim();
            if (token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(capitalize(token.toLowerCase(Locale.ROOT)));
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "";
        }
        String first = t.substring(0, 1).toUpperCase(Locale.ROOT);
        String rest = t.length() > 1 ? t.substring(1) : "";
        return first + rest;
    }

    private static void refreshBalances(
        AuthSession session,
        AccountRepository accountRepo,
        GoalRepository goalRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        CategoryRepository categoryRepo,
        Label totalValue,
        HBox totalTrend,
        Pane totalTrendBackdrop,
        Polyline totalWave1,
        Polyline totalWave2,
        Polygon totalSparkArea,
        Polyline totalSparkGlow,
        Polyline totalSparkline,
        Circle totalSparkDot,
        java.util.concurrent.atomic.AtomicReference<FontIcon> trendIconRef,
        java.util.concurrent.atomic.AtomicReference<Label> trendTextRef,
        java.util.concurrent.atomic.AtomicReference<Label> trendBadgeRef,
        Label monthIncomeValue,
        Label monthExpenseValue,
        Label monthBalanceValue,
        Label monthCompareValue,
        VBox monthlyHistoryPrimaryBox,
        VBox monthlyHistoryExtraBox,
        Hyperlink monthlyHistoryToggle,
        AtomicBoolean monthlyHistoryExpanded,
        VBox accountsBox,
        VBox goalsBox,
        BooleanProperty darkTheme,
        AtomicBoolean hideTotalBalance,
        Runnable openBudgetGoalsTab,
        AtomicReference<java.util.function.Consumer<String>> onViewAllMovementsRef
    ) {
        accountsBox.getChildren().clear();
        goalsBox.getChildren().clear();

        Runnable refreshAll = () -> refreshBalances(
            session,
            accountRepo,
            goalRepo,
            txRepo,
            transferRepo,
            categoryRepo,
            totalValue,
            totalTrend,
            totalTrendBackdrop,
            totalWave1,
            totalWave2,
            totalSparkArea,
            totalSparkGlow,
            totalSparkline,
            totalSparkDot,
            trendIconRef,
            trendTextRef,
            trendBadgeRef,
            monthIncomeValue,
            monthExpenseValue,
            monthBalanceValue,
            monthCompareValue,
            monthlyHistoryPrimaryBox,
            monthlyHistoryExtraBox,
            monthlyHistoryToggle,
            monthlyHistoryExpanded,
            accountsBox,
            goalsBox,
            darkTheme,
            hideTotalBalance,
            openBudgetGoalsTab,
            onViewAllMovementsRef
        );

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
                Label tText = trendTextRef.get();
                if (tText != null) {
                    tText.setText("Sin cambios");
                }
                monthIncomeValue.setText(formatMoney(0));
                monthExpenseValue.setText(formatMoney(0));
                monthBalanceValue.setText(formatMoney(0));
                if (monthCompareValue != null) {
                    monthCompareValue.setText("0");
                    monthCompareValue.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                    monthCompareValue.getStyleClass().add("money-neutral");
                }
                if (monthlyHistoryPrimaryBox != null) {
                    monthlyHistoryPrimaryBox.getChildren().clear();
                    if (monthlyHistoryExtraBox != null) {
                        monthlyHistoryExtraBox.getChildren().clear();
                        monthlyHistoryExtraBox.setVisible(false);
                        monthlyHistoryExtraBox.setManaged(false);
                    }
                    if (monthlyHistoryExpanded != null) {
                        monthlyHistoryExpanded.set(false);
                    }
                    if (monthlyHistoryToggle != null) {
                        monthlyHistoryToggle.setVisible(false);
                        monthlyHistoryToggle.setManaged(false);
                        monthlyHistoryToggle.setText("Ver detalles  >");
                    }
                    Label emptyMonthly = new Label("Sin registros previos");
                    emptyMonthly.getStyleClass().add("text-secondary");
                    monthlyHistoryPrimaryBox.getChildren().add(emptyMonthly);
                }
                Label empty = new Label("No hay cuentas creadas aún.");
                empty.getStyleClass().add("text-secondary");
                accountsBox.getChildren().add(empty);
                return;
            }

            long monthIncomeCents = 0L;
            long monthExpenseCents = 0L;
            List<TransactionRepository.TransactionRow> monthTx = java.util.List.of();
            try {
                ZoneId zone = ZoneId.systemDefault();
                LocalDate today = LocalDate.now();
                LocalDate firstDay = today.with(TemporalAdjusters.firstDayOfMonth());
                long fromEpoch = firstDay.atStartOfDay(zone).toEpochSecond();
                long toEpoch = Instant.now().getEpochSecond();

                monthTx = txRepo.listFiltered(
                    session.uid(),
                    null,
                    (List<String>) null,
                    fromEpoch,
                    toEpoch,
                    10_000
                );
                for (TransactionRepository.TransactionRow t : monthTx) {
                    if (t == null || t.kind() == null) {
                        continue;
                    }
                    String k = t.kind().trim().toUpperCase();
                    if ("INCOME".equals(k)) {
                        monthIncomeCents += Math.max(0L, t.amountCents());
                    } else if ("EXPENSE".equals(k)) {
                        monthExpenseCents += Math.max(0L, t.amountCents());
                    }
                }
            } catch (Exception ignored) {
            }

            monthIncomeValue.setText(formatMoney(monthIncomeCents));
            monthExpenseValue.setText(formatMoney(monthExpenseCents));
            long monthBalanceCents = monthIncomeCents - monthExpenseCents;
            monthBalanceValue.setText(formatMoney(monthBalanceCents));
            monthBalanceValue.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
            monthBalanceValue.getStyleClass().add(monthBalanceCents > 0 ? "money-positive" : (monthBalanceCents < 0 ? "money-negative" : "money-neutral"));

            java.util.List<MonthTotals> previousMonths = computePreviousMonthsWithRecords(session.uid(), txRepo, 3);
            if (monthCompareValue != null) {
                if (previousMonths.isEmpty()) {
                    monthCompareValue.setText("0");
                    monthCompareValue.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                    monthCompareValue.getStyleClass().add("money-neutral");
                } else {
                    long prevBalance = previousMonths.get(0).balanceCents();
                    long delta = monthBalanceCents - prevBalance;
                    String pctText = "";
                    long denom = Math.abs(prevBalance);
                    if (denom > 0) {
                        double pct = (delta * 100.0) / denom;
                        pctText = String.format(Locale.ROOT, " (%.1f%%)", pct);
                    }
                    monthCompareValue.setText(formatMoney(delta) + pctText);
                    monthCompareValue.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                    monthCompareValue.getStyleClass().add(delta > 0 ? "money-positive" : (delta < 0 ? "money-negative" : "money-neutral"));
                }
            }

            if (monthlyHistoryPrimaryBox != null) {
                updateMonthlyHistoryBox(
                    monthlyHistoryPrimaryBox,
                    monthlyHistoryExtraBox,
                    monthlyHistoryToggle,
                    monthlyHistoryExpanded,
                    previousMonths
                );
            }

            drawTotalTrendBackdrop(totalTrendBackdrop, totalWave1, totalWave2, totalSparkArea, totalSparkGlow, totalSparkline, totalSparkDot, monthTx, monthBalanceCents);

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

            List<AccountWithBalance> accountBalances = new java.util.ArrayList<>();
            long totalCents = 0L;
            for (AccountRepository.Account a : accounts) {
                long balance = accountRepo.computeBalanceCents(session.uid(), a.id());
                totalCents += balance;
                accountBalances.add(new AccountWithBalance(a, balance));
            }

            List<AccountWithBalance> nonGoalAccounts = accountBalances.stream()
                .filter(ab -> ab != null && ab.account() != null && !goalAccountIds.contains(ab.account().id()))
                .toList();

            java.util.function.Consumer<String> onViewAll = onViewAllMovementsRef != null ? onViewAllMovementsRef.get() : null;
            updateAccountsByTypeTwoColumns(accountsBox, nonGoalAccounts, session, accountRepo, txRepo, transferRepo, categoryRepo, darkTheme, refreshAll, onViewAll);

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

            FontIcon trendIcon = trendIconRef.get();
            Label trendText = trendTextRef.get();
            Label trendBadge = trendBadgeRef.get();
            if (trendIcon != null && trendText != null && trendBadge != null) {
                trendBadge.getStyleClass().removeAll("trend-badge-up", "trend-badge-down", "trend-badge-neutral");
                if (monthBalanceCents > 0) {
                    trendIcon.setIconLiteral("fas-arrow-up");
                    trendText.setText("Tendencia al alza");
                    trendBadge.getStyleClass().add("trend-badge-up");
                } else if (monthBalanceCents < 0) {
                    trendIcon.setIconLiteral("fas-arrow-down");
                    trendText.setText("Tendencia a la baja");
                    trendBadge.getStyleClass().add("trend-badge-down");
                } else {
                    trendIcon.setIconLiteral("fas-minus");
                    trendText.setText("Sin cambios");
                    trendBadge.getStyleClass().add("trend-badge-neutral");
                }
            }
        } catch (Exception ex) {
            totalValue.setText("No se pudo cargar saldos.");
            totalValue.getStyleClass().setAll("account-name", "dashboard-total-value", "text-danger");
            Label tText = trendTextRef.get();
            if (tText != null) {
                tText.setText("");
            }
            clearTotalTrendBackdrop(totalWave1, totalWave2, totalSparkArea, totalSparkGlow, totalSparkline, totalSparkDot);
            accountsBox.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
        }
    }

    private static void setButtonIcon(Button button, FontIcon icon) {
        if (button == null || icon == null) {
            return;
        }
        icon.getStyleClass().add("icon");
        icon.setIconSize(14);
        button.setGraphic(icon);
    }

    private static String accountTypeLabel(String type) {
        String t = AccountRepository.normalizeType(type);
        if ("BANK".equalsIgnoreCase(t)) {
            return "Banco";
        }
        if ("CASH".equalsIgnoreCase(t)) {
            return "Efectivo";
        }
        if ("SAVINGS".equalsIgnoreCase(t)) {
            return "Ahorro";
        }
        if ("VIRTUAL_WALLET".equalsIgnoreCase(t)) {
            return "Billetera virtual";
        }
        if ("DIGITAL_ACCOUNT".equalsIgnoreCase(t)) {
            return "Cuenta digital";
        }
        return t.isBlank() ? "Cuenta" : t;
    }

    private record AccountWithBalance(AccountRepository.Account account, long balanceCents) {
    }

    private static void updateAccountsByTypeTwoColumns(
        VBox accountsBox,
        List<AccountWithBalance> accounts,
        AuthSession session,
        AccountRepository accountRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        CategoryRepository categoryRepo,
        BooleanProperty darkTheme,
        Runnable refreshAll,
        java.util.function.Consumer<String> onViewAllMovements
    ) {
        if (accountsBox == null) {
            return;
        }
        accountsBox.getChildren().clear();

        if (accounts == null || accounts.isEmpty()) {
            Label empty = new Label("No hay cuentas creadas aún.");
            empty.getStyleClass().add("text-secondary");
            accountsBox.getChildren().add(empty);
            return;
        }

        Label hdr = new Label("Cuentas");
        hdr.getStyleClass().add("account-name");
        accountsBox.getChildren().add(hdr);

        List<AccountWithBalance> sorted = accounts.stream()
            .filter(ab -> ab != null && ab.account() != null)
            .sorted(Comparator.comparingLong(AccountWithBalance::balanceCents).reversed())
            .toList();

        VBox colLeft = new VBox(10);
        VBox colRight = new VBox(10);
        colLeft.setFillWidth(true);
        colRight.setFillWidth(true);
        colLeft.setMaxWidth(Double.MAX_VALUE);
        colRight.setMaxWidth(Double.MAX_VALUE);

        int leftCount = 0;
        int rightCount = 0;
        for (AccountWithBalance ab : sorted) {
            Region row = buildAccountCompactRow(accountsBox, session, accountRepo, txRepo, transferRepo, categoryRepo, darkTheme, ab, refreshAll, onViewAllMovements);
            if (leftCount <= rightCount) {
                colLeft.getChildren().add(row);
                leftCount++;
            } else {
                colRight.getChildren().add(row);
                rightCount++;
            }
        }

        HBox columns = new HBox(14, colLeft, colRight);
        columns.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(colLeft, Priority.ALWAYS);
        HBox.setHgrow(colRight, Priority.ALWAYS);
        accountsBox.getChildren().add(columns);
    }

    private static Region buildAccountCompactRow(
        VBox accountsBox,
        AuthSession session,
        AccountRepository accountRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        CategoryRepository categoryRepo,
        BooleanProperty darkTheme,
        AccountWithBalance ab,
        Runnable refreshAll,
        java.util.function.Consumer<String> onViewAllMovements
    ) {
        AccountRepository.Account a = ab.account();
        long balance = ab.balanceCents();

        String accentColor = AccountStyles.resolveColor(a);
        String softColor   = AccountStyles.resolveSoftColor(a);
        String iconLiteral = AccountStyles.resolveIcon(a);

        // ── Avatar circular ───────────────────────────────────────────
        FontIcon avatarIcon = new FontIcon(iconLiteral);
        avatarIcon.setIconSize(16);
        try {
            avatarIcon.setIconColor(Color.web(accentColor));
        } catch (Exception ignored) {}
        Circle avatarBg = new Circle(22);
        try {
            avatarBg.setFill(Color.web(softColor, 0.55));
        } catch (Exception ignored) {
            avatarBg.setFill(Color.TRANSPARENT);
        }
        StackPane avatar = new StackPane(avatarBg, avatarIcon);
        avatar.setMinWidth(44);
        avatar.setMaxWidth(44);
        avatar.setPrefWidth(44);
        avatar.setMinHeight(44);
        avatar.setMaxHeight(44);

        Label name = new Label(a.name());
        name.getStyleClass().add("account-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label typeDot = new Label("\u2022");
        try { typeDot.setTextFill(Color.web(accentColor)); } catch (Exception ignored) {}
        typeDot.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Label typeLabel = new Label(accountTypeLabel(a.type()));
        typeLabel.getStyleClass().addAll("text-secondary", "accounts-type-inline");
        typeLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        typeLabel.setMinWidth(0);
        typeLabel.setMaxWidth(Double.MAX_VALUE);
        HBox typeRow = new HBox(4, typeDot, typeLabel);
        typeRow.setAlignment(Pos.CENTER_LEFT);

        Label amount = new Label(formatMoney(balance, a.currency()));
        amount.setMinWidth(0);
        amount.getStyleClass().add(balance > 0 ? "money-positive" : (balance < 0 ? "money-negative" : "money-neutral"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        VBox left = new VBox(2, name, typeRow);
        left.setMinWidth(0);
        left.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(left, Priority.ALWAYS);

        Button reconcileBtn = new Button();
        reconcileBtn.getStyleClass().addAll("btn-secondary", "accounts-reconcile-btn");
        reconcileBtn.setText("");
        setButtonIcon(reconcileBtn, new FontIcon("fas-calculator"));
        reconcileBtn.setMinWidth(28);
        reconcileBtn.setPrefWidth(28);
        reconcileBtn.setMaxWidth(28);
        reconcileBtn.setOpacity(0);
        reconcileBtn.setFocusTraversable(false);

        HBox row = new HBox(10, avatar, left, spacer, reconcileBtn, amount);
        row.getStyleClass().add("account-item");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setMinHeight(Region.USE_PREF_SIZE);
        row.setAlignment(Pos.CENTER_LEFT);
        boolean initialDark = darkTheme != null && darkTheme.get();
        String borderSoft = initialDark ? "rgba(255,255,255,0.10)" : "rgba(15,23,42,0.09)";
        String baseStyle = "-fx-border-color: " + borderSoft + " " + borderSoft + " " + borderSoft + " " + accentColor + "; -fx-border-width: 1 1 1 3;";
        row.setStyle(baseStyle);

        row.hoverProperty().addListener((obs, o, n) -> {
            reconcileBtn.setOpacity(Boolean.TRUE.equals(n) ? 1 : 0);
            boolean isDarkNow = darkTheme != null && darkTheme.get();
            String hoverBorder = isDarkNow ? "rgba(59,130,246,0.40)" : "rgba(29,78,216,0.30)";
            row.setStyle(Boolean.TRUE.equals(n)
                ? "-fx-border-color: " + hoverBorder + " " + hoverBorder + " " + hoverBorder + " " + accentColor + "; -fx-border-width: 1 1 1 3;"
                : baseStyle);
        });

        VBox reconcilePanel = buildAccountReconcilePanel(accountsBox, a, balance, initialDark);
        boolean open = a != null && a.id() != null && a.id().equals(accountsReconcileOpenId(accountsBox));
        reconcilePanel.setVisible(open);
        reconcilePanel.setManaged(open);
        reconcilePanel.setOpacity(open ? 1 : 0);
        reconcilePanel.setTranslateY(0);

        reconcileBtn.setOnAction(e -> {
            if (accountsBox == null || a == null) {
                return;
            }
            String current = accountsReconcileOpenId(accountsBox);
            String next = (current != null && current.equals(a.id())) ? null : a.id();
            accountsBox.getProperties().put("accountsReconcileOpenId", next);
            if (refreshAll != null) {
                refreshAll.run();
            }
        });

        row.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY || ev.getClickCount() != 2) {
                return;
            }
            boolean isDarkNow = darkTheme != null && darkTheme.get();
            Optional<DashboardAccountsFeature.EditAccountResult> res = DashboardAccountsFeature.showEditAccountDialog(a, isDarkNow, session.uid(), accountRepo, txRepo, transferRepo, onViewAllMovements);
            if (res.isEmpty()) {
                return;
            }
            try {
                if (res.get().action() == DashboardAccountsFeature.EditAccountAction.SAVE) {
                    AccountRepository.Account updated = accountRepo.updateNameTypeAndColor(session.uid(), a.id(), res.get().newName(), res.get().newType(), res.get().newColor());
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.syncAccount(session, updated);
                    } catch (Exception ignored) {
                    }
                    if (refreshAll != null) {
                        refreshAll.run();
                    }
                } else if (res.get().action() == DashboardAccountsFeature.EditAccountAction.VIEW_SUMMARY) {
                    DashboardAccountsFeature.showAccountSummaryDialog(session.uid(), a, txRepo, transferRepo, accountRepo, isDarkNow);
                } else if (res.get().action() == DashboardAccountsFeature.EditAccountAction.DELETE) {
                    Alert confirm = buildAlert(
                        AlertType.CONFIRMATION,
                        "Eliminar cuenta",
                        "¿Eliminar cuenta?",
                        "Esta acción también eliminará sus transacciones y transferencias asociadas.",
                        isDarkNow
                    );
                    confirm.showAndWait().ifPresent(btn -> {
                        if (btn != ButtonType.OK) {
                            return;
                        }
                        try {
                            accountRepo.delete(session.uid(), a.id());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.deleteAccount(session, a.id());
                            } catch (Exception ignored) {
                            }
                            if (refreshAll != null) {
                                refreshAll.run();
                            }
                        } catch (Exception ignored) {
                        }
                    });
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
                        isDarkNow
                    );
                    err.showAndWait();
                }
            } catch (Exception ex) {
                Alert err = buildAlert(
                    AlertType.ERROR,
                    "Error",
                    "No se pudo actualizar la cuenta",
                    ex.getMessage() == null ? "Error" : ex.getMessage(),
                    isDarkNow
                );
                err.showAndWait();
            }
        });

        VBox wrap = new VBox(6, row, reconcilePanel);
        wrap.setFillWidth(true);
        wrap.setMaxWidth(Double.MAX_VALUE);
        return wrap;
    }

    private static String accountsReconcileOpenId(VBox accountsBox) {
        if (accountsBox == null) {
            return null;
        }
        Object v = accountsBox.getProperties().get("accountsReconcileOpenId");
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, String> accountsReconcileRealInputs(VBox accountsBox) {
        if (accountsBox == null) {
            return new HashMap<>();
        }
        Object v = accountsBox.getProperties().get("accountsReconcileRealInputs");
        if (v instanceof Map<?, ?> m) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> typed = (Map<String, String>) m;
                return typed;
            } catch (Exception ignored) {
            }
        }
        Map<String, String> created = new HashMap<>();
        accountsBox.getProperties().put("accountsReconcileRealInputs", created);
        return created;
    }

    private static VBox buildAccountReconcilePanel(VBox accountsBox, AccountRepository.Account a, long recordedCents, boolean darkTheme) {
        TextField realInput = new TextField();
        realInput.setPromptText("Saldo real");
        realInput.setPrefColumnCount(10);
        realInput.setMinWidth(120);

        Map<String, String> stash = accountsReconcileRealInputs(accountsBox);
        if (a != null && a.id() != null) {
            String prev = stash.get(a.id());
            if (prev != null) {
                realInput.setText(prev);
            }
        }

        Label diffLabel = new Label(formatMoney(0, a == null ? null : a.currency()));
        diffLabel.getStyleClass().addAll("text-secondary", "money-neutral");

        Runnable recompute = () -> {
            Long parsed = parseUserDecimalToCents(realInput.getText());
            long realCents = parsed == null ? 0L : parsed;
            long diff = realCents - recordedCents;

            diffLabel.setText(formatMoney(diff, a == null ? null : a.currency()));
            diffLabel.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
            diffLabel.getStyleClass().add(diff > 0 ? "money-positive" : (diff < 0 ? "money-negative" : "money-neutral"));

            if (accountsBox != null && a != null && a.id() != null) {
                stash.put(a.id(), realInput.getText() == null ? "" : realInput.getText());
            }
        };

        realInput.textProperty().addListener((obs, o, n) -> recompute.run());
        Platform.runLater(recompute);

        Hyperlink copy = new Hyperlink("Copiar diferencia");
        copy.getStyleClass().add("accounts-reconcile-copy");
        copy.setOnAction(e -> {
            Long parsed = parseUserDecimalToCents(realInput.getText());
            long realCents = parsed == null ? 0L : parsed;
            long diff = realCents - recordedCents;
            ClipboardContent cc = new ClipboardContent();
            long absDiff = Math.abs(diff);
            long intPart = absDiff / 100;
            long decPart = absDiff % 100;
            cc.putString(decPart == 0 ? String.valueOf(intPart) : intPart + "." + String.format("%02d", decPart));
            Clipboard.getSystemClipboard().setContent(cc);
        });

        Label diffText = new Label("Diferencia:");
        diffText.getStyleClass().add("text-secondary");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox line = new HBox(10, realInput, spacer, diffText, diffLabel, copy);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(6, line);
        panel.getStyleClass().addAll("card", "accounts-reconcile-panel");
        panel.setPadding(new Insets(10));
        panel.setMaxWidth(Double.MAX_VALUE);
        return panel;
    }

    private record MonthTotals(YearMonth month, long incomeCents, long expenseCents) {
        long balanceCents() {
            return incomeCents - expenseCents;
        }
    }

    private static java.util.List<MonthTotals> computePreviousMonthsWithRecords(
        String userUid,
        TransactionRepository txRepo,
        int maxMonths
    ) {
        java.util.List<MonthTotals> out = new java.util.ArrayList<>();
        if (txRepo == null || userUid == null || userUid.isBlank()) {
            return out;
        }
        ZoneId zone = ZoneId.systemDefault();
        YearMonth current = YearMonth.now();
        for (int i = 1; i <= 12 && out.size() < maxMonths; i++) {
            YearMonth ym = current.minusMonths(i);
            long fromEpoch = ym.atDay(1).atStartOfDay(zone).toEpochSecond();
            long toEpoch = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond() - 1;

            List<TransactionRepository.TransactionRow> tx;
            try {
                tx = txRepo.listFiltered(userUid, null, (List<String>) null, fromEpoch, toEpoch, 10_000);
            } catch (Exception ignored) {
                continue;
            }
            if (tx == null || tx.isEmpty()) {
                continue;
            }

            long inc = 0L;
            long exp = 0L;
            for (TransactionRepository.TransactionRow t : tx) {
                if (t == null || t.kind() == null) {
                    continue;
                }
                String k = t.kind().trim().toUpperCase(Locale.ROOT);
                if ("INCOME".equals(k)) {
                    inc += Math.max(0L, t.amountCents());
                } else if ("EXPENSE".equals(k)) {
                    exp += Math.max(0L, t.amountCents());
                }
            }
            out.add(new MonthTotals(ym, inc, exp));
        }
        return out;
    }

    private static void updateMonthlyHistoryBox(
        VBox primaryBox,
        VBox extraBox,
        Hyperlink toggle,
        AtomicBoolean expanded,
        java.util.List<MonthTotals> months
    ) {
        primaryBox.getChildren().clear();
        if (extraBox != null) {
            extraBox.getChildren().clear();
        }

        if (months == null || months.isEmpty()) {
            Label empty = new Label("Sin registros previos");
            empty.getStyleClass().add("text-secondary");
            primaryBox.getChildren().add(empty);
            if (toggle != null) {
                toggle.setVisible(false);
                toggle.setManaged(false);
            }
            if (extraBox != null) {
                extraBox.setVisible(false);
                extraBox.setManaged(false);
            }
            if (expanded != null) {
                expanded.set(false);
            }
            return;
        }

        java.util.List<MonthTotals> primary = months.size() >= 1 ? java.util.List.of(months.get(0)) : java.util.List.of();
        java.util.List<MonthTotals> extra = months.size() <= 1 ? java.util.List.of() : months.subList(1, months.size());

        for (MonthTotals m : primary) {
            String monthLabel = m.month().getMonth().getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("es-CO"));
            monthLabel = capitalize(monthLabel) + " " + m.month().getYear();

            Label monthText = new Label(monthLabel);
            monthText.getStyleClass().add("dashboard-monthly-history-month");
            monthText.setMinWidth(0);
            monthText.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(monthText, Priority.ALWAYS);

            long bal = m.balanceCents();
            Label balLabel = new Label(formatMoney(bal));
            balLabel.getStyleClass().add(bal > 0 ? "money-positive" : (bal < 0 ? "money-negative" : "money-neutral"));
            balLabel.getStyleClass().add("dashboard-monthly-history-balance");

            Label incomeText = new Label("I: " + formatMoney(m.incomeCents()));
            incomeText.getStyleClass().addAll("dashboard-monthly-history-sub", "money-positive");
            Label expenseText = new Label("G: " + formatMoney(m.expenseCents()));
            expenseText.getStyleClass().addAll("dashboard-monthly-history-sub", "money-negative");
            HBox sub = new HBox(12, incomeText, expenseText);
            sub.setAlignment(Pos.CENTER_LEFT);

            Label check = new Label("✓");
            check.getStyleClass().add("dashboard-monthly-history-check");
            StackPane checkBadge = new StackPane(check);
            checkBadge.getStyleClass().add("dashboard-monthly-history-check-badge");
            checkBadge.setMinSize(22, 22);
            checkBadge.setPrefSize(22, 22);
            checkBadge.setMaxSize(22, 22);

            HBox top = new HBox(10, checkBadge, monthText, balLabel);
            top.setAlignment(Pos.CENTER_LEFT);

            VBox card = new VBox(4, top, sub);
            card.getStyleClass().add("dashboard-monthly-history-card");
            card.setMaxWidth(Double.MAX_VALUE);
            primaryBox.getChildren().add(card);
        }

        if (extraBox != null) {
            for (MonthTotals m : extra) {
                String monthLabel = m.month().getMonth().getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("es-CO"));
                monthLabel = capitalize(monthLabel) + " " + m.month().getYear();

                Label monthText = new Label(monthLabel);
                monthText.getStyleClass().add("dashboard-monthly-history-month");
                monthText.setMinWidth(0);
                monthText.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(monthText, Priority.ALWAYS);

                long bal = m.balanceCents();
                Label balLabel = new Label(formatMoney(bal));
                balLabel.getStyleClass().add(bal > 0 ? "money-positive" : (bal < 0 ? "money-negative" : "money-neutral"));
                balLabel.getStyleClass().add("dashboard-monthly-history-balance");

                Label incomeText = new Label("I: " + formatMoney(m.incomeCents()));
                incomeText.getStyleClass().addAll("dashboard-monthly-history-sub", "money-positive");
                Label expenseText = new Label("G: " + formatMoney(m.expenseCents()));
                expenseText.getStyleClass().addAll("dashboard-monthly-history-sub", "money-negative");
                HBox sub = new HBox(12, incomeText, expenseText);
                sub.setAlignment(Pos.CENTER_LEFT);

                Label check = new Label("✓");
                check.getStyleClass().add("dashboard-monthly-history-check");
                StackPane checkBadge = new StackPane(check);
                checkBadge.getStyleClass().add("dashboard-monthly-history-check-badge");
                checkBadge.setMinSize(22, 22);
                checkBadge.setPrefSize(22, 22);
                checkBadge.setMaxSize(22, 22);

                HBox top = new HBox(10, checkBadge, monthText, balLabel);
                top.setAlignment(Pos.CENTER_LEFT);

                VBox card = new VBox(4, top, sub);
                card.getStyleClass().add("dashboard-monthly-history-card");
                card.setMaxWidth(Double.MAX_VALUE);
                extraBox.getChildren().add(card);
            }
        }

        boolean hasExtra = extraBox != null && !extraBox.getChildren().isEmpty();
        if (toggle != null) {
            toggle.setVisible(hasExtra);
            toggle.setManaged(hasExtra);
        }
        if (!hasExtra) {
            if (extraBox != null) {
                extraBox.setVisible(false);
                extraBox.setManaged(false);
            }
            if (expanded != null) {
                expanded.set(false);
            }
            if (toggle != null) {
                toggle.setText("Ver detalles  >");
            }
        } else if (extraBox != null && expanded != null) {
            boolean isExpanded = expanded.get();
            extraBox.setVisible(isExpanded);
            extraBox.setManaged(isExpanded);
            if (toggle != null) {
                toggle.setText(isExpanded ? "Ver menos" : "Ver detalles  >");
            }
        }
    }

    private static Region buildMonthlySummaryCard(
        Label monthIncomeValue,
        Label monthExpenseValue,
        Label monthBalanceValue,
        Label monthCompareValue,
        VBox monthlyHistoryPrimaryBox,
        VBox monthlyHistoryExtraBox,
        Hyperlink monthlyHistoryToggle
    ) {
        StackPane card = new StackPane();
        card.getStyleClass().addAll("card", "content-card", "dashboard-monthly-card", "dashboard-monthly-card-redesign");

        Pane deco = new Pane();
        deco.setMouseTransparent(true);
        deco.setManaged(false);

        Rectangle waveFill = new Rectangle();
        waveFill.setMouseTransparent(true);
        waveFill.setManaged(false);
        waveFill.setFill(Color.web("#93C5FD", 0.18));

        SVGPath wave1 = new SVGPath();
        wave1.setContent("M 0 95 C 80 70 140 120 220 95 C 300 70 360 120 440 95 C 520 70 600 120 680 95 L 680 170 L 0 170 Z");
        wave1.getStyleClass().add("dashboard-monthly-wave-1");
        wave1.setLayoutX(0);
        wave1.setLayoutY(38);

        SVGPath wave2 = new SVGPath();
        wave2.setContent("M 0 120 C 90 92 150 150 240 120 C 330 92 390 150 480 120 C 570 92 640 150 730 120 L 730 190 L 0 190 Z");
        wave2.getStyleClass().add("dashboard-monthly-wave-2");
        wave2.setLayoutX(0);
        wave2.setLayoutY(34);

        Circle b1 = new Circle(18);
        b1.getStyleClass().add("dashboard-monthly-bubble");
        b1.setLayoutX(520);
        b1.setLayoutY(28);
        b1.setOpacity(0.35);

        Circle b2 = new Circle(10);
        b2.getStyleClass().add("dashboard-monthly-bubble");
        b2.setLayoutX(560);
        b2.setLayoutY(64);
        b2.setOpacity(0.22);

        Circle b3 = new Circle(14);
        b3.getStyleClass().add("dashboard-monthly-bubble");
        b3.setLayoutX(480);
        b3.setLayoutY(78);
        b3.setOpacity(0.18);

        deco.getChildren().addAll(waveFill, wave2, wave1, b1, b2, b3);

        card.heightProperty().addListener((obs, oldV, newV) -> {
            double h = newV == null ? 0.0 : newV.doubleValue();
            double w = card.getWidth();
            waveFill.setWidth(Math.max(0, w));
            waveFill.setHeight(Math.max(0, h));
        });

        card.widthProperty().addListener((obs, oldV, newV) -> {
            double w = newV == null ? 0.0 : newV.doubleValue();
            waveFill.setWidth(Math.max(0, w));
        });



        Label title = new Label("Este mes");
        title.getStyleClass().add("account-name");

        Label balanceCaption = new Label("Balance");
        balanceCaption.getStyleClass().addAll("text-secondary", "dashboard-monthly-caption");
        HBox balanceRow = new HBox(10, balanceCaption, monthBalanceValue);
        balanceRow.setAlignment(Pos.BASELINE_LEFT);

        Label incomeCaption = new Label("Ingresos");
        incomeCaption.getStyleClass().addAll("text-secondary", "dashboard-monthly-caption");
        VBox income = new VBox(2, incomeCaption, monthIncomeValue);

        Label expenseCaption = new Label("Gastos");
        expenseCaption.getStyleClass().addAll("text-secondary", "dashboard-monthly-caption");
        VBox expense = new VBox(2, expenseCaption, monthExpenseValue);

        HBox kpis = new HBox(18, income, expense);
        kpis.setAlignment(Pos.CENTER_LEFT);

        Label compareCaption = new Label("Vs mes anterior");
        compareCaption.getStyleClass().add("text-secondary");
        HBox compareRow = new HBox(8, compareCaption, monthCompareValue);
        compareRow.setAlignment(Pos.CENTER_LEFT);
        compareRow.getStyleClass().add("dashboard-monthly-compare-row");

        VBox left = new VBox(8, balanceRow, kpis, compareRow);
        left.setAlignment(Pos.TOP_LEFT);
        left.getStyleClass().add("dashboard-monthly-left");

        Label accountsTitle = new Label("Meses anteriores");
        accountsTitle.getStyleClass().add("account-name");

        HBox accountsHeader = new HBox(10, accountsTitle);
        Region accountsSpacer = new Region();
        HBox.setHgrow(accountsSpacer, Priority.ALWAYS);
        if (monthlyHistoryToggle != null) {
            accountsHeader.getChildren().addAll(accountsSpacer, monthlyHistoryToggle);
        } else {
            accountsHeader.getChildren().addAll(accountsSpacer);
        }
        accountsHeader.setAlignment(Pos.CENTER_LEFT);

        VBox right = new VBox(8, accountsHeader, monthlyHistoryPrimaryBox);
        if (monthlyHistoryExtraBox != null) {
            right.getChildren().add(monthlyHistoryExtraBox);
        }
        right.setAlignment(Pos.TOP_LEFT);
        right.getStyleClass().add("dashboard-monthly-right");

        HBox body = new HBox(14, left, right);
        body.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(8, title, body);
        content.setAlignment(Pos.TOP_LEFT);
        content.setMaxWidth(Double.MAX_VALUE);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(card.widthProperty());
        clip.heightProperty().bind(card.heightProperty());
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        card.setClip(clip);

        card.getChildren().addAll(deco, content);
        StackPane.setAlignment(content, Pos.TOP_LEFT);

        card.widthProperty().addListener((obs, o, n) -> {
            double w = n == null ? 0.0 : n.doubleValue();
            if (w <= 0) {
                return;
            }
            // Keep waves spanning the card width
            wave1.setScaleX(w / 680.0);
            wave2.setScaleX(w / 730.0);
        });

        return card;
    }

    private static void showSummaryDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        GoalRepository goalRepo,
        boolean darkTheme
    ) {
        DashboardSummaryDialog.showSummaryDialog(userUid, txRepo, accountRepo, categoryRepo, goalRepo, darkTheme);
    }

    private static void showLoansDialog(
        AuthSession session,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        TransactionRepository txRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        DashboardLoansDialog.showLoansDialog(session, loanRepo, loanPaymentRepo, accountRepo, categoryRepo, txRepo, darkTheme, refreshBalances);
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
        DashboardBudgetDialog.showBudgetDialog(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme, refreshBalances);
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
        DashboardBudgetDialog.showBudgetDialog(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme, refreshBalances, initialTabIndex);
    }

    private static void showChartsDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        GoalRepository goalRepo,
        TransferRepository transferRepo,
        boolean darkTheme
    ) {
        DashboardChartsDialog.showChartsDialog(userUid, txRepo, accountRepo, categoryRepo, goalRepo, transferRepo, darkTheme);
    }

    private static void showTransfersDialog(
        AuthSession session,
        TransferRepository transferRepo,
        AccountRepository accountRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        DashboardTransfersDialog.showTransfersDialog(session, transferRepo, accountRepo, darkTheme, refreshBalances);
    }

    private static void showCategoriesDialog(AuthSession session, CategoryRepository categoryRepo, boolean darkTheme) {
        DashboardCategoriesDialog.showCategoriesDialog(session, categoryRepo, darkTheme);
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
        contentLabel.getStyleClass().add("text-secondary");
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(520);

        VBox body = new VBox(12, headerBox, contentLabel);
        body.setPadding(new Insets(14));

        a.setHeaderText(null);
        a.setContentText(null);
        a.getDialogPane().setContent(body);
        a.getDialogPane().setMinWidth(560);
        a.getDialogPane().setPrefWidth(560);
        a.getDialogPane().setGraphic(null);
        UiDialogs.applyAppTheme(a, darkTheme);
        return a;
    }

    private static Alert buildAlert(AlertType type, String title, String header, String content, boolean darkTheme, String iconLiteral) {
        Alert a = new Alert(type);
        a.setTitle(title);

        FontIcon icon;
        if (iconLiteral != null && !iconLiteral.isBlank()) {
            icon = new FontIcon(iconLiteral);
        } else {
            icon = new FontIcon("fas-info-circle");
        }
        icon.setIconSize(22);
        icon.getStyleClass().add(type == AlertType.ERROR || type == AlertType.WARNING ? "text-danger" : "text-secondary");

        Label headerLabel = new Label(header == null ? "" : header);
        headerLabel.getStyleClass().add("account-name");

        HBox headerBox = new HBox(10, icon, headerLabel);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        Label contentLabel = new Label(content == null ? "" : content);
        contentLabel.getStyleClass().add("text-secondary");
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(520);

        VBox body = new VBox(12, headerBox, contentLabel);
        body.setPadding(new Insets(14));

        a.setHeaderText(null);
        a.setContentText(null);
        a.getDialogPane().setContent(body);
        a.getDialogPane().setMinWidth(560);
        a.getDialogPane().setPrefWidth(560);
        a.getDialogPane().setGraphic(null);
        UiDialogs.applyAppTheme(a, darkTheme);
        return a;
    }

    private static void clearTotalTrendBackdrop(Polyline wave1, Polyline wave2, Polygon area, Polyline glow, Polyline line, Circle dot) {
        if (wave1 != null) {
            wave1.getPoints().clear();
        }
        if (wave2 != null) {
            wave2.getPoints().clear();
        }
        if (area != null) {
            area.getPoints().clear();
        }
        if (glow != null) {
            glow.getPoints().clear();
        }
        if (line != null) {
            line.getPoints().clear();
        }
        if (dot != null) {
            dot.setVisible(false);
        }
    }

    private static void drawTotalTrendBackdrop(
        Pane backdrop,
        Polyline wave1,
        Polyline wave2,
        Polygon area,
        Polyline glow,
        Polyline line,
        Circle dot,
        List<TransactionRepository.TransactionRow> monthTx,
        long monthBalanceCents
    ) {
        if (backdrop == null || wave1 == null || wave2 == null || area == null || glow == null || line == null || dot == null) {
            return;
        }

        double w = backdrop.getWidth();
        double h = backdrop.getHeight();
        if (w <= 0 || h <= 0) {
            Platform.runLater(() -> drawTotalTrendBackdrop(backdrop, wave1, wave2, area, glow, line, dot, monthTx, monthBalanceCents));
            return;
        }

        wave1.getPoints().clear();
        wave2.getPoints().clear();
        int steps = 44;
        double baseY1 = h * 0.72;
        double amp1 = h * 0.08;
        double baseY2 = h * 0.82;
        double amp2 = h * 0.06;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = t * w;
            double y1 = baseY1 + Math.sin(t * Math.PI * 2.15) * amp1;
            double y2 = baseY2 + Math.sin((t * Math.PI * 2.25) + (Math.PI / 5.0)) * amp2;
            wave1.getPoints().addAll(x, y1);
            wave2.getPoints().addAll(x, y2);
        }
        wave1.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.18));
        wave1.setStrokeWidth(1.6);
        wave1.setFill(null);
        wave2.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.12));
        wave2.setStrokeWidth(1.6);
        wave2.setFill(null);

        int daysInMonth = java.time.LocalDate.now().lengthOfMonth();
        long[] daily = new long[Math.max(1, daysInMonth)];
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();

        for (TransactionRepository.TransactionRow t : monthTx) {
            if (t == null || t.kind() == null) {
                continue;
            }
            long epoch = t.occurredAtEpochSec();
            java.time.LocalDate d = java.time.Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate();
            int idx = Math.max(0, Math.min(daysInMonth - 1, d.getDayOfMonth() - 1));
            String k = t.kind().trim().toUpperCase();
            long amt = t.amountCents();
            if ("INCOME".equals(k)) {
                daily[idx] += Math.max(0L, amt);
            } else if ("EXPENSE".equals(k)) {
                daily[idx] -= Math.max(0L, amt);
            }
        }
        for (int i = 1; i < daily.length; i++) {
            daily[i] += daily[i - 1];
        }

        long min = 0L;
        long max = 0L;
        for (long v : daily) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min == max) {
            max = min + 1L;
        }

        double chartLeft = w * 0.44;
        double chartRight = w * 0.96;
        double chartTop = h * 0.28;
        double chartBottom = h * 0.86;

        line.getPoints().clear();
        glow.getPoints().clear();
        area.getPoints().clear();

        double lastX = 0;
        double lastY = 0;
        for (int i = 0; i < daily.length; i++) {
            double t = (daily.length == 1) ? 0.0 : (i / (double) (daily.length - 1));
            double x = chartLeft + t * (chartRight - chartLeft);
            double norm = (daily[i] - min) / (double) (max - min);
            double y = chartBottom - norm * (chartBottom - chartTop);
            glow.getPoints().addAll(x, y);
            line.getPoints().addAll(x, y);
            if (i == 0) {
                area.getPoints().addAll(x, chartBottom);
            }
            area.getPoints().addAll(x, y);
            lastX = x;
            lastY = y;
        }
        area.getPoints().addAll(lastX, chartBottom);

        javafx.scene.paint.Color lineColor = monthBalanceCents >= 0
            ? javafx.scene.paint.Color.rgb(134, 239, 172, 0.95)
            : javafx.scene.paint.Color.rgb(252, 165, 165, 0.95);

        javafx.scene.paint.Color areaColor = monthBalanceCents >= 0
            ? javafx.scene.paint.Color.rgb(134, 239, 172, 0.16)
            : javafx.scene.paint.Color.rgb(252, 165, 165, 0.16);

        javafx.scene.paint.Color glowColor = monthBalanceCents >= 0
            ? javafx.scene.paint.Color.rgb(134, 239, 172, 0.25)
            : javafx.scene.paint.Color.rgb(252, 165, 165, 0.25);

        area.setFill(areaColor);
        area.setStroke(null);

        glow.setStroke(glowColor);
        glow.setStrokeWidth(10);
        glow.setFill(null);

        line.setStroke(lineColor);
        line.setStrokeWidth(2.2);
        line.setFill(null);

        dot.setRadius(4.0);
        dot.setCenterX(lastX);
        dot.setCenterY(lastY);
        dot.setFill(javafx.scene.paint.Color.rgb(255, 255, 255, 0.40));
        dot.setVisible(true);
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
        return DashboardFormatters.formatUserDecimal(cents);
    }

    private static String formatSignedUserDecimal(long cents) {
        return DashboardFormatters.formatSignedUserDecimal(cents);
    }

    private static String formatMoney(long cents) {
        return DashboardFormatters.formatMoney(cents);
    }

    private static String formatMoney(long cents, String currencyCode) {
        return DashboardFormatters.formatMoney(cents, currencyCode);
    }
}
