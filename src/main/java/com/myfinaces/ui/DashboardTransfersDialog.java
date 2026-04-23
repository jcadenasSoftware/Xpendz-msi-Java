package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.scene.control.TextField;
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

public final class DashboardTransfersDialog {

    private DashboardTransfersDialog() {
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

    public static void showTransfersDialog(
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
            List<AccountRepository.Account> accounts = new ArrayList<>(accountRepo.list(userUid));
            accounts.sort(
                Comparator
                    .comparing((AccountRepository.Account a) -> accountTypeLabel(a == null ? null : a.type()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(a -> a == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER)
            );
            accountFilter.getItems().addAll(accounts);
            accountFilter.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        accountFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : formatAccountLabel(object);
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
                Label right = new Label(DashboardFormatters.formatMoney(tr.amountCents(), cur));
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
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(bal, a.currency()));
            } catch (Exception ignored) {
                balanceLabel.setText("Saldo disponible: --");
            }
        };

        try {
            List<AccountRepository.Account> accounts = new ArrayList<>(accountRepo.list(userUid));
            accounts.sort(
                Comparator
                    .comparing((AccountRepository.Account a) -> accountTypeLabel(a == null ? null : a.type()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(a -> a == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER)
            );
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
                return object == null ? "" : formatAccountLabel(object);
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });
        to.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : formatAccountLabel(object);
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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
            BigDecimal v = DashboardFormatters.parseAmount(raw);
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents < 0) {
            return Optional.empty();
        }

        try {
            if (!"CREDIT".equalsIgnoreCase(AccountRepository.normalizeType(from.getValue().type()))) {
                long available = accountRepo.computeBalanceCents(userUid, from.getValue().id());
                if (cents > available) {
                    error.setText("El monto supera el saldo disponible.");
                    error.setVisible(true);
                    error.setManaged(true);
                    return Optional.empty();
                }
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
            List<AccountRepository.Account> accounts = new ArrayList<>(accountRepo.list(userUid));
            accounts.sort(
                Comparator
                    .comparing((AccountRepository.Account a) -> accountTypeLabel(a == null ? null : a.type()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(a -> a == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER)
            );
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
                return object == null ? "" : formatAccountLabel(object);
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });
        to.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "" : formatAccountLabel(object);
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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
            BigDecimal v = DashboardFormatters.parseAmount(raw);
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
}
