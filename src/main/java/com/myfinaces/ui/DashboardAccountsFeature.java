package com.myfinaces.ui;

import com.myfinaces.config.AccountStyles;
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
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;

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

    public record EditAccountResult(EditAccountAction action, String newName, String newType, String newColor) {
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

    public static Optional<EditAccountResult> showEditAccountDialog(
        AccountRepository.Account existing,
        boolean darkTheme,
        String userUid,
        AccountRepository accountRepo,
        TransactionRepository txRepo,
        TransferRepository transferRepo,
        java.util.function.Consumer<String> onViewAllMovements
    ) {
        Stage modal = new Stage();
        modal.initModality(Modality.APPLICATION_MODAL);
        try {
            for (Window w : Window.getWindows()) {
                if (w != null && w.isShowing()) {
                    modal.initOwner(w);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        modal.setTitle("Editar cuenta");
        modal.setResizable(true);
        modal.setMinWidth(860);
        modal.setWidth(920);
        modal.setMinHeight(560);

        Button bDelete = new Button("Eliminar cuenta");
        bDelete.getStyleClass().add("btn-danger");
        FontIcon di = new FontIcon("fas-trash");
        di.setIconSize(13);
        bDelete.setGraphic(di);
        bDelete.setMinWidth(140);
        bDelete.setFocusTraversable(true);

        Button bCancel = new Button("Cancelar");
        bCancel.getStyleClass().add("btn-secondary");
        FontIcon ci = new FontIcon("fas-times");
        ci.setIconSize(13);
        bCancel.setGraphic(ci);
        bCancel.setMinWidth(120);
        bCancel.setFocusTraversable(true);

        Button bSave = new Button("Guardar cambios");
        bSave.getStyleClass().add("btn-primary");
        FontIcon si = new FontIcon("fas-save");
        si.setIconSize(13);
        bSave.setGraphic(si);
        bSave.setMinWidth(160);
        bSave.setFocusTraversable(true);
        bSave.setDefaultButton(true);

        // ── Header title ─────────────────────────────────────────────
        Label title = new Label("Editar cuenta");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Administra tu cuenta y revisa actividad relacionada.");
        subtitle.getStyleClass().add("text-secondary");
        VBox titleBox = new VBox(2, title, subtitle);

        // ════════════════════════════════════════════════
        //  LEFT COLUMN
        // ════════════════════════════════════════════════

        // ── Identity card preview (AccountCard) ──────────────────────
        HBox identityCard = AccountCard.buildIdentity(existing, AccountCard.Variant.DETAIL, () -> darkTheme);
        identityCard.setMaxWidth(Double.MAX_VALUE);
        identityCard.getStyleClass().add("edit-account-identity-card");

        // ── Name field ───────────────────────────────────────────────
        Label lName = new Label("Nombre de la cuenta");
        lName.getStyleClass().add("field-label");
        TextField nameField = new TextField(existing == null ? "" : existing.name());
        nameField.getStyleClass().add("field-input");
        nameField.setMaxWidth(Double.MAX_VALUE);
        VBox nameSection = new VBox(6, lName, nameField);

        // ── Account type selector ────────────────────────────────────
        Label lType = new Label("Tipo de cuenta");
        lType.getStyleClass().add("field-label");
        ChoiceBox<String> typeBox = new ChoiceBox<>();
        typeBox.getItems().addAll("BANK", "CREDIT", "CASH", "SAVINGS", "VIRTUAL_WALLET", "DIGITAL_ACCOUNT");
        typeBox.setConverter(new StringConverter<>() {
            @Override public String toString(String s) { return accountTypeLabel(s); }
            @Override public String fromString(String s) { return s; }
        });
        typeBox.getStyleClass().add("field-choice");
        typeBox.setMaxWidth(Double.MAX_VALUE);
        String normalizedType = AccountRepository.normalizeType(existing == null ? null : existing.type());
        if (typeBox.getItems().contains(normalizedType)) {
            typeBox.getSelectionModel().select(normalizedType);
        } else {
            typeBox.getSelectionModel().selectFirst();
        }
        VBox typeSection = new VBox(6, lType, typeBox);

        // ── Currency (read-only) ─────────────────────────────────────
        Label lCurrency = new Label("Moneda");
        lCurrency.getStyleClass().add("field-label");
        TextField currencyField = new TextField(existing == null ? "COP" : existing.currency());
        currencyField.getStyleClass().add("field-input");
        currencyField.setMaxWidth(Double.MAX_VALUE);
        currencyField.setDisable(true);
        FontIcon moneyIcon = new FontIcon("fas-coins");
        moneyIcon.setIconSize(14);
        StackPane currencyWrap = new StackPane(currencyField, moneyIcon);
        StackPane.setAlignment(moneyIcon, Pos.CENTER_RIGHT);
        StackPane.setMargin(moneyIcon, new Insets(0, 10, 0, 0));
        VBox currencySection = new VBox(6, lCurrency, currencyWrap);

        // ── Warning info box ─────────────────────────────────────────
        FontIcon infoIcon = new FontIcon("fas-info-circle");
        infoIcon.setIconSize(14);
        Label infoText = new Label("Los cambios se aplicarán a todas las transacciones y transferencias vinculadas a esta cuenta.");
        infoText.setWrapText(true);
        infoText.getStyleClass().add("text-secondary");
        HBox infoBox = new HBox(8, infoIcon, infoText);
        infoBox.getStyleClass().add("edit-account-info-box");
        infoBox.setAlignment(Pos.TOP_LEFT);
        infoBox.setMinHeight(Region.USE_PREF_SIZE);
        infoBox.setPrefHeight(Region.USE_COMPUTED_SIZE);
        HBox.setHgrow(infoText, Priority.ALWAYS);

        // ── Color picker ─────────────────────────────────────────────
        Label lColor = new Label("Color de la cuenta");
        lColor.getStyleClass().add("field-label");
        String initialColor = (existing != null && existing.color() != null && !existing.color().isBlank())
            ? existing.color()
            : AccountStyles.resolveColor(existing);
        AccountColorPicker colorPicker = new AccountColorPicker.Builder()
            .value(initialColor)
            .onChange(hex -> {}) 
            .build();
        VBox colorSection = new VBox(6, lColor, colorPicker.getNode());

        VBox leftCol = new VBox(14,
            titleBox,
            identityCard,
            nameSection,
            typeSection,
            currencySection,
            infoBox,
            colorSection
        );
        leftCol.setPrefWidth(400);
        leftCol.setMaxWidth(400);
        leftCol.setMinWidth(360);

        // ════════════════════════════════════════════════
        //  RIGHT COLUMN
        // ════════════════════════════════════════════════

        // ── Balance card ─────────────────────────────────────────────
        long balanceCents = 0L;
        try {
            if (userUid != null && existing != null) {
                balanceCents = accountRepo.computeBalanceCents(userUid, existing.id());
            }
        } catch (Exception ignored) {}

        String accentHex = AccountStyles.resolveColor(existing);
        Label balanceTitle = new Label("Saldo actual");
        balanceTitle.getStyleClass().addAll("text-secondary");
        balanceTitle.setStyle("-fx-text-fill: rgba(255,255,255,0.8);");
        FontIcon walletIcon = new FontIcon("fas-wallet");
        walletIcon.setIconSize(18);
        try { walletIcon.setIconColor(Color.WHITE); } catch (Exception ignored) {}
        HBox balanceTitleRow = new HBox(8, walletIcon, balanceTitle);
        balanceTitleRow.setAlignment(Pos.CENTER_LEFT);

        Label balanceAmount = new Label(DashboardFormatters.formatMoney(balanceCents, existing == null ? "COP" : existing.currency()));
        balanceAmount.setStyle("-fx-font-size: 28px; -fx-font-weight: 900; -fx-text-fill: white;");

        Rectangle cardDeco = new Rectangle(120, 80);
        cardDeco.setArcWidth(16); cardDeco.setArcHeight(16);
        try { cardDeco.setFill(Color.web("rgba(255,255,255,0.12)")); } catch (Exception ignored) {}
        StackPane decoPane = new StackPane(cardDeco);
        decoPane.setAlignment(Pos.CENTER_RIGHT);
        Region decoSpacer = new Region();
        HBox.setHgrow(decoSpacer, Priority.ALWAYS);

        VBox balanceTextCol = new VBox(6, balanceTitleRow, balanceAmount);
        HBox balanceCardRow = new HBox(decoSpacer, decoPane);
        VBox balanceCard = new VBox(10, balanceTextCol, balanceCardRow);
        balanceCard.getStyleClass().add("edit-account-balance-card");
        balanceCard.setPadding(new Insets(18));
        try {
            balanceCard.setStyle("-fx-background-color: linear-gradient(to bottom right, " + accentHex + ", derive(" + accentHex + ", -20%)); -fx-background-radius: 14;");
        } catch (Exception ignored) {}

        // ── Stats row ─────────────────────────────────────────────────
        long txCount = 0L, trCount = 0L;
        long lastMovEpoch = 0L;
        List<TransactionRepository.TransactionRow> txListForStats = new java.util.ArrayList<>();
        try {
            if (userUid != null && existing != null) {
                txListForStats = txRepo.listFiltered(userUid, existing.id(), (List<String>) null, null, null, 5000);
                txCount = txListForStats.size();
                lastMovEpoch = txListForStats.stream().mapToLong(TransactionRepository.TransactionRow::occurredAtEpochSec).max().orElse(0L);
            }
        } catch (Exception e) { e.printStackTrace(); }
        try {
            if (userUid != null && existing != null) {
                List<TransferRepository.TransferRow> trList = transferRepo.listFiltered(userUid, existing.id(), null, null, 5000);
                trCount = trList.size();
                long lastTr = trList.stream().mapToLong(TransferRepository.TransferRow::occurredAtEpochSec).max().orElse(0L);
                if (lastTr > lastMovEpoch) lastMovEpoch = lastTr;
            }
        } catch (Exception e) { e.printStackTrace(); }

        String lastMovStr = lastMovEpoch > 0
            ? java.time.Instant.ofEpochSecond(lastMovEpoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", new java.util.Locale("es")))
            : "—";

        VBox statTx = buildStatBox(String.valueOf(txCount), "Transacciones", "fas-receipt", "#2563EB");
        VBox statTr = buildStatBox(String.valueOf(trCount), "Transferencias", "fas-exchange-alt", "#D97706");
        VBox statLast = buildStatBox(lastMovStr, "Último movimiento", "fas-calendar-alt", "#7C3AED");
        HBox statsRow = new HBox(10, statTx, statTr, statLast);
        HBox.setHgrow(statTx, Priority.ALWAYS);
        HBox.setHgrow(statTr, Priority.ALWAYS);
        HBox.setHgrow(statLast, Priority.ALWAYS);
        statsRow.getStyleClass().add("edit-account-stats-row");

        // ── Recent movements ─────────────────────────────────────────
        Label movLabel = new Label("Últimos movimientos");
        movLabel.getStyleClass().add("account-name");
        movLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 700;");
        Region movHeaderSpacer = new Region();
        HBox.setHgrow(movHeaderSpacer, Priority.ALWAYS);
        Button viewAllBtn = new Button("Ver todos los movimientos ›");
        viewAllBtn.getStyleClass().addAll("btn-secondary");
        viewAllBtn.setStyle("-fx-font-size: 12px; -fx-padding: 5 12;");
        HBox movHeader = new HBox(movLabel, movHeaderSpacer, viewAllBtn);
        movHeader.setAlignment(Pos.CENTER_LEFT);

        VBox movBox = new VBox(6);
        try {
            if (userUid != null && existing != null) {
                Map<String, String> accountCurrency = new HashMap<>();
                for (AccountRepository.Account ac : accountRepo.list(userUid)) {
                    accountCurrency.put(ac.id(), ac.currency());
                }

                class RecentItem {
                    final boolean isTransfer;
                    final TransactionRepository.TransactionRow tx;
                    final TransferRepository.TransferRow tr;
                    final long epoch;
                    RecentItem(TransactionRepository.TransactionRow tx) {
                        this.isTransfer = false;
                        this.tx = tx;
                        this.tr = null;
                        this.epoch = tx == null ? 0L : tx.occurredAtEpochSec();
                    }
                    RecentItem(TransferRepository.TransferRow tr) {
                        this.isTransfer = true;
                        this.tx = null;
                        this.tr = tr;
                        this.epoch = tr == null ? 0L : tr.occurredAtEpochSec();
                    }
                }

                List<RecentItem> recentAll = new java.util.ArrayList<>();
                for (TransactionRepository.TransactionRow t : txListForStats) {
                    recentAll.add(new RecentItem(t));
                }
                try {
                    List<TransferRepository.TransferRow> trs = transferRepo.listFiltered(userUid, existing.id(), null, null, 5000);
                    for (TransferRepository.TransferRow tr : trs) {
                        recentAll.add(new RecentItem(tr));
                    }
                } catch (Exception ignored) {
                }

                recentAll.sort((a, b) -> Long.compare(b.epoch, a.epoch));
                if (recentAll.size() > 3) {
                    recentAll = recentAll.subList(0, 3);
                }

                if (recentAll.isEmpty()) {
                    Label empty = new Label("Sin movimientos recientes.");
                    empty.getStyleClass().add("text-secondary");
                    movBox.getChildren().add(empty);
                } else {
                    for (RecentItem it : recentAll) {
                        long epoch = it.epoch;
                        java.time.LocalDate d = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                        String dateStr = d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", new java.util.Locale("es")));

                        String titleText;
                        String noteText;
                        String iconLiteral;
                        String cur;
                        long signed;

                        if (!it.isTransfer && it.tx != null) {
                            TransactionRepository.TransactionRow t = it.tx;
                            signed = "EXPENSE".equalsIgnoreCase(t.kind()) ? -t.amountCents() : t.amountCents();
                            cur = accountCurrency.getOrDefault(t.accountId(), existing.currency());
                            String catNameDisplay = (t.categoryName() != null && !t.categoryName().isBlank()) ? t.categoryName() : "Movimiento";
                            titleText = DashboardFormatters.formatTransactionDisplayText(t.kind(), t.note(), catNameDisplay);
                            String rawNote = (t.note() != null && !t.note().isBlank()) ? t.note().trim() : "";
                            noteText = rawNote.isEmpty() ? catNameDisplay : rawNote;
                            iconLiteral = "fas-hand-holding-usd";
                        } else if (it.tr != null) {
                            TransferRepository.TransferRow tr = it.tr;
                            boolean outgoing = existing.id() != null && existing.id().equals(tr.fromAccountId());
                            String other = outgoing ? tr.toAccountName() : tr.fromAccountName();
                            titleText = outgoing ? ("Transferencia a " + other) : ("Transferencia de " + other);
                            String rawNote = tr.note() == null ? "" : tr.note().trim();
                            noteText = rawNote.isBlank() ? (outgoing ? ("→ " + other) : ("← " + other)) : rawNote;
                            signed = outgoing ? -tr.amountCents() : tr.amountCents();
                            cur = accountCurrency.getOrDefault(outgoing ? tr.fromAccountId() : tr.toAccountId(), existing.currency());
                            iconLiteral = "fas-exchange-alt";
                        } else {
                            continue;
                        }

                        Label movCat = new Label(titleText);
                        movCat.getStyleClass().add("account-name");
                        movCat.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");

                        Label movNote = new Label(noteText);
                        movNote.getStyleClass().add("text-secondary");
                        movNote.setStyle("-fx-font-size: 11px;");
                        movNote.setMaxWidth(200);
                        movNote.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

                        VBox movLeft = new VBox(2, movCat, movNote);
                        HBox.setHgrow(movLeft, Priority.ALWAYS);

                        Label movAmount = new Label(DashboardFormatters.formatMoney(signed, cur));
                        movAmount.getStyleClass().add(signed >= 0 ? "money-positive" : "money-negative");
                        movAmount.setStyle("-fx-font-size: 13px; -fx-font-weight: 700;");

                        Label movDate = new Label(dateStr);
                        movDate.getStyleClass().add("text-secondary");
                        movDate.setStyle("-fx-font-size: 11px;");
                        movDate.setAlignment(Pos.CENTER_RIGHT);

                        VBox movRight = new VBox(2, movAmount, movDate);
                        movRight.setAlignment(Pos.CENTER_RIGHT);

                        FontIcon movIcon = new FontIcon(iconLiteral);
                        movIcon.setIconSize(14);
                        StackPane iconCircle = new StackPane(movIcon);
                        iconCircle.getStyleClass().add("tx-item-icon-bubble");
                        iconCircle.getStyleClass().add(signed >= 0 ? "tx-item-icon-income" : "tx-item-icon-expense");
                        iconCircle.setMinSize(36, 36);
                        iconCircle.setMaxSize(36, 36);

                        HBox movRow = new HBox(10, iconCircle, movLeft, movRight);
                        movRow.setAlignment(Pos.CENTER_LEFT);
                        movRow.getStyleClass().add("tx-item");
                        movBox.getChildren().add(movRow);
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        ScrollPane movScroll = new ScrollPane(movBox);
        movScroll.setFitToWidth(true);
        movScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        movScroll.getStyleClass().addAll("edge-to-edge");
        VBox.setVgrow(movScroll, Priority.ALWAYS);

        viewAllBtn.setOnAction(e -> {
            modal.close();
            if (onViewAllMovements != null && existing != null) {
                onViewAllMovements.accept(existing.id());
            }
        });

        VBox rightCol = new VBox(14, balanceCard, statsRow, movHeader, movScroll);
        rightCol.setPadding(new Insets(0, 0, 0, 14));
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        // ── Root layout ───────────────────────────────────────────────
        HBox root = new HBox(20, leftCol, rightCol);
        root.setPadding(new Insets(20));

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(12, bDelete, footerSpacer, bSave, bCancel);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(14, 20, 14, 20));

        VBox shell = new VBox(root, footer);
        shell.getStyleClass().add("app-root");

        Scene scene = new Scene(shell);
        var themeUrl = DashboardAccountsFeature.class.getResource(darkTheme ? "/styles/dark.css" : "/styles/light.css");
        if (themeUrl != null) {
            scene.getStylesheets().add(themeUrl.toExternalForm());
        }
        modal.setScene(scene);

        final java.util.concurrent.atomic.AtomicReference<EditAccountResult> resultRef = new java.util.concurrent.atomic.AtomicReference<>(null);

        // Add ESC key handler to close dialog
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                resultRef.set(null);
                modal.close();
            }
        });

        bCancel.setOnAction(e -> {
            resultRef.set(null);
            modal.close();
        });
        bDelete.setOnAction(e -> {
            resultRef.set(new EditAccountResult(EditAccountAction.DELETE, null, null, null));
            modal.close();
        });
        bSave.setOnAction(e -> {
            String n = nameField.getText() == null ? "" : nameField.getText().trim();
            if (n.isBlank()) {
                return;
            }
            String t = AccountRepository.normalizeType(typeBox.getValue());
            String c = colorPicker.getValue();
            resultRef.set(new EditAccountResult(EditAccountAction.SAVE, n, t, c));
            modal.close();
        });

        modal.showAndWait();
        EditAccountResult r = resultRef.get();
        return r == null ? Optional.empty() : Optional.of(r);
    }

    private static VBox buildStatBox(String value, String label, String iconLiteral, String colorHex) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(16);
        try { icon.setIconColor(Color.web(colorHex)); } catch (Exception ignored) {}

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 800;");
        valueLabel.getStyleClass().add("account-name");

        Label descLabel = new Label(label);
        descLabel.getStyleClass().add("text-secondary");
        descLabel.setStyle("-fx-font-size: 11px;");

        VBox box = new VBox(2, icon, valueLabel, descLabel);
        box.getStyleClass().add("edit-account-stat-box");
        box.setAlignment(Pos.TOP_LEFT);
        box.setPadding(new Insets(10));
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
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
