package com.myfinaces.ui;

import com.myfinaces.config.AppConfig;
import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.PopupWindow;
import javafx.stage.Screen;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public final class DashboardTransactionsDialog {

    private DashboardTransactionsDialog() {
    }

    private static String kindForRootCategory(CategoryRepository.Category root) {
        if (root == null) {
            return null;
        }
        if (root.kind() != null && !root.kind().isBlank()) {
            // Root categories may have kind=BOTH as a compatibility/default value.
            // Transactions must use concrete kinds (INCOME/EXPENSE/...) for correct balances + sync.
            if (!"BOTH".equalsIgnoreCase(root.kind().trim())) {
                return root.kind();
            }
        }
        if (root.name() == null) {
            return null;
        }
        String rootName = root.name().trim();
        if ("INGRESOS".equalsIgnoreCase(rootName) || rootName.toUpperCase().startsWith("INGRESOS")) {
            return "INCOME";
        }
        if ("GASTOS".equalsIgnoreCase(rootName) || rootName.toUpperCase().startsWith("GASTOS")) {
            return "EXPENSE";
        }
        return null;
    }

    private static String accountTypeLabel(String type) {
        String t = AccountRepository.normalizeType(type);
        if ("BANK".equalsIgnoreCase(t)) {
            return "Banco";
        }
        if ("CREDIT".equalsIgnoreCase(t)) {
            return "Crédito";
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

    private static String formatAccountLabel(AccountRepository.Account a) {
        if (a == null) {
            return "";
        }
        return a.name();
    }

    public static void showTransactionsDialog(
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
        headerText.setAlignment(javafx.geometry.Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, javafx.geometry.Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(null);

        javafx.scene.Node embedded = buildTransactionsPane(session, txRepo, accountRepo, categoryRepo, () -> darkTheme, refreshBalances);
        dialog.getDialogPane().setContent(embedded);
        dialog.showAndWait();
    }

    public static javafx.scene.Node buildTransactionsPane(
        AuthSession session,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        BooleanSupplier darkTheme,
        Runnable refreshBalances
    ) {
        String userUid = session.uid();

        VBox txBox = new VBox(8);
        txBox.getStyleClass().add("tx-timeline");
        ScrollPane txScroll = new ScrollPane(txBox);
        txScroll.setFitToWidth(true);
        txScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        txScroll.getStyleClass().addAll("card", "content-card", "tx-scroll");
        VBox.setVgrow(txScroll, Priority.ALWAYS);

        ChoiceBox<AccountRepository.Account> txAccountFilter = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> txRootCategoryFilter = new ChoiceBox<>();
        ChoiceBox<String> txKindFilter = new ChoiceBox<>();
        MenuButton txDateFilter = new MenuButton();
        DatePicker txFromDate = new DatePicker();
        DatePicker txToDate = new DatePicker();

        TextField searchField = new TextField();
        searchField.setPromptText("Buscar transacciones...");
        searchField.getStyleClass().add("tx-search");
        FontIcon searchIcon = new FontIcon("fas-search");
        searchIcon.getStyleClass().add("tx-search-icon");
        HBox searchBox = new HBox(8, searchIcon, searchField);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.getStyleClass().add("tx-search-box");

        Button newTx = new Button("+ Nueva");
        newTx.getStyleClass().add("btn-primary");
        newTx.getStyleClass().add("tx-new-button");
        newTx.setOnAction(e -> {
            Optional<NewTransaction> t = showCreateTransactionDialog(userUid, accountRepo, categoryRepo, darkTheme.getAsBoolean());
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
            } catch (Exception ignored) {
            }
        });

        Label title = new Label("Transacciones");
        title.getStyleClass().add("tx-header-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(12, title, headerSpacer, newTx);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("tx-header");

        Label incomeValue = new Label("0");
        incomeValue.getStyleClass().addAll("tx-metric-value", "money-positive");
        Label expenseValue = new Label("0");
        expenseValue.getStyleClass().addAll("tx-metric-value", "money-negative");
        Label balanceValue = new Label("0");
        balanceValue.getStyleClass().addAll("tx-metric-value", "money-neutral");

        VBox incomeBox = new VBox(2, new Label("Ingresos"), incomeValue);
        VBox expenseBox = new VBox(2, new Label("Gastos"), expenseValue);
        VBox balanceBox = new VBox(2, new Label("Balance"), balanceValue);
        incomeBox.getStyleClass().add("tx-metric");
        expenseBox.getStyleClass().add("tx-metric");
        balanceBox.getStyleClass().add("tx-metric");
        HBox metricsRow = new HBox(18, incomeBox, expenseBox, balanceBox);
        metricsRow.getStyleClass().add("tx-summary");
        HBox.setHgrow(metricsRow, Priority.ALWAYS);

        Region summarySpacer = new Region();
        HBox.setHgrow(summarySpacer, Priority.ALWAYS);
        searchBox.setMaxWidth(340);

        HBox summaryRow = new HBox(18, metricsRow, summarySpacer, searchBox);
        summaryRow.setAlignment(Pos.CENTER_LEFT);

        VBox summaryCard = new VBox(summaryRow);
        summaryCard.getStyleClass().addAll("card", "content-card", "tx-summary-card");
        summaryCard.setPadding(new Insets(12));

        txKindFilter.getItems().addAll(null, "INCOME", "EXPENSE");
        txKindFilter.getSelectionModel().selectFirst();
        txKindFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String object) {
                if (object == null) {
                    return "Tipo";
                }
                if ("INCOME".equalsIgnoreCase(object)) {
                    return "Ingresos";
                }
                if ("EXPENSE".equalsIgnoreCase(object)) {
                    return "Gastos";
                }
                return object;
            }

            @Override
            public String fromString(String string) {
                return null;
            }
        });
        txKindFilter.getStyleClass().add("tx-chip");

        txAccountFilter.getStyleClass().add("tx-chip");
        txRootCategoryFilter.getStyleClass().add("tx-chip");
        txDateFilter.getStyleClass().add("tx-chip");

        txDateFilter.setText("Fecha");
        MenuItem dateAll = new MenuItem("Todas");
        MenuItem dateToday = new MenuItem("Hoy");
        MenuItem dateYesterday = new MenuItem("Ayer");
        MenuItem dateThisMonth = new MenuItem("Este mes");
        MenuItem dateCustom = new MenuItem("Rango...");
        txDateFilter.getItems().setAll(dateAll, dateToday, dateYesterday, dateThisMonth, dateCustom);

        Runnable applyDateLabel = () -> {
            if (txFromDate.getValue() == null && txToDate.getValue() == null) {
                txDateFilter.setText("Fecha");
                return;
            }
            String from = txFromDate.getValue() == null ? "" : txFromDate.getValue().toString();
            String to = txToDate.getValue() == null ? "" : txToDate.getValue().toString();
            if (!from.isBlank() && !to.isBlank()) {
                txDateFilter.setText(from + " - " + to);
            } else if (!from.isBlank()) {
                txDateFilter.setText(from + " - Hoy");
            } else {
                txDateFilter.setText("Hasta " + to);
            }
        };

        dateAll.setOnAction(e -> {
            txFromDate.setValue(null);
            txToDate.setValue(null);
            applyDateLabel.run();
        });
        dateToday.setOnAction(e -> {
            LocalDate d = LocalDate.now();
            txFromDate.setValue(d);
            txToDate.setValue(d);
            applyDateLabel.run();
        });
        dateYesterday.setOnAction(e -> {
            LocalDate d = LocalDate.now().minusDays(1);
            txFromDate.setValue(d);
            txToDate.setValue(d);
            applyDateLabel.run();
        });
        dateThisMonth.setOnAction(e -> {
            LocalDate now = LocalDate.now();
            txFromDate.setValue(now.withDayOfMonth(1));
            txToDate.setValue(now);
            applyDateLabel.run();
        });
        dateCustom.setOnAction(e -> {
            Dialog<ButtonType> rangeDialog = new Dialog<>();
            rangeDialog.setTitle("Rango de fechas");
            rangeDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            UiDialogs.applyAppTheme(rangeDialog, darkTheme.getAsBoolean());

            DatePicker from = new DatePicker(txFromDate.getValue());
            DatePicker to = new DatePicker(txToDate.getValue());
            VBox content = new VBox(10, new HBox(10, new Label("Desde"), from), new HBox(10, new Label("Hasta"), to));
            content.setPadding(new Insets(14));
            rangeDialog.getDialogPane().setContent(content);
            rangeDialog.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                txFromDate.setValue(from.getValue());
                txToDate.setValue(to.getValue());
                applyDateLabel.run();
            });
        });

        FlowPane chipsRow = new FlowPane(10, 10);
        chipsRow.getStyleClass().add("tx-chips");
        chipsRow.getChildren().addAll(txAccountFilter, txRootCategoryFilter, txDateFilter, txKindFilter);

        Button fab = new Button("+");
        fab.getStyleClass().add("tx-fab");
        fab.setOnAction(e -> newTx.fire());

        Runnable refreshTx = () -> refreshTransactions(
            session,
            userUid,
            txRepo,
            txBox,
            txAccountFilter.getValue() == null ? null : txAccountFilter.getValue().id(),
            txRootCategoryFilter.getValue() == null ? null : txRootCategoryFilter.getValue().id(),
            txFromDate.getValue(),
            txToDate.getValue(),
            txKindFilter.getValue(),
            searchField.getText(),
            incomeValue,
            expenseValue,
            balanceValue,
            darkTheme.getAsBoolean(),
            accountRepo,
            categoryRepo,
            refreshBalances
        );

        try {
            txAccountFilter.getItems().add(null);
            List<AccountRepository.Account> accounts = new ArrayList<>(accountRepo.list(userUid));
            accounts.sort(
                Comparator
                    .comparing((AccountRepository.Account a) -> accountTypeLabel(a == null ? null : a.type()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(a -> a == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER)
            );
            txAccountFilter.getItems().addAll(accounts);
            txAccountFilter.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        txAccountFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : formatAccountLabel(object);
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
                return object == null ? "Categoría" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        txAccountFilter.valueProperty().addListener((obs, o, n) -> refreshTx.run());
        txRootCategoryFilter.valueProperty().addListener((obs, o, n) -> refreshTx.run());
        txFromDate.valueProperty().addListener((obs, o, n) -> {
            applyDateLabel.run();
            refreshTx.run();
        });
        txToDate.valueProperty().addListener((obs, o, n) -> {
            applyDateLabel.run();
            refreshTx.run();
        });
        txKindFilter.valueProperty().addListener((obs, o, n) -> refreshTx.run());
        searchField.textProperty().addListener((obs, o, n) -> refreshTx.run());

        VBox content = new VBox(12, headerRow, summaryCard, chipsRow, txScroll);
        content.setPadding(new Insets(14));

        StackPane root = new StackPane(content, fab);
        StackPane.setAlignment(fab, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(fab, new Insets(0, 20, 16, 0));

        applyDateLabel.run();
        refreshTx.run();
        return root;
    }

    private static void refreshTransactions(
        AuthSession session,
        String userUid,
        TransactionRepository txRepo,
        VBox txBox,
        String accountId,
        String rootCategoryId,
        LocalDate fromDate,
        LocalDate toDate,
        String kindFilter,
        String query,
        Label incomeValue,
        Label expenseValue,
        Label balanceValue,
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
            if (rootCategoryId != null && !rootCategoryId.isBlank()) {
                categoryIds = new ArrayList<>();
                categoryIds.add(rootCategoryId);
                try {
                    for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, rootCategoryId)) {
                        categoryIds.add(c.id());
                    }
                } catch (Exception ignored) {
                }
            }

            int limit = 80;
            if (fromEpoch != null || toEpoch != null) {
                limit = 5000;
            }
            String rawQuery = query == null ? "" : query.trim();
            if (!rawQuery.isBlank() || (kindFilter != null && !kindFilter.isBlank())) {
                limit = Math.max(limit, 2000);
            }
            List<TransactionRepository.TransactionRow> txs = txRepo.listFiltered(userUid, accountId, (List<String>) categoryIds, fromEpoch, toEpoch, limit);

            String usedCurrency = null;
            if (accountId != null) {
                usedCurrency = accountCurrency.get(accountId);
            }
            if (usedCurrency == null && !txs.isEmpty()) {
                usedCurrency = accountCurrency.get(txs.get(0).accountId());
            }
            if (usedCurrency == null) {
                usedCurrency = "COP";
            }

            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (!q.isBlank()) {
                List<TransactionRepository.TransactionRow> filtered = new ArrayList<>();
                for (TransactionRepository.TransactionRow t : txs) {
                    String note = t.note() == null ? "" : t.note();
                    String hay = (t.accountName() + " " + t.categoryName() + " " + note).toLowerCase(Locale.ROOT);
                    if (hay.contains(q)) {
                        filtered.add(t);
                    }
                }
                txs = filtered;
            }

            if (kindFilter != null && !kindFilter.isBlank()) {
                List<TransactionRepository.TransactionRow> filtered = new ArrayList<>();
                for (TransactionRepository.TransactionRow t : txs) {
                    if (kindFilter.equalsIgnoreCase(t.kind())) {
                        filtered.add(t);
                    }
                }
                txs = filtered;
            }

            long incomeCents = 0L;
            long expenseCents = 0L;
            for (TransactionRepository.TransactionRow t : txs) {
                if ("EXPENSE".equalsIgnoreCase(t.kind())) {
                    expenseCents += Math.max(0L, t.amountCents());
                } else {
                    incomeCents += Math.max(0L, t.amountCents());
                }
            }
            long balanceCents = incomeCents - expenseCents;
            if (incomeValue != null) {
                incomeValue.setText(DashboardFormatters.formatMoney(incomeCents, usedCurrency));
                incomeValue.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                incomeValue.getStyleClass().add("money-positive");
            }
            if (expenseValue != null) {
                expenseValue.setText(DashboardFormatters.formatMoney(-expenseCents, usedCurrency));
                expenseValue.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                expenseValue.getStyleClass().add("money-negative");
            }
            if (balanceValue != null) {
                balanceValue.setText(DashboardFormatters.formatMoney(balanceCents, usedCurrency));
                balanceValue.getStyleClass().removeAll("money-positive", "money-negative", "money-neutral");
                if (balanceCents > 0) {
                    balanceValue.getStyleClass().add("money-positive");
                } else if (balanceCents < 0) {
                    balanceValue.getStyleClass().add("money-negative");
                } else {
                    balanceValue.getStyleClass().add("money-neutral");
                }
            }

            if (txs.isEmpty()) {
                Label empty = new Label("No hay transacciones aún.");
                empty.getStyleClass().add("text-secondary");
                txBox.getChildren().add(empty);
                return;
            }

            LocalDate lastDate = null;
            for (TransactionRepository.TransactionRow t : txs) {
                Instant ins = Instant.ofEpochSecond(t.occurredAtEpochSec());
                LocalDate d = ins.atZone(ZoneId.systemDefault()).toLocalDate();
                LocalTime tm = ins.atZone(ZoneId.systemDefault()).toLocalTime().withNano(0);

                if (lastDate == null || !lastDate.equals(d)) {
                    Label group = new Label(formatGroupLabel(d));
                    group.getStyleClass().add("tx-group-title");
                    txBox.getChildren().add(group);
                    lastDate = d;
                }

                String txTitleText = (t.note() != null && !t.note().trim().isBlank()) ? t.note().trim() : t.categoryName();
                Label txTitle = new Label(txTitleText);
                txTitle.getStyleClass().add("tx-item-title");
                txTitle.setMaxWidth(Double.MAX_VALUE);
                txTitle.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

                Label txSubtitle = new Label(t.accountName() + " · " + t.categoryName());
                txSubtitle.getStyleClass().add("tx-item-subtitle");
                txSubtitle.setMaxWidth(Double.MAX_VALUE);
                txSubtitle.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

                VBox txText = new VBox(2, txTitle, txSubtitle);
                txText.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(txText, Priority.ALWAYS);

                FontIcon icon = new FontIcon("fas-tag");
                icon.getStyleClass().add("tx-item-icon");
                StackPane iconBubble = new StackPane(icon);
                iconBubble.getStyleClass().add("tx-item-icon-bubble");
                iconBubble.getStyleClass().add(
                    "EXPENSE".equalsIgnoreCase(t.kind()) ? "tx-item-icon-expense" : "tx-item-icon-income"
                );

                long signed = "EXPENSE".equalsIgnoreCase(t.kind()) ? -t.amountCents() : t.amountCents();
                String currency = accountCurrency.get(t.accountId());
                if (currency == null || currency.isBlank()) {
                    currency = usedCurrency;
                }

                Label amount = new Label(DashboardFormatters.formatMoney(signed, currency));
                amount.getStyleClass().add("tx-item-amount");
                if (signed > 0) {
                    amount.getStyleClass().add("money-positive");
                } else if (signed < 0) {
                    amount.getStyleClass().add("money-negative");
                } else {
                    amount.getStyleClass().add("money-neutral");
                }

                Label time = new Label(tm.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ROOT)));
                time.getStyleClass().add("tx-item-time");
                VBox rightBox = new VBox(2, amount, time);
                rightBox.setAlignment(Pos.CENTER_RIGHT);

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
                        refreshTransactions(session, userUid, txRepo, txBox, accountId, rootCategoryId, fromDate, toDate, kindFilter, query, incomeValue, expenseValue, balanceValue, darkTheme, accountRepo, categoryRepo, refreshBalances);
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
                            refreshTransactions(session, userUid, txRepo, txBox, accountId, rootCategoryId, fromDate, toDate, kindFilter, query, incomeValue, expenseValue, balanceValue, darkTheme, accountRepo, categoryRepo, refreshBalances);
                        } catch (Exception ignored) {
                        }
                    });
                };

                MenuItem edit = new MenuItem("Editar");
                edit.setOnAction(ev -> doEdit.run());
                MenuItem del = new MenuItem("Eliminar");
                del.setOnAction(ev -> doDelete.run());
                ContextMenu menu = new ContextMenu(edit, del);

                Button more = new Button();
                more.getStyleClass().add("tx-item-more");
                FontIcon dots = new FontIcon("fas-ellipsis-v");
                dots.getStyleClass().add("tx-item-more-icon");
                more.setGraphic(dots);
                more.setOpacity(0);
                more.setOnAction(ev -> menu.show(more, javafx.geometry.Side.BOTTOM, 0, 0));

                HBox row = new HBox(12, iconBubble, txText, rightBox, more);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("tx-item");
                row.setOnMouseEntered(ev -> more.setOpacity(1));
                row.setOnMouseExited(ev -> more.setOpacity(0));
                row.setOnMouseClicked(ev -> {
                    if (ev.getClickCount() >= 2) {
                        doEdit.run();
                    }
                });
                row.setOnContextMenuRequested(ev -> menu.show(row, ev.getScreenX(), ev.getScreenY()));
                txBox.getChildren().add(row);
            }
        } catch (Exception ex) {
            txBox.getChildren().add(new Label(ex.getMessage() == null ? "Error" : ex.getMessage()));
        }
    }

    private static String formatGroupLabel(LocalDate d) {
        if (d == null) {
            return "";
        }
        LocalDate today = LocalDate.now();
        if (d.equals(today)) {
            return "HOY";
        }
        if (d.equals(today.minusDays(1))) {
            return "AYER";
        }
        Locale es = Locale.forLanguageTag("es-CO");
        String month = d.getMonth().getDisplayName(TextStyle.FULL, es).toUpperCase(es);
        return d.getDayOfMonth() + " " + month + " " + d.getYear();
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
        ButtonType saveBtnType = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtnType = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, cancelBtnType);
        UiDialogs.applyAppTheme(dialog, darkTheme);

        dialog.getDialogPane().getStyleClass().remove("categories-dialog");
        if (!dialog.getDialogPane().getStyleClass().contains("new-tx-dialog")) {
            dialog.getDialogPane().getStyleClass().add("new-tx-dialog");
        }

        dialog.setHeaderText(null);
        dialog.getDialogPane().setHeader(null);
        dialog.getDialogPane().setPadding(Insets.EMPTY);

        // Modal centrado, ancho 420-480px
        dialog.getDialogPane().setMinWidth(420);
        dialog.getDialogPane().setPrefWidth(440);
        dialog.getDialogPane().setMaxWidth(480);

        // Header con botón cerrar personalizado
        BorderPane header = new BorderPane();
        header.getStyleClass().add("new-tx-header");

        ImageView headerIconView = new ImageView();
        try {
            var iconStream = DashboardTransactionsDialog.class.getResourceAsStream("/images/xpendz.png");
            if (iconStream == null) {
                iconStream = DashboardTransactionsDialog.class.getResourceAsStream("/images/logo.png");
            }
            if (iconStream != null) {
                headerIconView.setImage(new Image(iconStream));
            }
        } catch (Exception ignored) {
        }
        headerIconView.setPreserveRatio(true);
        headerIconView.setSmooth(true);
        headerIconView.setFitWidth(18);
        headerIconView.setFitHeight(18);
        headerIconView.getStyleClass().add("new-tx-header-icon");

        Label headerTitle = new Label("Nueva transacción");
        headerTitle.getStyleClass().add("new-tx-title");

        HBox headerTitleBox = new HBox(8, headerIconView, headerTitle);
        headerTitleBox.setAlignment(Pos.CENTER_LEFT);
        headerTitleBox.getStyleClass().add("new-tx-title-box");
        
        Button closeBtn = new Button();
        closeBtn.getStyleClass().add("new-tx-close");
        FontIcon closeIcon = new FontIcon("fas-times");
        closeIcon.getStyleClass().add("new-tx-close-icon");
        closeBtn.setGraphic(closeIcon);
        closeBtn.setOnAction(e -> dialog.setResult(cancelBtnType));
        
        header.setCenter(headerTitleBox);
        header.setRight(closeBtn);
        BorderPane.setAlignment(headerTitleBox, Pos.CENTER);
        BorderPane.setAlignment(closeBtn, Pos.CENTER);
        BorderPane.setMargin(closeBtn, new Insets(0, 8, 0, 0));

        // Toggle tipo de transacción (Gasto/Ingreso)
        ToggleGroup kindToggle = new ToggleGroup();
        ToggleButton expenseBtn = new ToggleButton("Gasto");
        expenseBtn.getStyleClass().add("tx-type-btn");
        expenseBtn.getStyleClass().add("tx-type-expense");
        expenseBtn.setToggleGroup(kindToggle);
        expenseBtn.setSelected(true);

        FontIcon expenseIcon = new FontIcon("fas-shopping-cart");
        expenseIcon.getStyleClass().add("tx-type-icon");
        expenseBtn.setGraphic(expenseIcon);

        ToggleButton incomeBtn = new ToggleButton("Ingreso");
        incomeBtn.getStyleClass().add("tx-type-btn");
        incomeBtn.getStyleClass().add("tx-type-income");
        incomeBtn.setToggleGroup(kindToggle);

        FontIcon incomeIcon = new FontIcon("fas-hand-holding-usd");
        incomeIcon.getStyleClass().add("tx-type-icon");
        incomeBtn.setGraphic(incomeIcon);

        HBox kindBox = new HBox(expenseBtn, incomeBtn);
        kindBox.getStyleClass().add("tx-type-toggle");
        kindBox.setAlignment(Pos.CENTER);
        kindBox.setFillHeight(true);
        HBox.setHgrow(expenseBtn, Priority.ALWAYS);
        HBox.setHgrow(incomeBtn, Priority.ALWAYS);
        expenseBtn.setMaxWidth(Double.MAX_VALUE);
        incomeBtn.setMaxWidth(Double.MAX_VALUE);

        // Monto grande dentro de contenedor
        VBox amountContainer = new VBox();
        amountContainer.getStyleClass().add("amount-container");
        amountContainer.setAlignment(Pos.CENTER_LEFT);

        HBox amountRow = new HBox(8);
        amountRow.setAlignment(Pos.CENTER_LEFT);
        amountRow.getStyleClass().add("amount-row");

        Label currencySymbol = new Label("$");
        currencySymbol.getStyleClass().add("currency-symbol");

        TextField amountField = new TextField("0.00");
        amountField.getStyleClass().add("amount-field");
        amountField.setAlignment(Pos.CENTER_LEFT);
        amountField.setMaxWidth(Double.MAX_VALUE);
        amountField.setPromptText("0.00");
        HBox.setHgrow(amountField, Priority.ALWAYS);

        amountRow.getChildren().addAll(currencySymbol, amountField);
        amountContainer.getChildren().add(amountRow);

        // Categoría (padre)
        Label categoryLabel = new Label("Categoría");
        categoryLabel.getStyleClass().add("field-label");

        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        rootCategory.getStyleClass().add("field-choice");
        rootCategory.setMaxWidth(Double.MAX_VALUE);

        VBox categoryBox = new VBox(4, categoryLabel, rootCategory);
        categoryBox.setFillWidth(true);

        // Subcategoría (dinámica)
        VBox subCategoryBox = new VBox(4);
        Label subCategoryLabel = new Label("Subcategoría");
        subCategoryLabel.getStyleClass().add("field-label");

        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();
        subCategory.getStyleClass().add("field-choice");
        subCategory.setMaxWidth(Double.MAX_VALUE);

        subCategoryBox.getChildren().addAll(subCategoryLabel, subCategory);
        subCategoryBox.setManaged(false);
        subCategoryBox.setVisible(false);

        // Cuenta
        Label accountLabel = new Label("Cuenta");
        accountLabel.getStyleClass().add("field-label");
        
        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        account.getStyleClass().add("field-choice");
        account.setMaxWidth(Double.MAX_VALUE);
        
        VBox accountBox = new VBox(4, accountLabel, account);

        // Descripción (opcional)
        Label noteLabel = new Label("Descripción");
        noteLabel.getStyleClass().add("field-label");
        
        TextField noteField = new TextField();
        noteField.setPromptText("Ej: Compra semanal");
        noteField.getStyleClass().add("field-input");
        noteField.setMaxWidth(Double.MAX_VALUE);
        
        VBox noteBox = new VBox(4, noteLabel, noteField);

        // Fecha
        Label dateLabel = new Label("Fecha");
        dateLabel.getStyleClass().add("field-label");
        
        DatePicker dateField = new DatePicker(LocalDate.now());
        dateField.getStyleClass().add("field-date");
        dateField.setMaxWidth(Double.MAX_VALUE);

        String datePickerCssPath = darkTheme ? "/styles/dark.css" : "/styles/light.css";
        var datePickerCssUrl = UiDialogs.class.getResource(datePickerCssPath);
        String datePickerCss = datePickerCssUrl == null ? null : datePickerCssUrl.toExternalForm();
        dateField.setOnShowing(ev -> {
            if (datePickerCss == null) {
                System.out.println("[DatePickerCss] cssUrl NOT FOUND for path=" + datePickerCssPath);
                return;
            }
            Platform.runLater(() -> {
                int windows = 0;
                int popups = 0;
                int matched = 0;
                int injected = 0;
                for (Window w : Window.getWindows()) {
                    windows++;
                    if (!(w instanceof PopupWindow pw)) {
                        continue;
                    }
                    popups++;
                    try {
                        var sc = pw.getScene();
                        if (sc == null || sc.getRoot() == null) {
                            continue;
                        }
                        var rootNode = sc.getRoot();
                        boolean isDatePickerPopupRoot = rootNode.getStyleClass() != null
                            && rootNode.getStyleClass().contains("date-picker-popup");
                        boolean hasDatePickerPopupInside = rootNode.lookup(".date-picker-popup") != null;
                        if (!isDatePickerPopupRoot && !hasDatePickerPopupInside) {
                            continue;
                        }
                        matched++;
                        if (!sc.getStylesheets().contains(datePickerCss)) {
                            sc.getStylesheets().add(datePickerCss);
                            injected++;
                            System.out.println("[DatePickerCss] injected css=" + datePickerCss);
                        }

                        try {
                            if (!rootNode.getStyleClass().contains("xpendz-date-popup")) {
                                rootNode.getStyleClass().add("xpendz-date-popup");
                            }
                            System.out.println("[DatePickerCss] matched popup window. rootStyleClasses=" + rootNode.getStyleClass());
                            rootNode.applyCss();
                            if (rootNode instanceof Parent p) {
                                p.layout();
                            }
                            var dpPopup = rootNode.lookup(".date-picker-popup");
                            if (dpPopup != null) {
                                if (!dpPopup.getStyleClass().contains("xpendz-date-popup")) {
                                    dpPopup.getStyleClass().add("xpendz-date-popup");
                                }
                                dpPopup.applyCss();
                                if (dpPopup instanceof Parent p2) {
                                    p2.layout();
                                }
                            }
                        } catch (Exception ignored2) {
                        }
                    } catch (Exception ignored) {
                    }
                }
                System.out.println(
                    "[DatePickerCss] windows=" + windows
                        + " popups=" + popups
                        + " matched=" + matched
                        + " injected=" + injected
                        + " cssPath=" + datePickerCssPath
                );
            });
        });
        
        VBox dateBox = new VBox(4, dateLabel, dateField);

        // Error label
        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        // Balance label (solo para gastos)
        Label balanceLabel = new Label();
        balanceLabel.getStyleClass().add("balance-label");
        balanceLabel.setVisible(false);
        balanceLabel.setManaged(false);
        VBox balanceBox = new VBox(balanceLabel);

        // Contenido principal
        VBox content = new VBox(12);
        content.getStyleClass().add("new-tx-content");
        content.setPadding(new Insets(12, 18, 12, 18));
        content.getChildren().addAll(
            kindBox,
            amountContainer,
            categoryBox,
            subCategoryBox,
            accountBox,
            balanceBox,
            noteBox,
            dateBox,
            error
        );

        // Estructura final
        VBox root = new VBox(header, content);
        root.getStyleClass().add("new-tx-root");
        root.getStyleClass().add("tx-kind-expense");
        root.setFillWidth(true);
        root.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(root);

        // Lógica de datos
        java.util.concurrent.atomic.AtomicReference<String> currentKind = new java.util.concurrent.atomic.AtomicReference<>("EXPENSE");
        
        // Balance refresh
        Runnable refreshBalance = () -> {
            AccountRepository.Account a = account.getValue();
            if (a == null || !"EXPENSE".equals(currentKind.get())) {
                balanceLabel.setText("");
                return;
            }
            try {
                long bal = accountRepo.computeBalanceCents(userUid, a.id());
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };
        
        kindToggle.selectedToggleProperty().addListener((obs, oldV, newV) -> {
            if (newV == expenseBtn) {
                currentKind.set("EXPENSE");
                expenseBtn.getStyleClass().add("tx-type-active");
                incomeBtn.getStyleClass().remove("tx-type-active");
                balanceBox.setVisible(true);
                balanceBox.setManaged(true);

                root.getStyleClass().remove("tx-kind-income");
                if (!root.getStyleClass().contains("tx-kind-expense")) {
                    root.getStyleClass().add("tx-kind-expense");
                }
            } else if (newV == incomeBtn) {
                currentKind.set("INCOME");
                incomeBtn.getStyleClass().add("tx-type-active");
                expenseBtn.getStyleClass().remove("tx-type-active");
                balanceBox.setVisible(false);
                balanceBox.setManaged(false);

                root.getStyleClass().remove("tx-kind-expense");
                if (!root.getStyleClass().contains("tx-kind-income")) {
                    root.getStyleClass().add("tx-kind-income");
                }
            }
            refreshBalance.run();
        });
        expenseBtn.getStyleClass().add("tx-type-active");

        balanceBox.setVisible(true);
        balanceBox.setManaged(true);
        refreshBalance.run();

        // Cargar cuentas
        try {
            account.getItems().add(null);
            List<AccountRepository.Account> accounts = new ArrayList<>(accountRepo.list(userUid));
            accounts.sort(
                Comparator
                    .comparing((AccountRepository.Account a) -> accountTypeLabel(a == null ? null : a.type()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(a -> a == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER)
            );
            account.getItems().addAll(accounts);
            if (!account.getItems().isEmpty()) {
                account.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }

        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : formatAccountLabel(object);
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        List<CategoryRepository.Category> allRoots = new ArrayList<>();
        try {
            allRoots.addAll(categoryRepo.listRoots(userUid));
        } catch (Exception ignored) {
        }

        Runnable refreshRootCatsByKind = () -> {
            String k = currentKind.get();
            rootCategory.getItems().clear();
            rootCategory.getItems().add(null);
            for (CategoryRepository.Category r : allRoots) {
                String derived = kindForRootCategory(r);
                if (derived == null || derived.isBlank()) {
                    continue;
                }
                if ("BOTH".equalsIgnoreCase(derived)) {
                    rootCategory.getItems().add(r);
                    continue;
                }
                if (k != null && derived.equalsIgnoreCase(k)) {
                    rootCategory.getItems().add(r);
                }
            }
            if (!rootCategory.getItems().isEmpty()) {
                rootCategory.getSelectionModel().selectFirst();
            }
        };

        refreshRootCatsByKind.run();

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

        // Lógica de subcategoría dinámica
        Runnable refreshSubcats = () -> {
            CategoryRepository.Category selectedCategory = rootCategory.getValue();
            subCategory.getItems().clear();
            
            if (selectedCategory == null) {
                subCategoryBox.setVisible(false);
                subCategoryBox.setManaged(false);
                return;
            }
            
            try {
                List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, selectedCategory.id());
                if (children.isEmpty()) {
                    subCategoryBox.setVisible(false);
                    subCategoryBox.setManaged(false);
                } else {
                    subCategory.getItems().add(null);
                    subCategory.getItems().addAll(children);
                    subCategory.getSelectionModel().selectFirst();
                    subCategoryBox.setVisible(true);
                    subCategoryBox.setManaged(true);
                }
            } catch (Exception ignored) {
                subCategoryBox.setVisible(false);
                subCategoryBox.setManaged(false);
            }
        };

        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        rootCategory.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> refreshSubcats.run());
        refreshSubcats.run();

        kindToggle.selectedToggleProperty().addListener((obs, oldV, newV) -> {
            refreshRootCatsByKind.run();
            refreshSubcats.run();
        });

        account.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> refreshBalance.run());

        // Foco automático en monto al abrir
        dialog.setOnShown(e -> Platform.runLater(() -> amountField.requestFocus()));

        // Botones
        javafx.scene.Node okNode = dialog.getDialogPane().lookupButton(saveBtnType);
        javafx.scene.Node cancelNode = dialog.getDialogPane().lookupButton(cancelBtnType);
        if (cancelNode instanceof Button cancelBtn) {
            cancelBtn.getStyleClass().add("btn-secondary");
        }
        if (okNode instanceof Button okBtn) {
            okBtn.getStyleClass().add("btn-primary");
            okBtn.getStyleClass().add("tx-save-btn");
            okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                error.setText("");
                error.setVisible(false);
                error.setManaged(false);

                if (dateField.getValue() == null || account.getValue() == null || rootCategory.getValue() == null) {
                    error.setText("Por favor completa los campos obligatorios.");
                    error.setVisible(true);
                    error.setManaged(true);
                    ev.consume();
                    return;
                }

                String raw = amountField.getText() == null ? "" : amountField.getText().trim();
                if (raw.isBlank()) {
                    error.setText("Por favor ingresa el monto.");
                    error.setVisible(true);
                    error.setManaged(true);
                    ev.consume();
                    return;
                }

                long cents;
                try {
                    BigDecimal v = DashboardFormatters.parseAmount(raw);
                    cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
                } catch (Exception ignored) {
                    error.setText("Monto inválido.");
                    error.setVisible(true);
                    error.setManaged(true);
                    ev.consume();
                    return;
                }
                if (cents <= 0) {
                    error.setText("El monto debe ser mayor a 0.");
                    error.setVisible(true);
                    error.setManaged(true);
                    ev.consume();
                    return;
                }

                if ("EXPENSE".equalsIgnoreCase(currentKind.get())) {
                    AccountRepository.Account a = account.getValue();
                    if (a != null) {
                        if ("CREDIT".equalsIgnoreCase(AccountRepository.normalizeType(a.type()))) {
                            // No validar saldo para tarjetas de crédito
                        } else {
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
                }
            });
        }

        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != saveBtnType) {
            return Optional.empty();
        }

        if (dateField.getValue() == null || account.getValue() == null || rootCategory.getValue() == null) {
            return Optional.empty();
        }

        String raw = amountField.getText() == null ? "" : amountField.getText().trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = DashboardFormatters.parseAmount(raw);
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }

        if (cents <= 0) {
            return Optional.empty();
        }

        long occurredAt = dateField.getValue().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        CategoryRepository.Category chosen = subCategory.getValue() != null ? subCategory.getValue() : rootCategory.getValue();
        return Optional.of(new NewTransaction(
            account.getValue().id(),
            chosen.id(),
            currentKind.get(),
            cents,
            occurredAt,
            noteField.getText() == null ? null : noteField.getText().trim()
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
        kind.getSelectionModel().clearSelection();
        kind.setDisable(true);
        kind.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String object) {
                if (object == null) {
                    return "";
                }
                if ("INCOME".equalsIgnoreCase(object)) {
                    return "Ingreso";
                }
                if ("EXPENSE".equalsIgnoreCase(object)) {
                    return "Egreso";
                }
                return object;
            }

            @Override
            public String fromString(String string) {
                if (string == null) {
                    return null;
                }
                String s = string.trim();
                if ("Ingreso".equalsIgnoreCase(s)) {
                    return "INCOME";
                }
                if ("Egreso".equalsIgnoreCase(s)) {
                    return "EXPENSE";
                }
                return null;
            }
        });

        Label balanceLabel = new Label();
        balanceLabel.getStyleClass().add("account-name");

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        java.util.concurrent.atomic.AtomicReference<Runnable> refreshKindFromRootRef = new java.util.concurrent.atomic.AtomicReference<>(null);

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
        rootCategory.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            refreshSubcats.run();
            Runnable r = refreshKindFromRootRef.get();
            if (r != null) {
                r.run();
            }
        });
        refreshSubcats.run();

        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : formatAccountLabel(object);
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

        javafx.scene.control.TextField amount = new javafx.scene.control.TextField(BigDecimal.valueOf(existing.amountCents(), 2).toPlainString());
        amount.setPrefWidth(340);

        javafx.scene.control.TextField note = new javafx.scene.control.TextField(existing.note() == null ? "" : existing.note());
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
            String derived = kindForRootCategory(rootCategory.getValue());
            String finalKind = derived != null ? derived : kind.getValue();
            if (!"EXPENSE".equalsIgnoreCase(finalKind)) {
                balanceLabel.setText("");
                return;
            }
            try {
                long bal = accountRepo.computeBalanceCents(userUid, a.id());
                if (existing.accountId() != null && existing.accountId().equals(a.id())) {
                    long effect = "EXPENSE".equalsIgnoreCase(existing.kind()) ? -existing.amountCents() : existing.amountCents();
                    bal -= effect;
                }
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };

        Runnable refreshKindFromRoot = () -> {
            String derived = kindForRootCategory(rootCategory.getValue());
            if (derived == null) {
                kind.setDisable(false);
                if (kind.getValue() == null) {
                    kind.getSelectionModel().clearSelection();
                }
            } else {
                kind.getSelectionModel().select(derived);
                kind.setDisable(true);
            }
            refreshBalance.run();
        };
        refreshKindFromRootRef.set(refreshKindFromRoot);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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
        refreshKindFromRoot.run();

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
                    BigDecimal v = DashboardFormatters.parseAmount(raw);
                    cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
                } catch (Exception ignored) {
                    ev.consume();
                    return;
                }
                if (cents < 0) {
                    ev.consume();
                    return;
                }

                String derivedKind = kindForRootCategory(rootCategory.getValue());
                String finalKind = derivedKind != null ? derivedKind : kind.getValue();
                if (finalKind == null || finalKind.isBlank()) {
                    ev.consume();
                    return;
                }

                if ("EXPENSE".equalsIgnoreCase(finalKind)) {
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

        String derivedKind = kindForRootCategory(rootCategory.getValue());
        String finalKind = derivedKind != null ? derivedKind : kind.getValue();
        if (finalKind == null || finalKind.isBlank()) {
            return Optional.empty();
        }

        String raw = amount.getText() == null ? "" : amount.getText().trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = DashboardFormatters.parseAmount(raw);
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
            finalKind,
            cents,
            occurredAt,
            note.getText() == null ? null : note.getText().trim()
        ));
    }
}
