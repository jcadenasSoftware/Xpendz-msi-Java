package com.myfinaces.ui;

import com.myfinaces.config.AppConfig;
import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
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
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DashboardTransactionsDialog {

    private DashboardTransactionsDialog() {
    }

    private static String kindForRootCategory(CategoryRepository.Category root) {
        if (root == null) {
            return null;
        }
        if (root.kind() != null && !root.kind().isBlank()) {
            return root.kind();
        }
        if (root.name() == null) {
            return null;
        }
        if ("INGRESOS".equalsIgnoreCase(root.name())) {
            return "INCOME";
        }
        if ("GASTOS".equalsIgnoreCase(root.name())) {
            return "EXPENSE";
        }
        return null;
    }

    private static String accountTypeLabel(String type) {
        String t = type == null ? "" : type.trim();
        if ("BANK".equalsIgnoreCase(t)) {
            return "Banco";
        }
        if ("CASH".equalsIgnoreCase(t)) {
            return "Efectivo";
        }
        if ("SAVINGS".equalsIgnoreCase(t)) {
            return "Ahorro";
        }
        if ("CREDIT".equalsIgnoreCase(t)) {
            return "Crédito";
        }
        if ("INVESTMENT".equalsIgnoreCase(t)) {
            return "Inversión";
        }
        if ("OTHER".equalsIgnoreCase(t)) {
            return "Otra";
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
        pAccount.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox pCat = new HBox(8, fCat, txRootCategoryFilter);
        pCat.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox pSub = new HBox(8, fSub, txSubCategoryFilter);
        pSub.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox pFrom = new HBox(8, fFrom, txFromDate);
        pFrom.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox pTo = new HBox(8, fTo, txToDate);
        pTo.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

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

                Label right = new Label(DashboardFormatters.formatMoney(signed, currency));
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
                actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

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
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };

        Runnable refreshKindFromRoot = () -> {
            String derived = kindForRootCategory(rootCategory.getValue());
            if (derived == null) {
                kind.getSelectionModel().clearSelection();
            } else {
                kind.getSelectionModel().select(derived);
            }
            refreshBalance.run();
        };
        refreshKindFromRootRef.set(refreshKindFromRoot);

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
        rootCategory.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            refreshSubcats.run();
            Runnable r = refreshKindFromRootRef.get();
            if (r != null) {
                r.run();
            }
        });
        refreshSubcats.run();
        refreshKindFromRoot.run();

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

        javafx.scene.control.TextField amount = new javafx.scene.control.TextField();
        amount.setPromptText("Ej: 10000.00");
        amount.setPrefWidth(340);

        javafx.scene.control.TextField note = new javafx.scene.control.TextField();
        note.setPromptText("Nota (opcional)");
        note.setPrefWidth(340);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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

                String derivedKind = kindForRootCategory(rootCategory.getValue());
                if (derivedKind == null) {
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

                if ("EXPENSE".equalsIgnoreCase(derivedKind)) {
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

        String derivedKind = kindForRootCategory(rootCategory.getValue());
        if (derivedKind == null) {
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
            derivedKind,
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
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };

        Runnable refreshKindFromRoot = () -> {
            String derived = kindForRootCategory(rootCategory.getValue());
            if (derived == null) {
                kind.getSelectionModel().clearSelection();
            } else {
                kind.getSelectionModel().select(derived);
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
                if (derivedKind == null) {
                    ev.consume();
                    return;
                }

                if ("EXPENSE".equalsIgnoreCase(derivedKind)) {
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
        if (derivedKind == null) {
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
            derivedKind,
            cents,
            occurredAt,
            note.getText() == null ? null : note.getText().trim()
        ));
    }
}
