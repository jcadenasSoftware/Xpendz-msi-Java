package com.myfinaces.ui;

import com.myfinaces.service.pdf.ReportPdfService;
import java.awt.Desktop;
import java.io.File;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

public final class SummaryPdfDrawer {

    private static final String DRAWER_ID = "summary-pdf";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> STATUS_STATE_CLASSES = List.of(
        "sid-report-status-idle",
        "sid-report-status-loading",
        "sid-report-status-success",
        "sid-report-status-error"
    );
    private static final List<String> STATUS_BADGE_STATE_CLASSES = List.of(
        "sid-report-status-chip-idle",
        "sid-report-status-chip-loading",
        "sid-report-status-chip-success",
        "sid-report-status-chip-error"
    );

    private final SideDrawer drawer;
    private final BooleanSupplier darkTheme;
    private final ReportPdfService reportPdfService;
    private final Supplier<ReportContext> contextSupplier;
    private final List<Button> reportButtons = new ArrayList<>();
    private final Button cancelButton;
    private VBox statusCard;
    private Label statusBadge;
    private Label statusTitle;
    private Label statusMessage;
    private ProgressIndicator statusProgress;
    private Button openFileButton;

    private volatile File lastGeneratedFile;
    private volatile boolean exportRunning;

    public record ReportContext(
        String userUid,
        String userName,
        String currencyCode,
        Integer year,
        String accountId,
        String kind,
        String rootCategoryId,
        Set<String> selectedSubcategoryIds
    ) {
    }

    private enum ReportType {
        TRANSACTIONS,
        ACCOUNTS,
        MONTHLY
    }

    public SummaryPdfDrawer(
        SideDrawer drawer,
        BooleanSupplier darkTheme,
        ReportPdfService reportPdfService,
        Supplier<ReportContext> contextSupplier
    ) {
        this.drawer = Objects.requireNonNull(drawer, "drawer");
        this.darkTheme = darkTheme == null ? () -> false : darkTheme;
        this.reportPdfService = Objects.requireNonNull(reportPdfService, "reportPdfService");
        this.contextSupplier = contextSupplier == null ? () -> null : contextSupplier;

        Label title = new Label("Centro de reportes");
        title.getStyleClass().add("sid-title");

        Label subtitle = new Label("Genera reportes financieros en PDF");
        subtitle.getStyleClass().add("sid-subtitle");
        subtitle.setWrapText(true);

        VBox titleBox = new VBox(2, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button close = drawer.buildCloseButton();

        FontIcon pdfIcon = new FontIcon("fas-file-pdf");
        pdfIcon.setIconSize(18);
        pdfIcon.getStyleClass().addAll("sid-avatar-icon", "sid-pdf-icon");

        StackPane avatar = new StackPane(pdfIcon);
        avatar.getStyleClass().add("sid-avatar");
        avatar.setMinSize(40, 40);
        avatar.setPrefSize(40, 40);
        avatar.setMaxSize(40, 40);

        HBox headerTop = new HBox(12, avatar, titleBox, close);
        headerTop.setAlignment(Pos.CENTER_LEFT);
        headerTop.getStyleClass().add("sid-header-top");

        VBox header = new VBox(10, headerTop);
        header.getStyleClass().add("sid-header-premium");
        header.setMinWidth(0);
        header.setMaxWidth(Double.MAX_VALUE);

        this.statusCard = buildStatusSection();
        Node quickReportsSection = buildQuickReportsSection();
        this.cancelButton = drawer.buildCancelButton("Cerrar");

        VBox scrollContent = new VBox(20, header, quickReportsSection, statusCard);
        scrollContent.getStyleClass().add("sid-root");
        scrollContent.setPadding(new Insets(16, 18, 18, 18));
        scrollContent.setFillWidth(true);
        scrollContent.setMinWidth(0);
        scrollContent.setMaxWidth(Double.MAX_VALUE);

        VBox footer = buildFooter();

        VBox content = new VBox(0, scrollContent, footer);
        content.setFillWidth(true);
        content.setMinWidth(0);
        content.setMaxWidth(Double.MAX_VALUE);

        setIdleState();
        drawer.register(DRAWER_ID, this.darkTheme.getAsBoolean(), content);
    }

    private Node buildQuickReportsSection() {
        Label sectionTitle = new Label("REPORTES RÁPIDOS");
        sectionTitle.getStyleClass().add("sid-quick-section-title");

        Node transactionsCard = buildReportCard(
            "Transacciones",
            "Últimos movimientos financieros de los últimos 30 días",
            "fas-file-alt",
            "sid-report-badge-transactions",
            ReportType.TRANSACTIONS
        );

        Node balanceCard = buildReportCard(
            "Balance de cuentas",
            "Estado actual y distribución de tus cuentas",
            "fas-wallet",
            "sid-report-badge-balance",
            ReportType.ACCOUNTS
        );

        Node monthlyCard = buildReportCard(
            "Resumen mensual",
            "Ingresos, gastos y cumplimiento de presupuesto del mes",
            "fas-chart-bar",
            "sid-report-badge-monthly",
            ReportType.MONTHLY
        );

        VBox section = new VBox(12, sectionTitle, transactionsCard, balanceCard, monthlyCard);
        section.getStyleClass().add("sid-block");
        section.setMinWidth(0);
        section.setMaxWidth(Double.MAX_VALUE);
        return section;
    }

    private Node buildReportCard(String title, String description, String iconLiteral, String badgeStyleClass, ReportType type) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(16);
        icon.getStyleClass().add("sid-report-icon");

        StackPane badge = new StackPane(icon);
        badge.getStyleClass().addAll("sid-report-badge", badgeStyleClass);
        badge.setMinSize(34, 34);
        badge.setPrefSize(34, 34);
        badge.setMaxSize(34, 34);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("sid-card-title");

        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("sid-card-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);

        VBox text = new VBox(3, titleLabel, descLabel);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button generateBtn = new Button("Generar");
        generateBtn.getStyleClass().add("sid-card-btn");
        generateBtn.setMinWidth(88);
        generateBtn.setPrefWidth(88);
        generateBtn.setMaxWidth(88);
        generateBtn.setOnAction(e -> handleGenerate(type, generateBtn));
        reportButtons.add(generateBtn);

        HBox cardContent = new HBox(10, badge, text, generateBtn);
        cardContent.setAlignment(Pos.CENTER_LEFT);
        cardContent.getStyleClass().add("sid-report-card");
        cardContent.setPadding(new Insets(6, 6, 6, 6));
        cardContent.setMinWidth(0);
        cardContent.setMaxWidth(Double.MAX_VALUE);
        return cardContent;
    }

    private VBox buildStatusSection() {
        Label sectionTitle = new Label("Estado de exportación");
        sectionTitle.getStyleClass().add("sid-quick-section-title");

        statusBadge = new Label("Listo");
        statusBadge.getStyleClass().addAll("sid-report-status-chip", "sid-report-status-chip-idle");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox headerRow = new HBox(10, sectionTitle, spacer, statusBadge);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setMinWidth(0);
        headerRow.getStyleClass().add("sid-report-status-header");

        statusTitle = new Label("Listo para generar PDFs");
        statusTitle.getStyleClass().add("sid-card-title");

        Label hint = new Label("Los archivos se generan en segundo plano y la interfaz sigue respondiendo.");
        hint.getStyleClass().add("sid-card-desc");
        hint.setWrapText(true);

        VBox titleBox = new VBox(2, statusTitle, hint);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.setMinWidth(0);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        statusProgress = new ProgressIndicator();
        statusProgress.setPrefSize(20, 20);
        statusProgress.setVisible(false);
        statusProgress.setManaged(false);

        HBox summaryRow = new HBox(10, statusProgress, titleBox);
        summaryRow.setAlignment(Pos.CENTER_LEFT);
        summaryRow.setMinWidth(0);
        summaryRow.getStyleClass().add("sid-report-status-summary");

        statusMessage = new Label("Selecciona un reporte para crear su PDF.");
        statusMessage.getStyleClass().addAll("sid-report-status-message", "sid-report-status-message-box");
        statusMessage.setWrapText(true);
        statusMessage.setMinWidth(0);
        statusMessage.setMaxWidth(Double.MAX_VALUE);

        openFileButton = SideDrawer.buildPrimaryButton("Abrir PDF", "fas-external-link-alt");
        openFileButton.getStyleClass().add("sid-status-action-btn");
        openFileButton.setVisible(false);
        openFileButton.setManaged(false);
        openFileButton.setOnAction(e -> openLastGeneratedFile());
        openFileButton.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(12, headerRow, summaryRow, statusMessage, openFileButton);
        box.getStyleClass().addAll("sid-block", "sid-report-status", "sid-report-status-shell", "sid-report-status-idle");
        box.setPadding(new Insets(14, 14, 14, 14));
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private VBox buildFooter() {
        Region separator = new Region();
        separator.getStyleClass().add("drawer-footer-separator");
        separator.setMinHeight(1);
        separator.setMaxHeight(1);
        HBox.setHgrow(separator, Priority.ALWAYS);

        cancelButton.setMaxWidth(Double.MAX_VALUE);

        VBox footer = new VBox(10, separator, cancelButton);
        footer.getStyleClass().add("drawer-footer");
        footer.setPadding(new Insets(16, 18, 18, 18));
        footer.setFillWidth(true);
        footer.setMinWidth(0);
        footer.setMaxWidth(Double.MAX_VALUE);
        return footer;
    }

    private void handleGenerate(ReportType reportType, Node triggerNode) {
        if (exportRunning) {
            return;
        }

        ReportContext context = contextSupplier.get();
        if (context == null || context.userUid() == null || context.userUid().isBlank()) {
            setErrorState("No hay contexto de usuario", "Abre el módulo Resumen y vuelve a intentarlo.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(titleFor(reportType));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        chooser.setInitialFileName(defaultFileName(reportType, context));

        File target = chooser.showSaveDialog(triggerNode == null || triggerNode.getScene() == null ? null : triggerNode.getScene().getWindow());
        if (target == null) {
            return;
        }

        runExport(reportType, context, target);
    }

    private void runExport(ReportType reportType, ReportContext context, File target) {
        exportRunning = true;
        lastGeneratedFile = null;
        setLoadingState(titleFor(reportType), "Generando el PDF en segundo plano. Puedes seguir usando la aplicación.");

        Task<File> task = new Task<>() {
            @Override
            protected File call() throws Exception {
                return switch (reportType) {
                    case TRANSACTIONS -> reportPdfService.generateTransactionsReport(
                        new ReportPdfService.TransactionsReportRequest(
                            context.userUid(),
                            context.userName(),
                            safeCurrency(context.currencyCode()),
                            null,
                            null,
                            null,
                            Set.of()
                        ),
                        target
                    );
                    case ACCOUNTS -> reportPdfService.generateAccountsReport(
                        new ReportPdfService.AccountsReportRequest(context.userUid(), context.userName()),
                        target
                    );
                    case MONTHLY -> reportPdfService.generateMonthlySummaryReport(
                        new ReportPdfService.MonthlySummaryReportRequest(
                            context.userUid(),
                            context.userName(),
                            safeCurrency(context.currencyCode()),
                            YearMonth.of(context.year() == null ? LocalDate.now().getYear() : context.year(), LocalDate.now().getMonthValue())
                        ),
                        target
                    );
                };
            }
        };

        task.setOnSucceeded(evt -> Platform.runLater(() -> {
            exportRunning = false;
            lastGeneratedFile = task.getValue();
            setSuccessState("PDF generado", "Guardado en: " + lastGeneratedFile.getAbsolutePath());
        }));
        task.setOnFailed(evt -> Platform.runLater(() -> {
            exportRunning = false;
            Throwable ex = task.getException();
            setErrorState("No se pudo generar el PDF", ex == null ? "Error desconocido" : safeMessage(ex.getMessage()));
        }));

        Thread worker = new Thread(task, "pdf-report-" + reportType.name().toLowerCase(Locale.ROOT));
        worker.setDaemon(true);
        worker.start();
    }

    private void openLastGeneratedFile() {
        if (lastGeneratedFile == null || !lastGeneratedFile.exists()) {
            setErrorState("No hay PDF disponible", "Genera un reporte primero para poder abrirlo.");
            return;
        }

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(lastGeneratedFile);
            }
        } catch (Exception ex) {
            setErrorState("No se pudo abrir el PDF", safeMessage(ex.getMessage()));
        }
    }

    private void setIdleState() {
        applyStatusClasses("sid-report-status-idle");
        applyBadgeClasses("sid-report-status-chip-idle");
        statusBadge.setText("Listo");
        statusProgress.setVisible(false);
        statusProgress.setManaged(false);
        statusTitle.setText("Listo para generar PDFs");
        statusMessage.setText("Selecciona un reporte para crear su PDF.");
        openFileButton.setVisible(false);
        openFileButton.setManaged(false);
        updateButtonsDisabled(false);
    }

    private void setLoadingState(String title, String message) {
        applyStatusClasses("sid-report-status-loading");
        applyBadgeClasses("sid-report-status-chip-loading");
        statusBadge.setText("Generando");
        statusProgress.setVisible(true);
        statusProgress.setManaged(true);
        statusTitle.setText(title);
        statusMessage.setText(message);
        openFileButton.setVisible(false);
        openFileButton.setManaged(false);
        updateButtonsDisabled(true);
    }

    private void setSuccessState(String title, String message) {
        applyStatusClasses("sid-report-status-success");
        applyBadgeClasses("sid-report-status-chip-success");
        statusBadge.setText("Generado");
        statusProgress.setVisible(false);
        statusProgress.setManaged(false);
        statusTitle.setText(title);
        statusMessage.setText(message);
        openFileButton.setVisible(true);
        openFileButton.setManaged(true);
        updateButtonsDisabled(false);
    }

    private void setErrorState(String title, String message) {
        applyStatusClasses("sid-report-status-error");
        applyBadgeClasses("sid-report-status-chip-error");
        statusBadge.setText("Error");
        statusProgress.setVisible(false);
        statusProgress.setManaged(false);
        statusTitle.setText(title);
        statusMessage.setText(message);
        openFileButton.setVisible(false);
        openFileButton.setManaged(false);
        updateButtonsDisabled(false);
    }

    private void updateButtonsDisabled(boolean disabled) {
        for (Button button : reportButtons) {
            button.setDisable(disabled);
        }
    }

    private void applyStatusClasses(String stateClass) {
        statusCard.getStyleClass().removeAll(STATUS_STATE_CLASSES);
        if (!statusCard.getStyleClass().contains(stateClass)) {
            statusCard.getStyleClass().add(stateClass);
        }
    }

    private void applyBadgeClasses(String stateClass) {
        statusBadge.getStyleClass().removeAll(STATUS_BADGE_STATE_CLASSES);
        if (!statusBadge.getStyleClass().contains(stateClass)) {
            statusBadge.getStyleClass().add(stateClass);
        }
    }

    private static String titleFor(ReportType reportType) {
        return switch (reportType) {
            case TRANSACTIONS -> "Reporte de transacciones";
            case ACCOUNTS -> "Balance de cuentas";
            case MONTHLY -> "Resumen mensual";
        };
    }

    private static String defaultFileName(ReportType reportType, ReportContext context) {
        String date = LocalDate.now().format(FILE_DATE);
        return switch (reportType) {
            case TRANSACTIONS -> "reporte_transacciones_" + date + ".pdf";
            case ACCOUNTS -> "balance_cuentas_" + date + ".pdf";
            case MONTHLY -> {
                int year = context != null && context.year() != null ? context.year() : LocalDate.now().getYear();
                yield "resumen_mensual_" + YearMonth.of(year, LocalDate.now().getMonth()).toString() + ".pdf";
            }
        };
    }

    private static String safeCurrency(String value) {
        return value == null || value.isBlank() ? "COP" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "Error desconocido" : message;
    }

    public void show() {
        drawer.setDarkTheme(darkTheme.getAsBoolean());
        drawer.show(DRAWER_ID);
    }
}
