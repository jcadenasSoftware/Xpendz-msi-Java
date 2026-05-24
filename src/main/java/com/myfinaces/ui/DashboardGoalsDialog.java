package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;

import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class DashboardGoalsDialog {

    private DashboardGoalsDialog() {
    }

    public record NewGoal(
        String name,
        String currency,
        long targetCents,
        long targetDateEpochSec
    ) {
    }

    public record GoalTransfer(
        String otherAccountId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    public static void showGoalsDialog(
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

    public static Optional<NewGoal> showCreateGoalDialog(boolean darkTheme) {
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

        UiDialogs.restrictToDecimalAmount(target);

        DatePicker targetDate = new DatePicker(LocalDate.now().plusMonths(1));

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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

    public static Optional<GoalTransfer> showGoalDepositDialog(
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

        UiDialogs.restrictToDecimalAmount(amount);
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

        Runnable refreshBalance = () -> {
            AccountRepository.Account a = from.getValue();
            if (a == null) {
                balance.setText("");
                return;
            }
            long cents = safeComputeBalanceCents(accountRepo, userUid, a.id());
            balance.setText("Saldo disponible: " + formatMoney(cents, a.currency()));
        };
        from.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshBalance.run());
        refreshBalance.run();

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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

    public static Optional<GoalTransfer> showGoalWithdrawDialog(
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

        UiDialogs.restrictToDecimalAmount(amount);
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

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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

    private static String formatMoney(long cents, String currencyCode) {
        return DashboardFormatters.formatMoney(cents, currencyCode);
    }

    private static BigDecimal parseAmount(String raw) {
        return DashboardFormatters.parseAmount(raw);
    }
}
