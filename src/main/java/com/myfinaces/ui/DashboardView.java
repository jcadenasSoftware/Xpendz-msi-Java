package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.config.AppConfig;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.scene.control.TextFormatter;
import javafx.util.Duration;

import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

        AtomicReference<Runnable> refreshBalancesRef = new AtomicReference<>();
        DashboardBalancesPane.Parts balancesUi = DashboardBalancesPane.build(refreshBalancesRef);
        Label totalValue = balancesUi.totalValue();
        VBox totalCard = balancesUi.totalCard();
        VBox accountsBox = balancesUi.accountsBox();
        VBox goalsBox = balancesUi.goalsBox();
        ScrollPane accountsScroll = balancesUi.accountsScroll();
        ScrollPane goalsScroll = balancesUi.goalsScroll();
        AtomicBoolean hideTotalBalance = balancesUi.hideTotalBalance();

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
            syncBlockedUntilMs
        );
        Runnable runSyncNow = syncActions.runSyncNow();
        Runnable doRefreshNow = syncActions.doRefreshNow();

        Button addAccount = new Button("Agregar cuenta");
        addAccount.getStyleClass().add("btn-primary");
        addAccount.getStyleClass().add("nav-button");
        addAccount.setMaxWidth(Double.MAX_VALUE);
        setButtonIcon(addAccount, new FontIcon("fas-plus-circle"));
        addAccount.setOnAction(e -> {
            Optional<DashboardAccountsFeature.NewAccount> newAccount = DashboardAccountsFeature.showCreateAccountDialog(darkTheme.get());
            if (newAccount.isEmpty()) {
                return;
            }

            try {
                DashboardAccountsFeature.NewAccount a = newAccount.get();
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
        transactions.setOnAction(e -> DashboardTransactionsDialog.showTransactionsDialog(session, txRepo, accountRepo, categoryRepo, darkTheme.get(), refreshBalances));

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

        autoSyncRef.set(scheduler.scheduleAtFixedRate(() -> {
            runSyncNow.run();
        }, 120, 1800, TimeUnit.SECONDS));

        ScrollPane sidebarScroll = DashboardSidebarPane.build(
            session.email(),
            transactions,
            transfers,
            summary,
            loans,
            budget,
            charts,
            addAccount,
            categories,
            syncNow,
            logout,
            exit
        );

        HBox headerBar = DashboardHeaderPane.build(title, toggleTheme);

        VBox content = new VBox(14, headerBar, totalCard, accountsScroll, goalsScroll);
        content.getStyleClass().add("content");
        content.setPadding(new Insets(20));
        VBox.setVgrow(accountsScroll, Priority.ALWAYS);
        VBox.setVgrow(goalsScroll, Priority.SOMETIMES);

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

    private static void showChartsDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        boolean darkTheme
    ) {
        DashboardChartsDialog.showChartsDialog(userUid, txRepo, accountRepo, categoryRepo, darkTheme);
    }

    private static void showSummaryDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        boolean darkTheme
    ) {
        DashboardSummaryDialog.showSummaryDialog(userUid, txRepo, accountRepo, categoryRepo, darkTheme);
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

    private static void showLoansDialog(
        AuthSession session,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        AccountRepository accountRepo,
        boolean darkTheme
    ) {
        DashboardLoansDialog.showLoansDialog(session, loanRepo, loanPaymentRepo, accountRepo, darkTheme);
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

                    Optional<DashboardAccountsFeature.EditAccountResult> res = DashboardAccountsFeature.showEditAccountDialog(a, darkTheme.get());
                    if (res.isEmpty()) {
                        return;
                    }

                    try {
                        if (res.get().action() == DashboardAccountsFeature.EditAccountAction.SAVE) {
                            AccountRepository.Account updated = accountRepo.updateName(session.uid(), a.id(), res.get().newName());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncAccount(session, updated);
                            } catch (Exception ignored) {
                            }
                            refreshBalances(session, accountRepo, goalRepo, txRepo, transferRepo, totalValue, accountsBox, goalsBox, darkTheme, hideTotalBalance, openBudgetGoalsTab);
                        } else if (res.get().action() == DashboardAccountsFeature.EditAccountAction.VIEW_SUMMARY) {
                            DashboardAccountsFeature.showAccountSummaryDialog(session.uid(), a, txRepo, transferRepo, accountRepo, darkTheme.get());
                        } else if (res.get().action() == DashboardAccountsFeature.EditAccountAction.DELETE) {
                            Alert confirm = buildAlert(
                                AlertType.CONFIRMATION,
                                "Eliminar cuenta",
                                "¿Eliminar cuenta?",
                                "Esta acción también eliminará sus transacciones y transferencias asociadas.",
                                darkTheme.get()
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
                                    refreshBalances(session, accountRepo, goalRepo, txRepo, transferRepo, totalValue, accountsBox, goalsBox, darkTheme, hideTotalBalance, openBudgetGoalsTab);
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

    private static BigDecimal parseAmount(String raw) {
        return DashboardFormatters.parseAmount(raw);
    }
}
