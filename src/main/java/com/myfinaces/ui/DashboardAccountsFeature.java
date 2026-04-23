package com.myfinaces.ui;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DashboardAccountsFeature {

    private DashboardAccountsFeature() {
    }

    public enum EditAccountAction {
        SAVE,
        VIEW_SUMMARY,
        DELETE
    }

    public record EditAccountResult(EditAccountAction action, String newName, String newType) {
    }

    public record NewAccount(String name, String type, String currency) {
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

    public static Optional<NewAccount> showCreateAccountDialog(boolean darkTheme) {
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
        type.getItems().addAll("BANK", "CREDIT", "CASH", "SAVINGS", "VIRTUAL_WALLET", "DIGITAL_ACCOUNT");
        type.setConverter(new StringConverter<>() {
            @Override
            public String toString(String object) {
                return accountTypeLabel(object);
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
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

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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

        String t = AccountRepository.normalizeType(type.getValue());
        String cur = currency.getValue() == null ? "" : currency.getValue().trim();
        if (cur.isBlank()) {
            cur = "COP";
        }

        return Optional.of(new NewAccount(n, t, cur));
    }

    public static Optional<EditAccountResult> showEditAccountDialog(AccountRepository.Account existing, boolean darkTheme) {
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
        type.getItems().addAll("BANK", "CASH", "SAVINGS", "VIRTUAL_WALLET", "DIGITAL_ACCOUNT");
        type.setConverter(new StringConverter<>() {
            @Override
            public String toString(String object) {
                return accountTypeLabel(object);
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        String normalized = AccountRepository.normalizeType(existing == null ? null : existing.type());
        if (type.getItems().contains(normalized)) {
            type.getSelectionModel().select(normalized);
        } else {
            type.getSelectionModel().selectFirst();
        }
        type.setDisable(false);

        TextField currency = new TextField(existing == null ? "COP" : existing.currency());
        currency.setPrefWidth(200);
        currency.setDisable(true);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
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
            return Optional.of(new EditAccountResult(EditAccountAction.DELETE, null, null));
        }
        if (result.get() == summaryBtn) {
            return Optional.of(new EditAccountResult(EditAccountAction.VIEW_SUMMARY, null, null));
        }
        if (result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        String n = name.getText() == null ? "" : name.getText().trim();
        if (n.isBlank()) {
            return Optional.empty();
        }
        String t = AccountRepository.normalizeType(type.getValue());
        return Optional.of(new EditAccountResult(EditAccountAction.SAVE, n, t));
    }

    public static void showAccountSummaryDialog(
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

    private static String formatMoney(long cents, String currencyCode) {
        return DashboardFormatters.formatMoney(cents, currencyCode);
    }
}
