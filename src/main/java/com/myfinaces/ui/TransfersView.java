package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AccountStyles;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.collections.FXCollections;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.scene.text.TextFlow;
import javafx.stage.Screen;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.application.Platform;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import javafx.geometry.Rectangle2D;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.util.Duration;
import javafx.util.StringConverter;

public final class TransfersView {

    private TransfersView() {
    }

    private record TransferUiRow(
        String transferId,
        String fromAccountId,
        String toAccountId,
        String fromName,
        String toName,
        String fromType,
        String toType,
        String fromTypeKey,
        String toTypeKey,
        String fromColor,
        String toColor,
        long amountCents,
        LocalDate date,
        String timeText
    ) {
    }

    private record TransferMetrics(
        long totalMovidoCents,
        int totalMovimientos,
        String cuentaMasUsada,
        double promedioCents
    ) {
    }

    public static Node buildTransfersView(
        AuthSession session,
        TransferRepository transferRepo,
        AccountRepository accountRepo,
        BooleanSupplier darkTheme,
        Runnable refreshBalances
    ) {
        List<TransferRepository.TransferRow> transferRows = new ArrayList<>();
        List<AccountRepository.Account> accounts = new ArrayList<>();
        Map<String, AccountRepository.Account> accountsById = new HashMap<>();
        List<TransferUiRow> transferUiRows = new ArrayList<>();
        TransferMetrics metrics = new TransferMetrics(0L, 0, "", 0.0);
        try {
            transferRows.addAll(transferRepo.listFiltered(session.uid(), null, null, null, 500));
            accounts.addAll(accountRepo.list(session.uid()));
            System.out.println("[TransfersView] accounts count=" + (accounts == null ? 0 : accounts.size()));
            if (accounts == null || accounts.isEmpty()) {
                System.out.println("[TransfersView] accounts is empty");
            }
            for (AccountRepository.Account a : accounts) {
                if (a != null && a.id() != null && !a.id().isBlank()) {
                    accountsById.put(a.id(), a);
                }
            }

            metrics = computeMetrics(transferRows, accountsById);

            for (TransferRepository.TransferRow tr : transferRows) {
                AccountRepository.Account from = accountsById.get(tr.fromAccountId());
                AccountRepository.Account to = accountsById.get(tr.toAccountId());

                String fromName = from != null && from.name() != null ? from.name() : tr.fromAccountName();
                String toName = to != null && to.name() != null ? to.name() : tr.toAccountName();
                String fromType = accountTypeLabel(from == null ? null : from.type());
                String toType = accountTypeLabel(to == null ? null : to.type());
                String fromTypeKey = from != null ? AccountRepository.normalizeType(from.type()) : "BANK";
                String toTypeKey = to != null ? AccountRepository.normalizeType(to.type()) : "BANK";
                String fromColor = from != null ? from.color() : null;
                String toColor = to != null ? to.color() : null;
                Instant occurred = Instant.ofEpochSecond(tr.occurredAtEpochSec());
                LocalDate date = occurred.atZone(ZoneId.systemDefault()).toLocalDate();
                LocalTime time = occurred.atZone(ZoneId.systemDefault()).toLocalTime();
                DateTimeFormatter tf = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("es-CO"));
                String timeText = tf.format(time);

                transferUiRows.add(new TransferUiRow(tr.id(), tr.fromAccountId(), tr.toAccountId(), fromName, toName, fromType, toType, fromTypeKey, toTypeKey, fromColor, toColor, tr.amountCents(), date, timeText));
            }

            System.out.println("[TransfersView] transferUiRows=" + transferUiRows.size());
            System.out.println("[TransfersView] metrics totalMovidoCents=" + metrics.totalMovidoCents());
            System.out.println("[TransfersView] metrics totalMovimientos=" + metrics.totalMovimientos());
            System.out.println("[TransfersView] metrics promedioCents=" + metrics.promedioCents());
            System.out.println("[TransfersView] metrics cuentaMasUsada=" + metrics.cuentaMasUsada());
        } catch (Exception ex) {
            System.out.println("[TransfersView] data load error: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            transferRows.clear();
            accounts.clear();
            transferUiRows.clear();
        }

        VBox root = new VBox(12);
        root.setPadding(new Insets(14));
        root.getStyleClass().add("transfers-root");
        java.net.URL cssUrl = TransfersView.class.getResource("/styles/transfers.css");
        if (cssUrl != null) {
            root.getStylesheets().add(cssUrl.toExternalForm());
        }
        java.net.URL themeCss = TransfersView.class.getResource(darkTheme.getAsBoolean() ? "/styles/dark.css" : "/styles/light.css");
        if (themeCss != null) {
            root.getStylesheets().add(themeCss.toExternalForm());
        }

        Label headerTitle = new Label("Transferencias");
        headerTitle.getStyleClass().add("tr-title");
        Label headerSubtitle = new Label("Mueve dinero entre tus cuentas (origen -> destino). Esto afecta saldos.");
        headerSubtitle.getStyleClass().add("tr-subtitle");
        VBox headerText = new VBox(4, headerTitle, headerSubtitle);
        headerText.setAlignment(Pos.CENTER_LEFT);

        Label btnIcon = new Label("+");
        btnIcon.getStyleClass().add("btn-primary-text");
        Label btnText = new Label("Nueva transferencia");
        btnText.getStyleClass().add("btn-primary-text");
        HBox btnContent = new HBox(6, btnIcon, btnText);
        btnContent.setAlignment(Pos.CENTER);

        Button newTransferBtn = new Button();
        newTransferBtn.setText("");
        newTransferBtn.setGraphic(btnContent);
        newTransferBtn.getStyleClass().add("btn-primary");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        final Runnable[] reloadHolder = new Runnable[]{ () -> {} };

        newTransferBtn.setOnAction(e -> {
            Window owner = newTransferBtn.getScene() == null ? null : newTransferBtn.getScene().getWindow();
            openNewTransferModal(owner, darkTheme.getAsBoolean(), session, transferRepo, refreshBalances, reloadHolder, session.uid(), accountRepo, accounts);
        });

        HBox headerRow = new HBox(12, headerText, headerSpacer, newTransferBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox headerSection = new VBox(6, headerRow);

        Label totalMovedValue = new Label(DashboardFormatters.formatMoney(metrics.totalMovidoCents()));
        Label totalMovementsValue = new Label(String.valueOf(metrics.totalMovimientos()));
        Label mostUsedValue = new Label(metrics.cuentaMasUsada() == null ? "" : metrics.cuentaMasUsada());
        Label avgValue = new Label(DashboardFormatters.formatMoney(Math.round(metrics.promedioCents())));

        Label totalMovedSub = new Label("Este período");
        Label totalMovementsSub = new Label("Este período");
        Label mostUsedSub = new Label("");
        Label avgSub = new Label("Este período");

        String svgArrowLR = "M8 3 L4 7 L8 11 M4 7 H20 M16 13 L20 17 L16 21 M20 17 H4";
        String svgArrowsUD = "M7 4 V20 M3 8 L7 4 L11 8 M17 20 V4 M13 16 L17 20 L21 16";
        String svgWallet = "M3 7 H21 V19 H3 Z M3 11 H21";
        String svgCalendar = "M8 2 V6 M16 2 V6 M3 10 H21 M5 4 H19 A2 2 0 0 1 21 6 V20 A2 2 0 0 1 19 22 H5 A2 2 0 0 1 3 20 V6 A2 2 0 0 1 5 4 Z";

        HBox totalMovedCard = buildMetricCard(svgArrowLR, "Transferencias este mes", totalMovedValue, totalMovedSub, "metric-icon--blue");
        HBox totalMovementsCard = buildMetricCard(svgArrowsUD, "Número de movimientos", totalMovementsValue, totalMovementsSub, "metric-icon--green");
        HBox mostUsedCard = buildMetricCard(svgWallet, "Cuenta más utilizada", mostUsedValue, mostUsedSub, "metric-icon--purple");
        HBox avgCard = buildMetricCard(svgCalendar, "Promedio por transferencia", avgValue, avgSub, "metric-icon--amber");

        HBox summaryRow = new HBox(12, totalMovedCard, totalMovementsCard, mostUsedCard, avgCard);
        summaryRow.setAlignment(Pos.CENTER_LEFT);
        summaryRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(totalMovedCard, Priority.ALWAYS);
        HBox.setHgrow(totalMovementsCard, Priority.ALWAYS);
        HBox.setHgrow(mostUsedCard, Priority.ALWAYS);
        HBox.setHgrow(avgCard, Priority.ALWAYS);

        VBox summarySection = new VBox(6, summaryRow);

        Label accountLabel = new Label("Cuenta");
        accountLabel.getStyleClass().add("tr-label");
        ComboBox<AccountRepository.Account> accountFilter = new ComboBox<>();
        accountFilter.setPromptText("(Todas)");
        accountFilter.setPrefWidth(220);
        accountFilter.getStyleClass().add("combo-box-custom");
        accountFilter.setItems(FXCollections.observableArrayList(accounts));
        accountFilter.setConverter(new StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                if (object == null) {
                    return "";
                }
                return object.name() == null ? "" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });
        accountFilter.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(AccountRepository.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.name() == null ? "" : item.name());
                }
            }
        });

        HBox accountBox = new HBox(8, accountLabel, accountFilter);
        accountBox.setAlignment(Pos.CENTER_LEFT);

        MenuButton dateFilter = new MenuButton();
        dateFilter.getStyleClass().addAll("tx-chip", "date-chip");
        dateFilter.setText("Fecha");
        MenuItem dateAll = new MenuItem("Todas");
        MenuItem dateToday = new MenuItem("Hoy");
        MenuItem dateYesterday = new MenuItem("Ayer");
        MenuItem dateThisMonth = new MenuItem("Este mes");
        MenuItem dateCustom = new MenuItem("Rango...");
        dateFilter.getItems().setAll(dateAll, dateToday, dateYesterday, dateThisMonth, dateCustom);

        LocalDate[] dateFrom = { null };
        LocalDate[] dateTo = { null };

        java.time.format.DateTimeFormatter dateFmtShort = java.time.format.DateTimeFormatter
                .ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("es"));
        Runnable applyDateLabel = () -> {
            LocalDate f = dateFrom[0];
            LocalDate t = dateTo[0];
            if (f == null && t == null) {
                dateFilter.setText("Fecha");
                return;
            }
            LocalDate today = LocalDate.now();
            if (f != null && t != null && f.equals(t)) {
                if (f.equals(today)) {
                    dateFilter.setText("Hoy");
                } else if (f.equals(today.minusDays(1))) {
                    dateFilter.setText("Ayer");
                } else {
                    dateFilter.setText(dateFmtShort.format(f));
                }
                return;
            }
            if (f != null && t != null
                    && f.equals(today.withDayOfMonth(1)) && t.equals(today)) {
                dateFilter.setText("Este mes");
                return;
            }
            if (f != null && t != null) {
                dateFilter.setText(dateFmtShort.format(f) + "  –  " + dateFmtShort.format(t));
            } else if (f != null) {
                dateFilter.setText("Desde " + dateFmtShort.format(f));
            } else {
                dateFilter.setText("Hasta " + dateFmtShort.format(t));
            }
        };

        Label dateLabel = new Label("Fecha");
        dateLabel.getStyleClass().add("tr-label");
        HBox dateBox = new HBox(8, dateLabel, dateFilter);
        dateBox.setAlignment(Pos.CENTER_LEFT);

        Button clearFiltersBtn = new Button("Limpiar filtros");
        clearFiltersBtn.getStyleClass().add("btn-clear");

        Region filtersSpacer = new Region();
        HBox.setHgrow(filtersSpacer, Priority.ALWAYS);
        HBox filtersRow = new HBox(16, accountBox, dateBox, filtersSpacer, clearFiltersBtn);
        filtersRow.setAlignment(Pos.CENTER_LEFT);
        filtersRow.getStyleClass().add("filters-card");

        VBox filtersSection = new VBox(6, filtersRow);

        VBox historySection = new VBox(6);
        VBox historyList = new VBox(2);
        historyList.setFillWidth(true);

        ScrollPane historyScroll = new ScrollPane(historyList);
        historyScroll.setFitToWidth(true);
        historyScroll.setFitToHeight(false);
        historyScroll.setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        historyScroll.setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        historyScroll.getStyleClass().add("history-scroll");
        VBox.setVgrow(historyScroll, Priority.ALWAYS);

        VBox historyCard = new VBox(historyScroll);
        historyCard.getStyleClass().add("history-card");
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        Consumer<TransferUiRow> onDelete = row -> {
            if (row == null || row.transferId() == null) return;
            Dialog<ButtonType> confirm = new Dialog<>();
            confirm.setTitle("Eliminar");
            UiDialogs.applyAppTheme(confirm, darkTheme.getAsBoolean());
            confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            confirm.setContentText("¿Eliminar esta transferencia?");
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) return;
                try {
                    transferRepo.delete(session.uid(), row.transferId());
                    if (refreshBalances != null) refreshBalances.run();
                    reloadHolder[0].run();
                } catch (Exception ignored) {}
            });
        };
        Consumer<TransferUiRow> onEdit = row -> {
            if (row == null || row.transferId() == null) return;
            try {
                TransferRepository.TransferRow existing = null;
                for (TransferRepository.TransferRow t : transferRows) {
                    if (t != null && row.transferId().equals(t.id())) { existing = t; break; }
                }
                if (existing == null) return;
                Optional<DashboardTransfersDialog.NewTransfer> updated =
                        DashboardTransfersDialog.showEditTransferDialog(existing, session.uid(), accountRepo, darkTheme.getAsBoolean());
                if (updated.isEmpty()) return;
                DashboardTransfersDialog.NewTransfer ut = updated.get();
                transferRepo.update(session.uid(), row.transferId(), ut.fromAccountId(), ut.toAccountId(), ut.amountCents(), ut.occurredAtEpochSec(), ut.note());
                if (refreshBalances != null) refreshBalances.run();
                reloadHolder[0].run();
            } catch (Exception ignored) {}
        };

        renderHistory(historyList, transferUiRows, onEdit, onDelete);
        historySection.getChildren().addAll(historyCard);
        VBox.setVgrow(historySection, Priority.ALWAYS);

        Region historySpacer = new Region();
        VBox.setVgrow(historySpacer, Priority.ALWAYS);

        root.getChildren().addAll(headerSection, summarySection, filtersSection, historySection);

        Runnable refreshFiltered = () -> {
            String selectedId = accountFilter.getValue() == null ? null : accountFilter.getValue().id();
            LocalDate from = dateFrom[0];
            LocalDate to = dateTo[0];

            List<TransferUiRow> filtered = new ArrayList<>();
            for (TransferUiRow r : transferUiRows) {
                if (r == null) {
                    continue;
                }

                boolean matchesAccount = true;
                if (selectedId != null && !selectedId.isBlank()) {
                    matchesAccount = selectedId.equals(r.fromAccountId()) || selectedId.equals(r.toAccountId());
                }

                boolean matchesDate = true;
                if (from != null || to != null) {
                    LocalDate d = r.date();
                    if (d == null) {
                        matchesDate = false;
                    } else {
                        if (from != null && d.isBefore(from)) {
                            matchesDate = false;
                        }
                        if (matchesDate && to != null && d.isAfter(to)) {
                            matchesDate = false;
                        }
                    }
                }

                if (matchesAccount && matchesDate) {
                    filtered.add(r);
                }
            }

            TransferMetrics filteredMetrics = computeMetricsFromUiRows(filtered);
            totalMovedValue.setText(DashboardFormatters.formatMoney(filteredMetrics.totalMovidoCents()));
            totalMovementsValue.setText(String.valueOf(filteredMetrics.totalMovimientos()));
            mostUsedValue.setText(filteredMetrics.cuentaMasUsada() == null ? "" : filteredMetrics.cuentaMasUsada());
            avgValue.setText(DashboardFormatters.formatMoney(Math.round(filteredMetrics.promedioCents())));

            mostUsedSub.setText(formatMostUsedCount(filtered, filteredMetrics.cuentaMasUsada()));

            renderHistory(historyList, filtered, onEdit, onDelete);
        };

        reloadHolder[0] = () -> {
            try {
                List<TransferRepository.TransferRow> newRows = transferRepo.listFiltered(session.uid(), null, null, null, 500);
                List<AccountRepository.Account> newAccounts = accountRepo.list(session.uid());
                transferRows.clear();
                transferRows.addAll(newRows);
                accounts.clear();
                accounts.addAll(newAccounts);
                accountsById.clear();
                for (AccountRepository.Account a : newAccounts) {
                    if (a != null && a.id() != null && !a.id().isBlank()) accountsById.put(a.id(), a);
                }
                transferUiRows.clear();
                DateTimeFormatter tf = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("es-CO"));
                for (TransferRepository.TransferRow tr : transferRows) {
                    AccountRepository.Account from = accountsById.get(tr.fromAccountId());
                    AccountRepository.Account to = accountsById.get(tr.toAccountId());
                    String fromName = from != null && from.name() != null ? from.name() : tr.fromAccountName();
                    String toName = to != null && to.name() != null ? to.name() : tr.toAccountName();
                    String fromType = accountTypeLabel(from == null ? null : from.type());
                    String toType = accountTypeLabel(to == null ? null : to.type());
                    String fromTypeKey = from != null ? AccountRepository.normalizeType(from.type()) : "BANK";
                    String toTypeKey = to != null ? AccountRepository.normalizeType(to.type()) : "BANK";
                    String fromColor = from != null ? from.color() : null;
                    String toColor = to != null ? to.color() : null;
                    Instant occurred = Instant.ofEpochSecond(tr.occurredAtEpochSec());
                    LocalDate date = occurred.atZone(ZoneId.systemDefault()).toLocalDate();
                    LocalTime time = occurred.atZone(ZoneId.systemDefault()).toLocalTime();
                    String timeText = tf.format(time);
                    transferUiRows.add(new TransferUiRow(tr.id(), tr.fromAccountId(), tr.toAccountId(), fromName, toName, fromType, toType, fromTypeKey, toTypeKey, fromColor, toColor, tr.amountCents(), date, timeText));
                }
                refreshFiltered.run();
            } catch (Exception ignored) {}
        };

        clearFiltersBtn.setOnAction(e -> {
            accountFilter.getSelectionModel().clearSelection();
            dateFrom[0] = null;
            dateTo[0] = null;
            applyDateLabel.run();
            refreshFiltered.run();
        });

        accountFilter.valueProperty().addListener((obs, oldV, newV) -> refreshFiltered.run());

        dateAll.setOnAction(e -> {
            dateFrom[0] = null;
            dateTo[0] = null;
            applyDateLabel.run();
            refreshFiltered.run();
        });
        dateToday.setOnAction(e -> {
            LocalDate d = LocalDate.now();
            dateFrom[0] = d;
            dateTo[0] = d;
            applyDateLabel.run();
            refreshFiltered.run();
        });
        dateYesterday.setOnAction(e -> {
            LocalDate d = LocalDate.now().minusDays(1);
            dateFrom[0] = d;
            dateTo[0] = d;
            applyDateLabel.run();
            refreshFiltered.run();
        });
        dateThisMonth.setOnAction(e -> {
            LocalDate now = LocalDate.now();
            dateFrom[0] = now.withDayOfMonth(1);
            dateTo[0] = now;
            applyDateLabel.run();
            refreshFiltered.run();
        });
        dateCustom.setOnAction(e -> {
            Dialog<ButtonType> rangeDialog = new Dialog<>();
            rangeDialog.setTitle("Rango de fechas");
            rangeDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            UiDialogs.applyAppTheme(rangeDialog, darkTheme.getAsBoolean());

            DatePicker fromPicker = new DatePicker(dateFrom[0]);
            DatePicker toPicker = new DatePicker(dateTo[0]);
            VBox content = new VBox(10, new HBox(10, new Label("Desde"), fromPicker), new HBox(10, new Label("Hasta"), toPicker));
            content.setPadding(new Insets(18, 20, 24, 20));
            rangeDialog.getDialogPane().setContent(content);
            rangeDialog.getDialogPane().setPadding(new Insets(8, 8, 16, 8));
            javafx.scene.Node okBtn = rangeDialog.getDialogPane().lookupButton(ButtonType.OK);
            if (okBtn instanceof Button okButton) {
                okButton.setText("Aceptar");
                okButton.setMinWidth(96);
                okButton.setPrefWidth(96);
            }
            javafx.scene.Node cancelBtn = rangeDialog.getDialogPane().lookupButton(ButtonType.CANCEL);
            if (cancelBtn instanceof Button cancelButton) {
                cancelButton.setMinWidth(96);
                cancelButton.setPrefWidth(96);
            }
            rangeDialog.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                dateFrom[0] = fromPicker.getValue();
                dateTo[0] = toPicker.getValue();
                applyDateLabel.run();
                refreshFiltered.run();
            });
        });

        return root;
    }

    private static void openNewTransferModal(
        Window owner,
        boolean darkTheme,
        AuthSession session,
        TransferRepository transferRepo,
        Runnable refreshBalances,
        Runnable[] reloadHolder,
        String userUid,
        AccountRepository accountRepo,
        List<AccountRepository.Account> accounts
    ) {
        Stage modal = new Stage();
        modal.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            modal.initOwner(owner);
        }
        modal.initStyle(StageStyle.UNDECORATED);
        modal.setResizable(false);
        modal.setTitle("Nueva transferencia");

        Label titleLabel = new Label("Nueva transferencia");
        titleLabel.getStyleClass().add("modal-title");

        Label subtitleLabel = new Label("Mueve tu dinero entre tus cuentas");
        subtitleLabel.getStyleClass().add("modal-subtitle");

        VBox titleText = new VBox(2, titleLabel, subtitleLabel);
        titleText.setAlignment(Pos.CENTER_LEFT);

        SVGPath closeIcon = new SVGPath();
        closeIcon.setContent("M6 6 L18 18 M18 6 L6 18");
        closeIcon.getStyleClass().add("modal-close-icon");

        Button closeBtn = new Button();
        closeBtn.setGraphic(closeIcon);
        closeBtn.getStyleClass().add("modal-close-btn");
        closeBtn.setFocusTraversable(false);
        closeBtn.setOnAction(e -> modal.close());

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        HBox titleBar = new HBox(8, titleText, titleSpacer, closeBtn);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getStyleClass().add("modal-title-bar");

        VBox content = new VBox(10);
        content.setPadding(new Insets(14));
        content.getStyleClass().add("modal-content");

        Map<String, Long> balanceByAccountId = new HashMap<>();
        if (accounts != null && accountRepo != null && userUid != null && !userUid.isBlank()) {
            for (AccountRepository.Account a : accounts) {
                if (a == null || a.id() == null || a.id().isBlank()) {
                    continue;
                }
                try {
                    balanceByAccountId.put(a.id(), accountRepo.computeBalanceCents(userUid, a.id()));
                } catch (Exception ignored) {
                    balanceByAccountId.put(a.id(), null);
                }
            }
        }

        Label fromLabel = new Label("Desde");
        fromLabel.getStyleClass().add("modal-field-label");

        ComboBox<AccountRepository.Account> fromAccountCombo = new ComboBox<>();
        fromAccountCombo.getStyleClass().add("account-combo");
        fromAccountCombo.setPromptText("Selecciona una cuenta");
        fromAccountCombo.setMaxWidth(Double.MAX_VALUE);
        fromAccountCombo.setItems(FXCollections.observableArrayList(accounts == null ? List.of() : accounts));

        ObjectProperty<AccountRepository.Account> toSelectedForFrom = new SimpleObjectProperty<>(null);

        var fromCellFactory = (javafx.util.Callback<javafx.scene.control.ListView<AccountRepository.Account>, javafx.scene.control.ListCell<AccountRepository.Account>>) cb -> new ListCell<>() {
            @Override
            protected void updateItem(AccountRepository.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setDisable(false);
                    setOpacity(1.0);
                    return;
                }

                AccountRepository.Account toSelected = toSelectedForFrom.get();
                boolean disallowed = toSelected != null
                    && toSelected.id() != null
                    && item.id() != null
                    && toSelected.id().equals(item.id());
                setDisable(disallowed);
                setOpacity(disallowed ? 0.45 : 1.0);

                StackPane avatar = buildAccountAvatar(AccountRepository.normalizeType(item.type()), item.color());
                avatar.setMinSize(32, 32);
                avatar.setPrefSize(32, 32);
                avatar.setMaxSize(32, 32);

                Label name = new Label(titleCase(item.name() == null ? "" : item.name()));
                name.getStyleClass().add("account-option-name");

                Label type = new Label(accountTypeLabel(item.type()));
                type.getStyleClass().add("account-option-type");

                VBox leftText = new VBox(2, name, type);
                leftText.setAlignment(Pos.CENTER_LEFT);
                leftText.setMinWidth(0);

                Long bal = item.id() == null ? null : balanceByAccountId.get(item.id());
                String balText = bal == null ? "--" : DashboardFormatters.formatMoney(bal, item.currency());
                Label availableLabel = new Label("Disponible");
                availableLabel.getStyleClass().add("account-option-available-label");
                Label availableValue = new Label(balText);
                availableValue.getStyleClass().add("account-option-available-value");
                VBox availableBox = new VBox(2, availableLabel, availableValue);
                availableBox.setAlignment(Pos.CENTER_RIGHT);
                availableBox.getStyleClass().add("account-option-available-box");

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox row = new HBox(10, avatar, leftText, spacer, availableBox);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
        fromAccountCombo.setCellFactory(fromCellFactory);
        fromAccountCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AccountRepository.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                StackPane avatar = buildAccountAvatar(AccountRepository.normalizeType(item.type()), item.color());
                avatar.setMinSize(32, 32);
                avatar.setPrefSize(32, 32);
                avatar.setMaxSize(32, 32);

                Label name = new Label(titleCase(item.name() == null ? "" : item.name()));
                name.getStyleClass().add("account-option-name");

                Label type = new Label(accountTypeLabel(item.type()));
                type.getStyleClass().add("account-option-type");

                VBox leftText = new VBox(2, name, type);
                leftText.setAlignment(Pos.CENTER_LEFT);
                leftText.setMinWidth(0);

                Long bal = item.id() == null ? null : balanceByAccountId.get(item.id());
                String balText = bal == null ? "--" : DashboardFormatters.formatMoney(bal, item.currency());
                Label availableLabel = new Label("Disponible");
                availableLabel.getStyleClass().add("account-option-available-label");
                Label availableValue = new Label(balText);
                availableValue.getStyleClass().add("account-option-available-value");
                VBox availableBox = new VBox(2, availableLabel, availableValue);
                availableBox.setAlignment(Pos.CENTER_RIGHT);
                availableBox.getStyleClass().add("account-option-available-box");

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox row = new HBox(10, avatar, leftText, spacer, availableBox);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });

        final boolean[] accountSelectionLock = new boolean[] { false };
        if (accounts != null && !accounts.isEmpty()) {
            fromAccountCombo.getSelectionModel().selectFirst();
        }

        VBox fromSection = new VBox(6, fromLabel, fromAccountCombo);
        fromSection.getStyleClass().add("modal-field-section");
        content.getChildren().add(fromSection);

        Label toLabel = new Label("Hacia");
        toLabel.getStyleClass().add("modal-field-label");

        ComboBox<AccountRepository.Account> toAccountCombo = new ComboBox<>();
        toAccountCombo.getStyleClass().add("account-combo");
        toAccountCombo.setPromptText("Selecciona una cuenta");
        toAccountCombo.setMaxWidth(Double.MAX_VALUE);
        toAccountCombo.setItems(FXCollections.observableArrayList(accounts == null ? List.of() : accounts));

        SVGPath swapIcon = new SVGPath();
        swapIcon.setContent("M10 2 V10 M6 6 L10 2 L14 6 M10 22 V14 M6 18 L10 22 L14 18");
        swapIcon.getStyleClass().add("modal-swap-icon");

        Button swapBtn = new Button();
        swapBtn.setGraphic(swapIcon);
        swapBtn.getStyleClass().add("modal-swap-btn");
        swapBtn.setFocusTraversable(false);
        swapBtn.setOnAction(e -> {
            AccountRepository.Account fromV = fromAccountCombo.getValue();
            AccountRepository.Account toV = toAccountCombo.getValue();
            if (fromV == null || toV == null) {
                return;
            }
            accountSelectionLock[0] = true;
            try {
                fromAccountCombo.setValue(toV);
                toAccountCombo.setValue(fromV);
            } finally {
                accountSelectionLock[0] = false;
            }
        });

        StackPane swapWrap = new StackPane(swapBtn);
        swapWrap.getStyleClass().add("modal-swap-wrap");
        StackPane.setAlignment(swapBtn, Pos.CENTER);

        content.getChildren().add(swapWrap);

        var toCellFactory = (javafx.util.Callback<javafx.scene.control.ListView<AccountRepository.Account>, javafx.scene.control.ListCell<AccountRepository.Account>>) cb -> new ListCell<>() {
            @Override
            protected void updateItem(AccountRepository.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setDisable(false);
                    setOpacity(1.0);
                    return;
                }

                AccountRepository.Account fromSelected = fromAccountCombo.getValue();
                boolean disallowed = fromSelected != null
                    && fromSelected.id() != null
                    && item.id() != null
                    && fromSelected.id().equals(item.id());
                setDisable(disallowed);
                setOpacity(disallowed ? 0.45 : 1.0);

                if (!disallowed) {
                    setDisable(false);
                    setOpacity(1.0);
                }

                StackPane avatar = buildAccountAvatar(AccountRepository.normalizeType(item.type()), item.color());
                avatar.setMinSize(32, 32);
                avatar.setPrefSize(32, 32);
                avatar.setMaxSize(32, 32);

                Label name = new Label(titleCase(item.name() == null ? "" : item.name()));
                name.getStyleClass().add("account-option-name");

                Label type = new Label(accountTypeLabel(item.type()));
                type.getStyleClass().add("account-option-type");

                VBox leftText = new VBox(2, name, type);
                leftText.setAlignment(Pos.CENTER_LEFT);
                leftText.setMinWidth(0);

                HBox row = new HBox(10, avatar, leftText);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
        toAccountCombo.setCellFactory(toCellFactory);
        toAccountCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AccountRepository.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                StackPane avatar = buildAccountAvatar(AccountRepository.normalizeType(item.type()), item.color());
                avatar.setMinSize(32, 32);
                avatar.setPrefSize(32, 32);
                avatar.setMaxSize(32, 32);

                Label name = new Label(titleCase(item.name() == null ? "" : item.name()));
                name.getStyleClass().add("account-option-name");

                Label type = new Label(accountTypeLabel(item.type()));
                type.getStyleClass().add("account-option-type");

                VBox leftText = new VBox(2, name, type);
                leftText.setAlignment(Pos.CENTER_LEFT);
                leftText.setMinWidth(0);

                HBox row = new HBox(10, avatar, leftText);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });
        if (accounts != null && !accounts.isEmpty()) {
            AccountRepository.Account fromSelected = fromAccountCombo.getValue();
            AccountRepository.Account pick = null;
            for (AccountRepository.Account a : toAccountCombo.getItems()) {
                if (a == null || a.id() == null) {
                    continue;
                }
                if (fromSelected != null && fromSelected.id() != null && fromSelected.id().equals(a.id())) {
                    continue;
                }
                pick = a;
                break;
            }
            if (pick != null) {
                toAccountCombo.setValue(pick);
            }
        }

        fromAccountCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (accountSelectionLock[0]) {
                return;
            }
            AccountRepository.Account toV = toAccountCombo.getValue();
            if (newV != null && toV != null && newV.id() != null && newV.id().equals(toV.id())) {
                accountSelectionLock[0] = true;
                try {
                    AccountRepository.Account fallback = null;
                    for (AccountRepository.Account a : toAccountCombo.getItems()) {
                        if (a == null || a.id() == null) {
                            continue;
                        }
                        if (newV.id().equals(a.id())) {
                            continue;
                        }
                        fallback = a;
                        break;
                    }
                    toAccountCombo.setValue(fallback);
                } finally {
                    accountSelectionLock[0] = false;
                }
            }

            boolean showing = toAccountCombo.isShowing();
            if (showing) {
                toAccountCombo.hide();
            }
            toAccountCombo.setCellFactory(toCellFactory);
            AccountRepository.Account selectedTo = toAccountCombo.getValue();
            ObservableList<AccountRepository.Account> currentItems = toAccountCombo.getItems();
            toAccountCombo.setItems(null);
            toAccountCombo.setItems(currentItems);
            toAccountCombo.setValue(selectedTo);
            if (showing) {
                toAccountCombo.show();
            }
        });

        toAccountCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (accountSelectionLock[0]) {
                return;
            }
            AccountRepository.Account fromV = fromAccountCombo.getValue();
            if (newV != null && fromV != null && newV.id() != null && newV.id().equals(fromV.id())) {
                accountSelectionLock[0] = true;
                try {
                    AccountRepository.Account fallback = null;
                    for (AccountRepository.Account a : fromAccountCombo.getItems()) {
                        if (a == null || a.id() == null) {
                            continue;
                        }
                        if (newV.id().equals(a.id())) {
                            continue;
                        }
                        fallback = a;
                        break;
                    }
                    fromAccountCombo.setValue(fallback);
                } finally {
                    accountSelectionLock[0] = false;
                }
            }

            toSelectedForFrom.set(newV);

            AccountRepository.Account selectedFrom = fromAccountCombo.getValue();
            fromAccountCombo.setCellFactory(fromCellFactory);
            fromAccountCombo.setValue(selectedFrom);
        });

        VBox toSection = new VBox(6, toLabel, toAccountCombo);
        toSection.getStyleClass().add("modal-field-section");
        content.getChildren().add(toSection);

        Label amountLabel = new Label("Monto");
        amountLabel.getStyleClass().add("modal-field-label");

        TextField amountField = new TextField();
        amountField.getStyleClass().add("modal-amount-input");
        amountField.setPromptText("$ 0,00");

        final boolean[] amountLock = new boolean[] { false };
        amountField.textProperty().addListener((obs, oldV, newV) -> {
            if (amountLock[0]) {
                return;
            }
            amountLock[0] = true;
            try {
                String digits = newV == null ? "" : newV.replaceAll("\\D", "");
                if (digits.isBlank()) {
                    amountField.setText("");
                    return;
                }
                long cents;
                try {
                    cents = Long.parseLong(digits);
                } catch (NumberFormatException ex) {
                    cents = 0L;
                }
                AccountRepository.Account a = fromAccountCombo.getValue();
                String currency = a == null ? "COP" : a.currency();
                amountField.setText(formatMoneySpaced(cents, currency));
                amountField.positionCaret(amountField.getText().length());
            } finally {
                amountLock[0] = false;
            }
        });

        VBox amountSection = new VBox(6, amountLabel, amountField);
        amountSection.getStyleClass().add("modal-field-section");
        content.getChildren().add(amountSection);

        Label descLabel = new Label("Descripción (opcional)");
        descLabel.getStyleClass().add("modal-field-label");

        TextField descField = new TextField();
        descField.getStyleClass().add("modal-text-input");
        descField.setPromptText("Descripción (opcional)");

        VBox descSection = new VBox(6, descLabel, descField);
        descSection.getStyleClass().add("modal-field-section");
        content.getChildren().add(descSection);

        Label dateLabel = new Label("Fecha");
        dateLabel.getStyleClass().add("modal-field-label");

        DatePicker datePicker = new DatePicker(LocalDate.now());
        datePicker.setEditable(false);
        datePicker.getStyleClass().add("modal-date-picker");
        datePicker.getEditor().setMouseTransparent(true);
        datePicker.getEditor().setFocusTraversable(false);
        if (darkTheme) {
            var dpCssUrl = TransfersView.class.getResource("/styles/dark.css");
            if (dpCssUrl != null) {
                final String dpCss = dpCssUrl.toExternalForm();
                datePicker.setOnShowing(ev -> Platform.runLater(() -> {
                    for (Window w : Window.getWindows()) {
                        if (!(w instanceof PopupWindow pw)) continue;
                        var sc = pw.getScene();
                        if (sc == null) continue;
                        var r = sc.getRoot();
                        if (r == null) continue;
                        if (!r.getStyleClass().contains("date-picker-popup")
                                && r.lookup(".date-picker-popup") == null) continue;
                        if (!sc.getStylesheets().contains(dpCss))
                            sc.getStylesheets().add(dpCss);
                    }
                }));
            }
        }

        Locale esCo = Locale.forLanguageTag("es-CO");
        DateTimeFormatter longDateFmt = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", esCo);
        datePicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                if (date == null) {
                    return "";
                }
                LocalDate today = LocalDate.now();
                String prefix;
                if (date.equals(today)) {
                    prefix = "Hoy";
                } else if (date.equals(today.minusDays(1))) {
                    prefix = "Ayer";
                } else {
                    String dow = date.getDayOfWeek().getDisplayName(TextStyle.FULL, esCo);
                    prefix = dow.substring(0, 1).toUpperCase(esCo) + dow.substring(1);
                }
                return prefix + ", " + longDateFmt.format(date);
            }

            @Override
            public LocalDate fromString(String string) {
                return null;
            }
        });

        SVGPath calIcon = new SVGPath();
        calIcon.setContent("M8 2 V6 M16 2 V6 M3 10 H21 M5 4 H19 A2 2 0 0 1 21 6 V20 A2 2 0 0 1 19 22 H5 A2 2 0 0 1 3 20 V6 A2 2 0 0 1 5 4 Z");
        calIcon.getStyleClass().add("modal-date-icon");

        HBox dateRow = new HBox(10, calIcon, datePicker);
        dateRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(datePicker, Priority.ALWAYS);
        dateRow.getStyleClass().add("modal-date-wrap");

        VBox dateSection = new VBox(6, dateLabel, dateRow);
        dateSection.getStyleClass().add("modal-field-section");
        content.getChildren().add(dateSection);

        Text summaryPrefix = new Text("Moverás ");
        summaryPrefix.getStyleClass().add("modal-summary-text");
        Text summaryAmount = new Text("$ 0,00");
        summaryAmount.getStyleClass().add("modal-summary-amount");
        TextFlow summaryLine1 = new TextFlow(summaryPrefix, summaryAmount);
        summaryLine1.getStyleClass().add("modal-summary-line");

        Text summaryDesde = new Text("desde ");
        summaryDesde.getStyleClass().add("modal-summary-text");
        Text summaryFromName = new Text("-");
        summaryFromName.getStyleClass().add("modal-summary-from");
        Text summaryArrow = new Text("  →  ");
        summaryArrow.getStyleClass().add("modal-summary-text");
        Text summaryHacia = new Text("hacia ");
        summaryHacia.getStyleClass().add("modal-summary-text");
        Text summaryToName = new Text("-");
        summaryToName.getStyleClass().add("modal-summary-to");
        TextFlow summaryLine2 = new TextFlow(summaryDesde, summaryFromName, summaryArrow, summaryHacia, summaryToName);
        summaryLine2.getStyleClass().add("modal-summary-line");

        Label summaryTitle = new Label("Resumen de la transferencia");
        summaryTitle.getStyleClass().add("modal-summary-title");

        VBox summaryCard = new VBox(8, summaryTitle, summaryLine1, summaryLine2);
        summaryCard.getStyleClass().add("modal-summary-card");
        summaryCard.setAlignment(Pos.CENTER);
        summaryLine1.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        summaryLine2.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        Runnable refreshSummary = () -> {
            AccountRepository.Account fromAcc = fromAccountCombo.getValue();
            AccountRepository.Account toAcc = toAccountCombo.getValue();

            String digits = amountField.getText() == null ? "" : amountField.getText().replaceAll("\\D", "");
            long cents = 0L;
            if (!digits.isBlank()) {
                try {
                    cents = Long.parseLong(digits);
                } catch (NumberFormatException ignored) {
                    cents = 0L;
                }
            }
            String currency = fromAcc == null ? "COP" : fromAcc.currency();
            summaryAmount.setText(formatMoneySpaced(cents, currency));

            summaryFromName.setText(fromAcc == null ? "-" : (fromAcc.name() == null ? "" : fromAcc.name()));
            summaryToName.setText(toAcc == null ? "-" : (toAcc.name() == null ? "" : toAcc.name()));
        };
        refreshSummary.run();
        fromAccountCombo.valueProperty().addListener((obs, oldV, newV) -> refreshSummary.run());
        toAccountCombo.valueProperty().addListener((obs, oldV, newV) -> refreshSummary.run());
        amountField.textProperty().addListener((obs, oldV, newV) -> refreshSummary.run());

        content.getChildren().add(summaryCard);

        Button cancelBtn = new Button("Cancelar");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        cancelBtn.setMinHeight(44);
        cancelBtn.setOnAction(e -> modal.close());

        SVGPath lockIcon = new SVGPath();
        lockIcon.setContent("M7 11 V8 A5 5 0 0 1 17 8 V11 M6 11 H18 V20 H6 Z");
        lockIcon.getStyleClass().add("modal-lock-icon");
        Label confirmText = new Label("Confirmar transferencia");
        confirmText.getStyleClass().add("modal-btn-primary-text");
        HBox confirmGraphic = new HBox(8, lockIcon, confirmText);
        confirmGraphic.setAlignment(Pos.CENTER);

        Button confirmBtn = new Button();
        confirmBtn.setGraphic(confirmGraphic);
        confirmBtn.getStyleClass().add("modal-btn-confirm");
        confirmBtn.setMinHeight(44);

        confirmBtn.setOnAction(ev -> {
            try {
                AccountRepository.Account fromAcc = fromAccountCombo.getValue();
                AccountRepository.Account toAcc = toAccountCombo.getValue();
                if (fromAcc == null || toAcc == null || fromAcc.id() == null || toAcc.id() == null) {
                    showModalWarning(darkTheme, "Selecciona las cuentas de origen y destino.");
                    return;
                }
                if (fromAcc.id().equals(toAcc.id())) {
                    showModalWarning(darkTheme, "El origen y el destino no pueden ser la misma cuenta.");
                    return;
                }

                String digits = amountField.getText() == null ? "" : amountField.getText().replaceAll("\\D", "");
                long cents = 0L;
                if (!digits.isBlank()) {
                    try {
                        cents = Long.parseLong(digits);
                    } catch (NumberFormatException ignored) {
                        cents = 0L;
                    }
                }
                if (cents <= 0L) {
                    showModalWarning(darkTheme, "Ingresa un monto válido.");
                    return;
                }

                LocalDate d = datePicker.getValue();
                if (d == null) {
                    showModalWarning(darkTheme, "Selecciona una fecha.");
                    return;
                }

                try {
                    if (!"CREDIT".equalsIgnoreCase(AccountRepository.normalizeType(fromAcc.type()))) {
                        long available = accountRepo.computeBalanceCents(userUid, fromAcc.id());
                        if (cents > available) {
                            showModalWarning(darkTheme, "El monto supera el saldo disponible.");
                            return;
                        }
                    }
                } catch (Exception ignored) {
                }

                long occurredAt = d.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
                String note = descField.getText() == null ? null : descField.getText().trim();
                if (note != null && note.isBlank()) {
                    note = null;
                }

                String transferId = transferRepo.create(userUid, fromAcc.id(), toAcc.id(), cents, occurredAt, note);
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                } catch (Exception ignored) {
                }

                if (refreshBalances != null) {
                    refreshBalances.run();
                }
                if (reloadHolder != null && reloadHolder.length > 0 && reloadHolder[0] != null) {
                    reloadHolder[0].run();
                }
                modal.close();
            } catch (Exception ignored) {
            }
        });

        cancelBtn.setMaxWidth(Double.MAX_VALUE);
        confirmBtn.setMaxWidth(Double.MAX_VALUE);

        HBox actionsRow = new HBox(12, cancelBtn, confirmBtn);
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        actionsRow.getStyleClass().add("modal-actions-row");
        HBox.setHgrow(cancelBtn, Priority.ALWAYS);
        HBox.setHgrow(confirmBtn, Priority.ALWAYS);
        VBox footer = new VBox(actionsRow);
        footer.getStyleClass().add("modal-footer");

        VBox rootBox = new VBox(titleBar, content, footer);
        rootBox.getStyleClass().add("modal-root");

        Scene scene = new Scene(rootBox, 480, Region.USE_COMPUTED_SIZE);
        java.net.URL cssUrl = TransfersView.class.getResource("/styles/transfers.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        java.net.URL themeCss = TransfersView.class.getResource(darkTheme ? "/styles/dark.css" : "/styles/light.css");
        if (themeCss != null) {
            scene.getStylesheets().add(themeCss.toExternalForm());
        }

        modal.setScene(scene);
        modal.setWidth(480);

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double maxHeight = Math.min(760, bounds.getHeight() * 0.90);
        modal.setHeight(Math.max(560, maxHeight));
        modal.centerOnScreen();
        modal.showAndWait();
    }

    private static String titleCase(String s) {
        if (s == null) {
            return "";
        }
        String input = s.trim();
        if (input.isBlank()) {
            return "";
        }
        String[] parts = input.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isBlank()) {
                continue;
            }
            String lower = p.toLowerCase(Locale.ROOT);
            String word = lower.length() == 1
                ? lower.toUpperCase(Locale.ROOT)
                : (lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1));
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word);
        }
        return out.toString();
    }

    private static void showModalWarning(boolean darkTheme, String message) {
        Alert a = new Alert(AlertType.WARNING);
        UiDialogs.applyAppTheme(a, darkTheme);
        a.setHeaderText(null);
        a.setContentText(message == null ? "" : message);
        a.showAndWait();
    }

    private static String formatMoneySpaced(long cents, String currencyCode) {
        String sym = DashboardFormatters.currencySymbol(currencyCode);
        return sym + " " + DashboardFormatters.formatSignedUserDecimal(cents);
    }

    private static HBox buildMetricCard(String iconSvgPath, String title, Label valueLabel, Label subLabel, String iconVariant) {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        if (iconSvgPath != null) {
            icon.setContent(iconSvgPath);
        }
        icon.setMouseTransparent(true);
        icon.getStyleClass().add("metric-icon-svg");
        if (iconVariant != null && !iconVariant.isBlank()) {
            icon.getStyleClass().add(iconVariant);
        }

        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().add("metric-icon-bg");
        if (iconVariant != null && !iconVariant.isBlank()) {
            iconBox.getStyleClass().add(iconVariant + "-bg");
        }
        iconBox.setMinWidth(44);
        iconBox.setPrefWidth(44);
        iconBox.setMaxWidth(44);
        iconBox.setMinHeight(44);
        iconBox.setPrefHeight(44);
        iconBox.setMaxHeight(44);
        StackPane.setAlignment(icon, Pos.CENTER);

        Label t = new Label(title);
        t.getStyleClass().add("metric-title");
        valueLabel.getStyleClass().add("metric-value");
        subLabel.getStyleClass().add("metric-sub");
        VBox text = new VBox(2, t, valueLabel, subLabel);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMaxWidth(Double.MAX_VALUE);

        HBox card = new HBox(10, iconBox, text);
        card.getStyleClass().add("metric-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);

        iconBox.prefHeightProperty().bind(card.heightProperty());
        HBox.setHgrow(text, Priority.ALWAYS);

        return card;
    }

    private static String formatMostUsedCount(List<TransferUiRow> rows, String mostUsedName) {
        String key = mostUsedName == null ? "" : mostUsedName.trim();
        if (key.isBlank() || rows == null || rows.isEmpty()) {
            return "";
        }
        int count = 0;
        for (TransferUiRow r : rows) {
            if (r == null) {
                continue;
            }
            if (key.equals(r.fromName()) || key.equals(r.toName())) {
                count++;
            }
        }
        return count + (count == 1 ? " transferencia" : " transferencias");
    }

    private static TransferMetrics computeMetricsFromUiRows(List<TransferUiRow> rows) {
        long totalMovedCents = 0L;
        int totalMovements = 0;

        Map<String, Integer> usage = new HashMap<>();
        if (rows != null) {
            for (TransferUiRow r : rows) {
                if (r == null) {
                    continue;
                }
                totalMovedCents += Math.max(0L, r.amountCents());
                totalMovements++;
                if (r.fromName() != null && !r.fromName().isBlank()) {
                    usage.merge(r.fromName(), 1, Integer::sum);
                }
                if (r.toName() != null && !r.toName().isBlank()) {
                    usage.merge(r.toName(), 1, Integer::sum);
                }
            }
        }

        String mostUsed = "";
        int best = -1;
        for (Map.Entry<String, Integer> e : usage.entrySet()) {
            if (e.getValue() != null && e.getValue() > best) {
                best = e.getValue();
                mostUsed = e.getKey();
            }
        }

        double avg = totalMovements <= 0 ? 0.0 : (double) totalMovedCents / (double) totalMovements;
        return new TransferMetrics(totalMovedCents, totalMovements, mostUsed, avg);
    }

    private static TransferMetrics computeMetrics(
        List<TransferRepository.TransferRow> transferRows,
        Map<String, AccountRepository.Account> accountsById
    ) {
        long totalMovedCents = 0L;
        int totalMovements = 0;

        Map<String, Integer> accountUsage = new HashMap<>();
        if (transferRows != null) {
            for (TransferRepository.TransferRow tr : transferRows) {
                if (tr == null) {
                    continue;
                }
                totalMovedCents += Math.max(0L, tr.amountCents());
                totalMovements++;
                if (tr.fromAccountId() != null && !tr.fromAccountId().isBlank()) {
                    accountUsage.merge(tr.fromAccountId(), 1, Integer::sum);
                }
                if (tr.toAccountId() != null && !tr.toAccountId().isBlank()) {
                    accountUsage.merge(tr.toAccountId(), 1, Integer::sum);
                }
            }
        }

        double avg = totalMovements <= 0 ? 0.0 : (double) totalMovedCents / (double) totalMovements;

        String mostUsedAccountId = null;
        int best = -1;
        for (Map.Entry<String, Integer> e : accountUsage.entrySet()) {
            if (e.getValue() != null && e.getValue() > best) {
                best = e.getValue();
                mostUsedAccountId = e.getKey();
            }
        }

        String mostUsedAccountName = "";
        if (mostUsedAccountId != null && accountsById != null) {
            AccountRepository.Account a = accountsById.get(mostUsedAccountId);
            mostUsedAccountName = a == null || a.name() == null ? "" : a.name();
        }

        return new TransferMetrics(totalMovedCents, totalMovements, mostUsedAccountName, avg);
    }

    private static void renderHistory(VBox historyList, List<TransferUiRow> rows, Consumer<TransferUiRow> onEdit, Consumer<TransferUiRow> onDelete) {
        historyList.getChildren().clear();

        if (rows == null || rows.isEmpty()) {
            return;
        }

        LocalDate lastDate = null;
        for (TransferUiRow r : rows) {
            if (r == null || r.date() == null) {
                continue;
            }
            if (lastDate == null || !lastDate.equals(r.date())) {
                Label group = new Label(formatGroupLabel(r.date()));
                group.getStyleClass().add("section-header");
                historyList.getChildren().add(group);
                lastDate = r.date();
            }
            historyList.getChildren().add(buildTransferItem(r, onEdit, onDelete));
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

    private static Node buildTransferItem(TransferUiRow row, Consumer<TransferUiRow> onEdit, Consumer<TransferUiRow> onDelete) {
        StackPane fromIcon = buildAccountAvatar(row == null ? "BANK" : row.fromTypeKey(), row == null ? null : row.fromColor());
        Label fromName = new Label("");
        fromName.setMaxWidth(Double.MAX_VALUE);
        fromName.setMinWidth(0);
        fromName.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        fromName.getStyleClass().add("account-name");
        Label fromType = new Label("");
        fromType.getStyleClass().add("account-type");
        VBox fromText = new VBox(2, fromName, fromType);
        fromText.setAlignment(Pos.CENTER_LEFT);
        fromText.setMinWidth(0);
        fromText.setMaxWidth(Double.MAX_VALUE);
        HBox fromBox = new HBox(8, fromIcon, fromText);
        fromBox.setAlignment(Pos.CENTER_LEFT);
        fromBox.setMinWidth(200);
        fromBox.setPrefWidth(200);
        fromBox.setMaxWidth(200);
        HBox.setHgrow(fromText, Priority.ALWAYS);

        StackPane toIcon = buildAccountAvatar(row == null ? "BANK" : row.toTypeKey(), row == null ? null : row.toColor());
        Label toName = new Label("");
        toName.setMaxWidth(Double.MAX_VALUE);
        toName.setMinWidth(0);
        toName.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        toName.getStyleClass().add("account-name");
        Label toType = new Label("");
        toType.getStyleClass().add("account-type");
        VBox toText = new VBox(2, toName, toType);
        toText.setAlignment(Pos.CENTER_LEFT);
        toText.setMinWidth(0);
        toText.setMaxWidth(Double.MAX_VALUE);
        HBox toBox = new HBox(8, toIcon, toText);
        toBox.setAlignment(Pos.CENTER_LEFT);
        toBox.setMinWidth(200);
        toBox.setPrefWidth(200);
        toBox.setMaxWidth(200);
        HBox.setHgrow(toText, Priority.ALWAYS);

        Label amount = new Label("");
        amount.getStyleClass().add("transfer-amount");
        SVGPath arrow = new SVGPath();
        arrow.setContent("M0 5 H180 V1 L196 7 L180 13 V9 H0 Z");
        arrow.getStyleClass().add("transfer-arrow");
        Label time = new Label("");
        time.getStyleClass().add("transfer-time");
        VBox centerBox = new VBox(2, amount, arrow, time);
        centerBox.setAlignment(Pos.CENTER);
        centerBox.setMinWidth(0);
        centerBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(centerBox, Priority.ALWAYS);

        Button actionsBtn = new Button("⋮");
        actionsBtn.setFocusTraversable(false);
        actionsBtn.getStyleClass().add("row-actions-btn");

        MenuItem editItem = new MenuItem("Editar");
        editItem.setOnAction(e -> {
            if (onEdit != null) onEdit.accept(row);
        });
        MenuItem deleteItem = new MenuItem("Eliminar");
        deleteItem.setOnAction(e -> {
            if (onDelete != null) onDelete.accept(row);
        });
        ContextMenu menu = new ContextMenu(editItem, deleteItem);
        menu.getStyleClass().add("row-actions-menu");
        actionsBtn.setOnAction(e -> menu.show(actionsBtn, javafx.geometry.Side.BOTTOM, 0, 0));

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.NEVER);
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.NEVER);

        HBox rowLayout = new HBox(24, fromBox, spacer1, centerBox, spacer2, toBox, actionsBtn);
        rowLayout.setAlignment(Pos.CENTER);
        rowLayout.setMinWidth(760);
        HBox.setHgrow(fromBox, Priority.NEVER);
        HBox.setHgrow(toBox, Priority.NEVER);
        HBox.setHgrow(actionsBtn, Priority.NEVER);

        VBox item = new VBox(4, rowLayout);
        item.setMaxWidth(Double.MAX_VALUE);
        item.getStyleClass().add("transfer-item");

        if (row != null) {
            fromName.setText(row.fromName() == null ? "" : row.fromName());
            toName.setText(row.toName() == null ? "" : row.toName());
            fromType.setText(row.fromType() == null ? "" : row.fromType());
            toType.setText(row.toType() == null ? "" : row.toType());
            amount.setText(DashboardFormatters.formatMoney(row.amountCents()));
            time.setText(row.timeText() == null ? "" : row.timeText());
        }

        TranslateTransition arrowMoveIn = new TranslateTransition(Duration.millis(140), arrow);
        arrowMoveIn.setToX(6);
        FadeTransition arrowFadeIn = new FadeTransition(Duration.millis(140), arrow);
        arrowFadeIn.setToValue(1.0);
        ParallelTransition arrowIn = new ParallelTransition(arrowMoveIn, arrowFadeIn);

        TranslateTransition arrowMoveOut = new TranslateTransition(Duration.millis(140), arrow);
        arrowMoveOut.setToX(0);
        FadeTransition arrowFadeOut = new FadeTransition(Duration.millis(140), arrow);
        arrowFadeOut.setToValue(0.9);
        ParallelTransition arrowOut = new ParallelTransition(arrowMoveOut, arrowFadeOut);

        item.setOnMouseEntered(e -> {
            arrowOut.stop();
            arrowIn.playFromStart();
        });
        item.setOnMouseExited(e -> {
            arrowIn.stop();
            arrowOut.playFromStart();
        });
        return item;
    }

    private static StackPane buildAccountAvatar(String typeKey, String storedColor) {
        String hex = AccountStyles.resolveColor(typeKey, storedColor);
        javafx.scene.shape.Circle bg = new javafx.scene.shape.Circle(16);
        try {
            javafx.scene.paint.Color base = javafx.scene.paint.Color.web(hex);
            bg.setFill(base.deriveColor(0, 1.0, 1.0, 0.18));
        } catch (Exception ignored) {
            bg.setFill(javafx.scene.paint.Color.web(AccountStyles.BANK.color(), 0.18));
        }
        FontIcon icon = new FontIcon(AccountStyles.resolveIcon(typeKey));
        icon.setIconSize(13);
        try {
            icon.setIconColor(javafx.scene.paint.Color.web(hex));
        } catch (Exception ignored) {
            icon.setIconColor(javafx.scene.paint.Color.web(AccountStyles.BANK.color()));
        }
        StackPane avatar = new StackPane(bg, icon);
        avatar.setMinSize(32, 32);
        avatar.setPrefSize(32, 32);
        avatar.setMaxSize(32, 32);
        avatar.setAlignment(javafx.geometry.Pos.CENTER);
        return avatar;
    }

    private static String computeInitials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        String s;
        if (parts.length == 1) {
            s = parts[0].length() >= 2 ? parts[0].substring(0, 2) : parts[0];
        } else {
            s = "" + parts[0].charAt(0) + parts[1].charAt(0);
        }
        return s.toUpperCase(Locale.ROOT);
    }

    private static String accountTypeLabel(String type) {
        return AccountStyles.resolveLabel(type);
    }

    private static String avatarToneClass(String name) {
        if (name == null || name.isBlank()) {
            return "avatar-blue";
        }
        int hash = Math.floorMod(name.hashCode(), 6);
        return switch (hash) {
            case 0 -> "avatar-blue";
            case 1 -> "avatar-purple";
            case 2 -> "avatar-rose";
            case 3 -> "avatar-amber";
            case 4 -> "avatar-emerald";
            default -> "avatar-indigo";
        };
    }
}
