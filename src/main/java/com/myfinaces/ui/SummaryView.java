package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.service.pdf.ReportPdfService;
import javafx.application.Platform;
import javafx.event.EventHandler;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.animation.FadeTransition;
import javafx.beans.property.ObjectProperty;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

public final class SummaryView {

    private SummaryView() {
    }

    private record MonthlySummaryParts(
        Node filtersRow,
        Node tablesCard
    ) {
    }

    private record AnalysisParts(
        Node sectionTitle,
        Node filtersRow,
        Node tablesCard,
        Node goalsPane
    ) {
    }

    public static Node buildSummaryView(
        AuthSession session,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        BudgetRepository budgetRepo,
        GoalRepository goalRepo,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        BooleanSupplier darkTheme,
        Runnable refreshBalances,
        SideDrawer sideDrawer
    ) {
        String userUid = session.uid();
        String userName = session.displayName() == null || session.displayName().isBlank() ? session.email() : session.displayName();

        SummaryInsightDrawer insightDrawer = new SummaryInsightDrawer(sideDrawer, txRepo, darkTheme);
        ReportPdfService reportPdfService = new ReportPdfService(txRepo, accountRepo, categoryRepo, budgetRepo, loanRepo, loanPaymentRepo, goalRepo);
        AtomicReference<SummaryPdfDrawer.ReportContext> reportContextRef = new AtomicReference<>();
        SummaryPdfDrawer pdfDrawer = new SummaryPdfDrawer(sideDrawer, darkTheme, reportPdfService, reportContextRef::get);

        VBox root = new VBox(0);
        root.setPadding(new Insets(20));
        root.setMinWidth(0);
        root.getStyleClass().add("summary-root");

        URL themeCss = SummaryView.class.getResource(darkTheme.getAsBoolean() ? "/styles/dark.css" : "/styles/light.css");
        if (themeCss != null) {
            root.getStylesheets().add(themeCss.toExternalForm());
        }

        Node headerCore = buildHeaderContainer();
        SummaryViewSwitcher viewSwitcher = new SummaryViewSwitcher();
        VBox headerWithSwitcher = new VBox(10, headerCore, viewSwitcher.getNode());
        headerWithSwitcher.setMinWidth(0);

        Node headerSection = SummarySectionContainer.buildWithSpacing(headerWithSwitcher, 8);
        Node kpiSection = SummarySectionContainer.buildWithSpacing(SummaryKPISection.build(userUid, txRepo, categoryRepo), 16);
        Node insightsSection = SummarySectionContainer.buildWithSpacing(SummaryInsightsSection.build(userUid, txRepo, categoryRepo), 16);
        Node chartsSection = SummarySectionContainer.buildWithSpacing(SummaryTrendsSection.build(userUid, txRepo, categoryRepo), 16);
        ObjectProperty<SummaryViewSwitcher.ViewMode> viewModeProp = viewSwitcher.selectedViewProperty();
        AnalysisParts analysisParts = buildAnalysisSection(userUid, userName, txRepo, accountRepo, categoryRepo, goalRepo, refreshBalances, viewModeProp, insightDrawer, pdfDrawer, reportContextRef);

        // Create compact versions for different views
        Node compactChartsSection = SummarySectionContainer.buildWithSpacing(buildCompactChartsSection(userUid, txRepo), 12);

        VBox tableView = SummaryTableView.create();
        tableView.getStyleClass().add("summary-table-view");
        SummaryTableView.setContent(tableView, analysisParts.sectionTitle(), analysisParts.filtersRow(), kpiSection, analysisParts.tablesCard(), compactChartsSection);

        VBox analyticsView = SummaryAnalyticsView.create();
        analyticsView.getStyleClass().add("summary-analytics-view");
        SummaryAnalyticsView.setContent(analyticsView, null, chartsSection, insightsSection, null);

        VBox heatmapView = SummaryHeatmapView.create();
        heatmapView.getStyleClass().add("summary-heatmap-view-container");
        SummaryHeatmapView.setContent(heatmapView, userUid, txRepo, accountRepo, categoryRepo, viewModeProp, insightDrawer);

        StackPane centerHost = new StackPane();
        centerHost.getStyleClass().add("summary-center-host");
        centerHost.setMinWidth(0);

        centerHost.getChildren().setAll(tableView);

        viewSwitcher.selectedViewProperty().addListener((o, oldV, newV) -> {
            Node next = switch (newV) {
                case TABLE -> {
                    SummaryTableView.setContent(tableView, analysisParts.sectionTitle(), analysisParts.filtersRow(), kpiSection, analysisParts.tablesCard(), compactChartsSection);
                    yield tableView;
                }
                case ANALYTICS -> {
                    SummaryAnalyticsView.setContent(analyticsView, null, chartsSection, insightsSection, null);
                    yield analyticsView;
                }
                case HEATMAP -> {
                    SummaryHeatmapView.setContent(heatmapView, userUid, txRepo, accountRepo, categoryRepo, viewModeProp, insightDrawer);
                    yield heatmapView;
                }
            };

            if (!centerHost.getChildren().isEmpty() && centerHost.getChildren().get(0) == next) {
                return;
            }

            centerHost.setOpacity(0);
            centerHost.setScaleX(0.98);
            centerHost.setScaleY(0.98);
            centerHost.getChildren().setAll(next);
            FadeTransition ft = new FadeTransition(Duration.millis(200), centerHost);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();

            javafx.animation.ScaleTransition st = new javafx.animation.ScaleTransition(Duration.millis(200), centerHost);
            st.setFromX(0.98);
            st.setToX(1.0);
            st.setFromY(0.98);
            st.setToY(1.0);
            st.play();
        });

        // Trigger initial render to ensure data loads on first display
        Platform.runLater(() -> {
            SummaryTableView.setContent(tableView, analysisParts.sectionTitle(), analysisParts.filtersRow(), kpiSection, analysisParts.tablesCard(), compactChartsSection);
            centerHost.getChildren().setAll(tableView);
        });

        Node body = SummaryGridLayout.build(headerSection, centerHost);
        if (body instanceof VBox vbox) {
            vbox.setMinWidth(0);
        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("summary-module-scroll");

        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);

        return root;
    }

    private static BorderPane buildHeaderContainer() {
        Label title = new Label("Resumen financiero");
        title.getStyleClass().add("tx-header-title");

        Label subtitle = new Label("Centro financiero y analítica (en preparación)");
        subtitle.getStyleClass().add("text-secondary");
        subtitle.setWrapText(true);

        VBox text = new VBox(4, title, subtitle);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("tx-header");
        header.setCenter(text);
        header.setPadding(new Insets(4, 2, 0, 2));
        return header;
    }

    private static Node buildChartsSection(String userUid, TransactionRepository txRepo) {
        return SummaryChartsSection.build(userUid, txRepo);
    }

    private static Node buildCompactChartsSection(String userUid, TransactionRepository txRepo) {
        Node mainChartCard = SummaryChartCard.build(
            "Tendencia",
            ExpensesIncomeChart.buildBars(userUid, txRepo)
        );
        return mainChartCard;
    }

    private static Node buildCompactSummarySection(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo,
        ObjectProperty<SummaryViewSwitcher.ViewMode> viewModeProp,
        SummaryInsightDrawer insightDrawer
    ) {
        Label sectionTitle = new Label("Resumen Rápido");
        sectionTitle.getStyleClass().add("account-name");

        Node topCategories = buildTopCategoriesCard(userUid, txRepo, categoryRepo);
        
        VBox section = new VBox(12, sectionTitle, topCategories);
        section.setMinWidth(0);
        return section;
    }

    private static Node buildTopCategoriesCard(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo
    ) {
        int year = java.time.LocalDate.now().getYear();
        String kind = "EXPENSE";
        
        Map<String, Long> totalsByRoot = new HashMap<>();
        Map<String, String> rootNameById = new HashMap<>();

        try {
            for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                if (r == null || r.id() == null) {
                    continue;
                }
                if (r.kind() == null || !r.kind().equalsIgnoreCase(kind)) {
                    continue;
                }
                rootNameById.put(r.id(), r.name());
                totalsByRoot.put(r.id(), 0L);
            }
        } catch (Exception ignored) {
        }

        try {
            for (TransactionRepository.MonthlyCategoryTotal row : txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, kind)) {
                if (row == null) {
                    continue;
                }
                long v = row.totalAmountCents();
                totalsByRoot.merge(row.rootCategoryId(), v, Long::sum);
                if (row.rootCategoryName() != null && !row.rootCategoryName().isBlank()) {
                    rootNameById.putIfAbsent(row.rootCategoryId(), row.rootCategoryName());
                }
            }
        } catch (Exception ignored) {
        }

        List<Map.Entry<String, Long>> sorted = totalsByRoot.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
            .limit(5)
            .toList();

        VBox content = new VBox(8);
        content.setMinWidth(0);
        
        for (Map.Entry<String, Long> entry : sorted) {
            String name = rootNameById.getOrDefault(entry.getKey(), "Desconocido");
            long cents = entry.getValue();
            String formatted = DashboardFormatters.formatMoney(cents, "COP");
            
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);
            
            Label nameLabel = new Label(name);
            nameLabel.getStyleClass().add("text-secondary");
            nameLabel.setMinWidth(0);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);
            
            Label valueLabel = new Label(formatted);
            valueLabel.getStyleClass().add("account-name");
            
            row.getChildren().addAll(nameLabel, valueLabel);
            content.getChildren().add(row);
        }

        if (sorted.isEmpty()) {
            Label empty = new Label("Sin datos este año");
            empty.getStyleClass().add("text-secondary");
            content.getChildren().add(empty);
        }

        return content;
    }

    private static AnalysisParts buildAnalysisSection(
        String userUid,
        String userName,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        GoalRepository goalRepo,
        Runnable refreshBalances,
        ObjectProperty<SummaryViewSwitcher.ViewMode> viewModeProp,
        SummaryInsightDrawer insightDrawer,
        SummaryPdfDrawer pdfDrawer,
        AtomicReference<SummaryPdfDrawer.ReportContext> reportContextRef
    ) {
        Label sectionTitle = new Label("Análisis");
        sectionTitle.getStyleClass().add("account-name");

        MonthlySummaryParts parts = buildMonthlySummaryPane(userUid, userName, txRepo, accountRepo, categoryRepo, viewModeProp, insightDrawer, pdfDrawer, reportContextRef);
        Node goalsPane = buildGoalsPane(userUid, accountRepo, goalRepo, refreshBalances);

        return new AnalysisParts(sectionTitle, parts.filtersRow(), parts.tablesCard(), goalsPane);
    }

    private static MonthlySummaryParts buildMonthlySummaryPane(
        String userUid,
        String userName,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        ObjectProperty<SummaryViewSwitcher.ViewMode> viewModeProp,
        SummaryInsightDrawer insightDrawer,
        SummaryPdfDrawer pdfDrawer,
        AtomicReference<SummaryPdfDrawer.ReportContext> reportContextRef
    ) {
        ComboBox<Integer> year = new ComboBox<>();
        ComboBox<String> kind = new ComboBox<>();
        ComboBox<String> view = new ComboBox<>();
        ComboBox<AccountRepository.Account> account = new ComboBox<>();
        ComboBox<CategoryRepository.Category> rootCategory = new ComboBox<>();
        MenuButton subCategory = new MenuButton("(Todas las subcategorías)");

        Set<String> selectedSubIds = new HashSet<>();

        int currentYear = LocalDate.now().getYear();
        for (int y = currentYear; y >= currentYear - 5; y--) {
            year.getItems().add(y);
        }
        year.getSelectionModel().selectFirst();

        kind.getItems().addAll("Gastos", "Ingresos");
        kind.getSelectionModel().select("Gastos");

        view.getItems().addAll("Categorías", "Subcategorías");
        view.getSelectionModel().select("Categorías");

        try {
            account.getItems().add(null);
            account.getItems().addAll(accountRepo.list(userUid));
            account.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        try {
            rootCategory.getItems().add(null);
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                if (r != null && r.kind() != null && r.kind().equalsIgnoreCase(k)) {
                    rootCategory.getItems().add(r);
                }
            }
            rootCategory.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las categorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshRootCategories = () -> {
            try {
                CategoryRepository.Category selected = rootCategory.getValue();
                rootCategory.getItems().clear();
                rootCategory.getItems().add(null);

                String kindLabel = kind.getValue();
                String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
                for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                    if (r != null && r.kind() != null && r.kind().equalsIgnoreCase(k)) {
                        rootCategory.getItems().add(r);
                    }
                }

                if (selected == null) {
                    rootCategory.getSelectionModel().selectFirst();
                    return;
                }
                for (CategoryRepository.Category r : rootCategory.getItems()) {
                    if (r != null && selected.id().equals(r.id())) {
                        rootCategory.getSelectionModel().select(r);
                        return;
                    }
                }
                rootCategory.getSelectionModel().selectFirst();
            } catch (Exception ignored) {
            }
        };

        AtomicReference<Runnable> refreshAccountsForSummaryRef = new AtomicReference<>(null);
        AtomicReference<Runnable> refreshSummaryRef = new AtomicReference<>(null);

        Runnable refreshSubcatsSummary = () -> {
            boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
            CategoryRepository.Category root = rootCategory.getValue();

            subCategory.getItems().clear();
            selectedSubIds.clear();
            subCategory.setText("(Todas las subcategorías)");

            if (!bySub || root == null) {
                subCategory.setDisable(true);
                return;
            }

            subCategory.setDisable(false);

            MenuItem all = new MenuItem("(Todas las subcategorías)");
            all.setOnAction(ev -> {
                selectedSubIds.clear();
                subCategory.setText("(Todas las subcategorías)");
                for (MenuItem mi : subCategory.getItems()) {
                    if (mi instanceof CheckMenuItem cmi) {
                        cmi.setSelected(false);
                    }
                }
                Runnable ra = refreshAccountsForSummaryRef.get();
                if (ra != null) {
                    ra.run();
                }
                Runnable rs = refreshSummaryRef.get();
                if (rs != null) {
                    rs.run();
                }
            });
            subCategory.getItems().add(all);

            try {
                List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, root.id());
                children.sort((c1, c2) -> c1.name().compareToIgnoreCase(c2.name()));
                for (CategoryRepository.Category c : children) {
                    CheckMenuItem item = new CheckMenuItem(c.name());
                    item.getStyleClass().add("summary-menu-strong");
                    item.setOnAction(ev -> {
                        if (item.isSelected()) {
                            selectedSubIds.add(c.id());
                        } else {
                            selectedSubIds.remove(c.id());
                        }

                        if (selectedSubIds.isEmpty()) {
                            subCategory.setText("(Todas las subcategorías)");
                        } else {
                            subCategory.setText(selectedSubIds.size() + " seleccionadas");
                        }

                        Runnable ra = refreshAccountsForSummaryRef.get();
                        if (ra != null) {
                            ra.run();
                        }
                        Runnable rs = refreshSummaryRef.get();
                        if (rs != null) {
                            rs.run();
                        }
                    });
                    subCategory.getItems().add(item);
                }
            } catch (Exception ignored) {
            }
        };
        refreshSubcatsSummary.run();

        Runnable refreshAccountsForSummary = () -> {
            try {
                AccountRepository.Account selected = account.getValue();

                account.getItems().clear();
                account.getItems().add(null);

                boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
                Integer y = year.getValue();
                String kindLabel = kind.getValue();
                String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";

                List<AccountRepository.Account> allAccounts;
                try {
                    allAccounts = accountRepo.list(userUid);
                } catch (Exception ignored) {
                    allAccounts = List.of();
                }

                if (bySub && !selectedSubIds.isEmpty()) {
                    Set<String> idSet = new HashSet<>();
                    for (String subId : selectedSubIds) {
                        try {
                            idSet.addAll(txRepo.listAccountIdsUsedInCategory(userUid, y == null ? currentYear : y, k, subId));
                        } catch (Exception ignored) {
                        }
                    }
                    for (AccountRepository.Account a : allAccounts) {
                        if (a != null && idSet.contains(a.id())) {
                            account.getItems().add(a);
                        }
                    }
                } else {
                    account.getItems().addAll(allAccounts);
                }

                if (selected == null) {
                    account.getSelectionModel().selectFirst();
                    return;
                }
                for (AccountRepository.Account a : account.getItems()) {
                    if (a != null && selected.id().equals(a.id())) {
                        account.getSelectionModel().select(a);
                        return;
                    }
                }
                account.getSelectionModel().selectFirst();
            } catch (Exception ignored) {
            }
        };
        refreshAccountsForSummaryRef.set(refreshAccountsForSummary);

        Label fYear = new Label("Año");
        fYear.getStyleClass().add("account-name");
        Label fKind = new Label("Tipo");
        fKind.getStyleClass().add("account-name");
        Label fView = new Label("Vista");
        fView.getStyleClass().add("account-name");
        Label fRoot = new Label("Categoría");
        fRoot.getStyleClass().add("account-name");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("account-name");
        Label fAccount = new Label("Cuenta");
        fAccount.getStyleClass().add("account-name");

        year.setMinWidth(120);
        kind.setMinWidth(160);
        view.setMinWidth(180);
        rootCategory.setMinWidth(240);
        subCategory.setMinWidth(260);
        account.setMinWidth(260);

        year.setMaxWidth(Double.MAX_VALUE);
        kind.setMaxWidth(Double.MAX_VALUE);
        view.setMaxWidth(Double.MAX_VALUE);
        rootCategory.setMaxWidth(Double.MAX_VALUE);
        subCategory.setMaxWidth(Double.MAX_VALUE);
        account.setMaxWidth(Double.MAX_VALUE);

        year.setPromptText("Año");
        kind.setPromptText("Tipo");
        view.setPromptText("Vista");
        rootCategory.setPromptText("Categoría");
        account.setPromptText("Cuenta");
        subCategory.setTooltip(new Tooltip("Subcategoría"));

        VBox pYear = new VBox(6, fYear, year);
        pYear.setAlignment(Pos.CENTER_LEFT);
        pYear.getStyleClass().add("summary-filter-field");
        VBox pKind = new VBox(6, fKind, kind);
        pKind.setAlignment(Pos.CENTER_LEFT);
        pKind.getStyleClass().add("summary-filter-field");
        VBox pView = new VBox(6, fView, view);
        pView.setAlignment(Pos.CENTER_LEFT);
        pView.getStyleClass().add("summary-filter-field");
        VBox pRoot = new VBox(6, fRoot, rootCategory);
        pRoot.setAlignment(Pos.CENTER_LEFT);
        pRoot.getStyleClass().add("summary-filter-field");
        VBox pSub = new VBox(6, fSub, subCategory);
        pSub.setAlignment(Pos.CENTER_LEFT);
        pSub.getStyleClass().add("summary-filter-field");
        VBox pAccount = new VBox(6, fAccount, account);
        pAccount.setAlignment(Pos.CENTER_LEFT);
        pAccount.getStyleClass().add("summary-filter-field");

        year.getStyleClass().add("combo-box-custom");
        kind.getStyleClass().add("combo-box-custom");
        view.getStyleClass().add("combo-box-custom");
        rootCategory.getStyleClass().add("combo-box-custom");
        account.getStyleClass().add("combo-box-custom");
        subCategory.getStyleClass().add("combo-box-custom");

        GridPane filtersGrid = new GridPane();
        filtersGrid.getStyleClass().add("summary-filters-row");
        filtersGrid.setHgap(14);
        filtersGrid.setVgap(10);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        ColumnConstraints c3 = new ColumnConstraints();
        c3.setHgrow(Priority.ALWAYS);
        filtersGrid.getColumnConstraints().addAll(c1, c2, c3);

        filtersGrid.add(pYear, 0, 0);
        filtersGrid.add(pKind, 1, 0);
        filtersGrid.add(pView, 2, 0);
        filtersGrid.add(pRoot, 0, 1);
        filtersGrid.add(pSub, 1, 1);
        filtersGrid.add(pAccount, 2, 1);

        VBox filtersCard = new VBox(10, filtersGrid);
        filtersCard.getStyleClass().add("summary-filters-card");
        filtersCard.setPadding(new Insets(12));
        filtersCard.setMinWidth(0);
        HBox.setHgrow(filtersCard, Priority.ALWAYS);

        SummaryFinancialTable.Parts tableParts = SummaryFinancialTable.create();
        GridPane fixedTable = tableParts.fixedTable();
        GridPane monthsTable = tableParts.monthsTable();

        SummaryFinancialTable.applyDefaultColumnConstraints(fixedTable);

        Node tablesCard = SummaryFinancialTable.wrapAsCard(tableParts.host());
        VBox.setVgrow(tablesCard, Priority.ALWAYS);

        StackPane contentHost = new StackPane();
        contentHost.setMinWidth(0);
        VBox.setVgrow(contentHost, Priority.ALWAYS);

        AtomicReference<List<List<String>>> exportRowsRef = new AtomicReference<>(List.of());
        Map<String, Boolean> expandedAccountsBySubId = new HashMap<>();

        Runnable refreshSummary = () -> {
            fixedTable.getChildren().clear();
            monthsTable.getChildren().clear();

            Integer y = year.getValue();
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            boolean bySubcategory = "Subcategorías".equalsIgnoreCase(view.getValue());
            int currentMonth = LocalDate.now().getMonthValue();
            AccountRepository.Account a = account.getValue();
            String accountId = a == null ? null : a.id();
            String currencyCode = a == null || a.currency() == null || a.currency().isBlank() ? "COP" : a.currency();
            CategoryRepository.Category rootFilter = rootCategory.getValue();
            SummaryPdfDrawer.ReportContext reportContext = new SummaryPdfDrawer.ReportContext(
                userUid,
                userName,
                currencyCode,
                y,
                accountId,
                k,
                rootFilter == null ? null : rootFilter.id(),
                new HashSet<>(selectedSubIds)
            );
            reportContextRef.set(reportContext);

            if (viewModeProp != null && viewModeProp.get() == SummaryViewSwitcher.ViewMode.HEATMAP) {
                Node heatmap = SummaryHeatmapView.build(userUid, txRepo, y, k, a, currencyCode, (sel) -> {
                    if (sel == null || insightDrawer == null) {
                        return;
                    }

                    List<String> ids = null;
                    try {
                        ids = new ArrayList<>();
                        ids.add(sel.categoryId());
                        for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, sel.categoryId())) {
                            if (c != null && c.id() != null) {
                                ids.add(c.id());
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    java.util.Locale esCo = java.util.Locale.forLanguageTag("es-CO");
                    String monthLabel = java.time.Month.of(sel.month()).getDisplayName(java.time.format.TextStyle.FULL, esCo);
                    String monthCap = monthLabel == null || monthLabel.isBlank() ? "" : (monthLabel.substring(0, 1).toUpperCase(esCo) + monthLabel.substring(1));
                    String subtitle = monthCap + " " + (y == null ? String.valueOf(currentYear) : String.valueOf(y)) + " · " + ("INCOME".equalsIgnoreCase(k) ? "Ingresos" : "Gastos");
                    long totalCents = sel.cents();
                    long avgCents = 0L;
                    int count = 0;
                    for (int mm = 1; mm <= 12; mm++) {
                        if (sel.monthsCents()[mm] != 0) {
                            avgCents += sel.monthsCents()[mm];
                            count++;
                        }
                    }
                    avgCents = count == 0 ? 0L : (avgCents / count);

                    insightDrawer.show(new SummaryInsightDrawer.Context(
                        userUid,
                        sel.categoryName(),
                        subtitle,
                        y == null ? currentYear : y,
                        k,
                        currencyCode,
                        accountId,
                        sel.categoryId(),
                        ids,
                        sel.month(),
                        sel.monthsCents(),
                        totalCents,
                        avgCents
                    ));
                });
                contentHost.getChildren().setAll(heatmap);
                return;
            }

            contentHost.getChildren().setAll(tablesCard);

            int monthsElapsed;
            int selectedYear = y == null ? currentYear : y;
            if (selectedYear == currentYear) {
                monthsElapsed = Math.max(1, Math.min(12, currentMonth - 1));
            } else {
                monthsElapsed = 12;
            }

            List<List<String>> exportRows = new ArrayList<>();

            Set<String> subFilterIds = bySubcategory ? new HashSet<>(selectedSubIds) : Set.of();

            List<CategoryRepository.Category> roots;
            try {
                roots = new ArrayList<>();
                for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                    if (r != null && r.kind() != null && r.kind().equalsIgnoreCase(k)) {
                        roots.add(r);
                    }
                }
            } catch (Exception ignored) {
                roots = List.of();
            }

            if (bySubcategory) {
                Map<String, Map<String, String>> subNameByRoot = new HashMap<>();
                Map<String, Map<String, long[]>> byRootSub = new HashMap<>();
                Map<String, Map<String, Map<String, long[]>>> byRootSubAccount = new HashMap<>();
                Map<String, String> accountNameById = new HashMap<>();
                for (CategoryRepository.Category r : roots) {
                    byRootSub.put(r.id(), new HashMap<>());
                    subNameByRoot.put(r.id(), new HashMap<>());
                    try {
                        List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, r.id());
                        for (CategoryRepository.Category c : children) {
                            subNameByRoot.get(r.id()).put(c.id(), c.name());
                            byRootSub.get(r.id()).put(c.id(), new long[13]);
                            byRootSubAccount.computeIfAbsent(r.id(), __ -> new HashMap<>()).put(c.id(), new HashMap<>());
                        }
                    } catch (Exception ignored) {
                    }
                    subNameByRoot.get(r.id()).put(r.id() + ":NONE", "(Sin subcategoría)");
                    byRootSub.get(r.id()).put(r.id() + ":NONE", new long[13]);
                    byRootSubAccount.computeIfAbsent(r.id(), __ -> new HashMap<>()).put(r.id() + ":NONE", new HashMap<>());
                }

                try {
                    if (accountId == null) {
                        List<TransactionRepository.MonthlyCategoryDetailAccountTotal> rows = txRepo.listMonthlyTotalsBySubcategoryAndAccount(userUid, selectedYear, k);
                        for (TransactionRepository.MonthlyCategoryDetailAccountTotal row : rows) {
                            Map<String, long[]> subs = byRootSub.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            long[] months = subs.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                months[m] += row.totalAmountCents();
                            }

                            Map<String, Map<String, long[]>> subsByAcc = byRootSubAccount
                                .computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            Map<String, long[]> accs = subsByAcc.computeIfAbsent(row.categoryId(), __ -> new HashMap<>());
                            long[] accMonths = accs.computeIfAbsent(row.accountId(), __ -> new long[13]);
                            if (m >= 1 && m <= 12) {
                                accMonths[m] += row.totalAmountCents();
                            }

                            subNameByRoot.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>())
                                .putIfAbsent(row.categoryId(), row.categoryName());
                            if (row.accountId() != null && row.accountName() != null) {
                                accountNameById.putIfAbsent(row.accountId(), row.accountName());
                            }
                        }
                    } else {
                        List<TransactionRepository.MonthlyCategoryDetailTotal> rows = txRepo.listMonthlyTotalsBySubcategory(userUid, accountId, selectedYear, k);
                        for (TransactionRepository.MonthlyCategoryDetailTotal row : rows) {
                            Map<String, long[]> subs = byRootSub.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            long[] months = subs.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                months[m] = row.totalAmountCents();
                            }
                            subNameByRoot.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>())
                                .putIfAbsent(row.categoryId(), row.categoryName());
                        }
                    }
                } catch (Exception ignored) {
                }

                String[] monthNames = new String[] {
                    "DESCRIPCIÓN",
                    "ENERO",
                    "FEBRERO",
                    "MARZO",
                    "ABRIL",
                    "MAYO",
                    "JUNIO",
                    "JULIO",
                    "AGOSTO",
                    "SEPTIEMBRE",
                    "OCTUBRE",
                    "NOVIEMBRE",
                    "DICIEMBRE",
                    "TOTAL",
                    "PROMEDIO"
                };

                exportRows.add(List.of(monthNames));

                for (int col = 0; col < monthNames.length; col++) {
                    Label h = SummaryTableCell.header(monthNames[col], col == currentMonth);
                    if (col == 0) {
                        fixedTable.add(h, 0, 0);
                    } else if (col >= 1 && col <= 12) {
                        monthsTable.add(h, col - 1, 0);
                    } else if (col == 13) {
                        fixedTable.add(h, 1, 0);
                    } else if (col == 14) {
                        monthsTable.add(h, 12, 0);
                    }
                }

                long[] totalByMonth = new long[13];
                int rowIdx = 1;
                long grandTotal = 0;
                for (CategoryRepository.Category r : roots) {
                    if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                        continue;
                    }

                    org.kordamp.ikonli.Ikon icon = "EXPENSE".equalsIgnoreCase(r.kind())
                        ? org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.SHOPPING_CART
                        : org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.WALLET;
                    Node rootName = SummaryTableCell.categoryWithIcon(r.name(), true, false, icon);
                    String zebra = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                    rootName.getStyleClass().add(zebra);
                    fixedTable.add(rootName, 0, rowIdx);

                    if (insightDrawer != null) {
                        long[] monthsForDrawer = new long[13];
                        try {
                            for (long[] mm : byRootSub.getOrDefault(r.id(), Map.of()).values()) {
                                if (mm == null || mm.length < 13) {
                                    continue;
                                }
                                for (int m = 1; m <= 12; m++) {
                                    monthsForDrawer[m] += mm[m];
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        long totalForDrawer = 0L;
                        for (int m = 1; m <= 12; m++) {
                            totalForDrawer += monthsForDrawer[m];
                        }
                        long avgForDrawer = monthsElapsed <= 0 ? 0L : (totalForDrawer / monthsElapsed);

                        final long totalForDrawerFinal = totalForDrawer;
                        final long avgForDrawerFinal = avgForDrawer;
                        final long[] monthsForDrawerFinal = monthsForDrawer;
                        rootName.setOnMouseClicked(ev -> {
                            if (ev == null) {
                                return;
                            }

                            List<String> ids = null;
                            try {
                                ids = new ArrayList<>();
                                ids.add(r.id());
                                for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, r.id())) {
                                    if (c != null && c.id() != null) {
                                        ids.add(c.id());
                                    }
                                }
                            } catch (Exception ignored) {
                            }

                            String subtitle = (y == null ? String.valueOf(currentYear) : String.valueOf(y)) + " · " + ("INCOME".equalsIgnoreCase(k) ? "Ingresos" : "Gastos");
                            insightDrawer.show(new SummaryInsightDrawer.Context(
                                userUid,
                                r.name(),
                                subtitle,
                                y == null ? currentYear : y,
                                k,
                                currencyCode,
                                accountId,
                                r.id(),
                                ids,
                                null,
                                monthsForDrawerFinal,
                                totalForDrawerFinal,
                                avgForDrawerFinal
                            ));
                        });
                    }
                    for (int m = 1; m <= 12; m++) {
                        Label v = new Label(" ");
                        v.getStyleClass().add(zebra);
                        if (m == currentMonth) {
                            v.getStyleClass().add("summary-current-month");
                        }
                        monthsTable.add(v, m - 1, rowIdx);
                    }
                    Label rootTotalCell = new Label(" ");
                    rootTotalCell.getStyleClass().add(zebra);
                    rootTotalCell.getStyleClass().add("summary-total-col");
                    fixedTable.add(rootTotalCell, 1, rowIdx);
                    Label rootAvgCell = new Label(" ");
                    rootAvgCell.getStyleClass().add(zebra);
                    rootAvgCell.getStyleClass().add("summary-avg-col");
                    monthsTable.add(rootAvgCell, 12, rowIdx);

                    rowIdx++;

                    List<String> keys = new ArrayList<>(subNameByRoot.getOrDefault(r.id(), Map.of()).keySet());
                    keys.sort((a1, a2) -> {
                        String n1 = subNameByRoot.get(r.id()).getOrDefault(a1, a1);
                        String n2 = subNameByRoot.get(r.id()).getOrDefault(a2, a2);
                        return n1.compareToIgnoreCase(n2);
                    });

                    for (String subId : keys) {
                        if (!subFilterIds.isEmpty() && !subFilterIds.contains(subId)) {
                            continue;
                        }
                        long[] months = byRootSub.get(r.id()).getOrDefault(subId, new long[13]);
                        boolean any = false;
                        for (int m = 1; m <= 12; m++) {
                            if (months[m] != 0) {
                                any = true;
                                break;
                            }
                        }
                        if (!any) {
                            continue;
                        }

                        String subLabel = subNameByRoot.get(r.id()).getOrDefault(subId, subId);
                        boolean canToggleAccounts = accountId == null;
                        boolean expanded = expandedAccountsBySubId.getOrDefault(subId, false);
                        FontIcon chevronIcon = null;
                        if (canToggleAccounts) {
                            chevronIcon = new FontIcon(expanded ? FontAwesomeSolid.CHEVRON_DOWN : FontAwesomeSolid.CHEVRON_RIGHT);
                            chevronIcon.setIconSize(10);
                            chevronIcon.getStyleClass().add("summary-sub-chevron");
                        }
                        Label nameLabel = new Label(subLabel);
                        nameLabel.getStyleClass().add("text-secondary");
                        nameLabel.getStyleClass().add("summary-sub-name");
                        nameLabel.getStyleClass().add("summary-sub-name-inline");
                        nameLabel.getStyleClass().add("summary-cell-strong");
                        nameLabel.setMaxWidth(320);
                        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                        Tooltip.install(nameLabel, new Tooltip(subLabel));

                        Region indent = new Region();
                        indent.setMinWidth(14);
                        indent.setPrefWidth(14);

                        HBox name = chevronIcon == null
                            ? new HBox(8, indent, nameLabel)
                            : new HBox(8, indent, chevronIcon, nameLabel);
                        name.setAlignment(Pos.CENTER_LEFT);
                        String zebraSub = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                        name.getStyleClass().add(zebraSub);
                        fixedTable.add(name, 0, rowIdx);

                        long rowTotal = 0;
                        List<Label> monthCells = new ArrayList<>();
                        for (int m = 1; m <= 12; m++) {
                            totalByMonth[m] += months[m];
                            rowTotal += months[m];
                            Label v = SummaryTableCell.amount(DashboardFormatters.formatMoney(months[m], currencyCode), m == currentMonth);
                            v.getStyleClass().add(zebraSub);
                            monthsTable.add(v, m - 1, rowIdx);
                            monthCells.add(v);
                        }

                        grandTotal += rowTotal;
                        Label totalCell = SummaryTableCell.amount(DashboardFormatters.formatMoney(rowTotal, currencyCode), false);
                        totalCell.getStyleClass().addAll("summary-total-col", zebraSub);
                        fixedTable.add(totalCell, 1, rowIdx);

                        long avgBase = rowTotal;
                        if (selectedYear == currentYear) {
                            long prevSum = 0;
                            for (int m = 1; m < currentMonth; m++) {
                                prevSum += months[m];
                            }
                            avgBase = prevSum;
                        }
                        long avgCents = monthsElapsed <= 0 ? 0 : (avgBase / monthsElapsed);
                        Label avgCell = SummaryTableCell.amount(DashboardFormatters.formatMoney(avgCents, currencyCode), false);
                        avgCell.getStyleClass().addAll("summary-avg-col", zebraSub);
                        monthsTable.add(avgCell, 12, rowIdx);

                        if (insightDrawer != null) {
                            String subtitleBase = (y == null ? String.valueOf(currentYear) : String.valueOf(y)) + " · " + ("INCOME".equalsIgnoreCase(k) ? "Ingresos" : "Gastos");

                            final long rowTotalFinal = rowTotal;
                            final long avgCentsFinal = avgCents;

                            name.setOnMouseClicked(ev -> {
                                if (ev != null && ev.getClickCount() >= 2) {
                                    insightDrawer.show(new SummaryInsightDrawer.Context(
                                        userUid,
                                        subLabel,
                                        subtitleBase,
                                        y == null ? currentYear : y,
                                        k,
                                        currencyCode,
                                        accountId,
                                        subId,
                                        null,
                                        null,
                                        months,
                                        rowTotalFinal,
                                        avgCentsFinal
                                    ));
                                }
                            });

                            for (int i = 0; i < monthCells.size(); i++) {
                                int monthNum = i + 1;
                                Label cell = monthCells.get(i);
                                cell.setOnMouseClicked(ev -> {
                                    java.util.Locale esCo = java.util.Locale.forLanguageTag("es-CO");
                                    String ml = java.time.Month.of(monthNum).getDisplayName(java.time.format.TextStyle.FULL, esCo);
                                    String mc = ml == null || ml.isBlank() ? "" : (ml.substring(0, 1).toUpperCase(esCo) + ml.substring(1));
                                    String subtitle = mc + " " + (y == null ? String.valueOf(currentYear) : String.valueOf(y)) + " · " + ("INCOME".equalsIgnoreCase(k) ? "Ingresos" : "Gastos");
                                    insightDrawer.show(new SummaryInsightDrawer.Context(
                                        userUid,
                                        subLabel,
                                        subtitle,
                                        y == null ? currentYear : y,
                                        k,
                                        currencyCode,
                                        accountId,
                                        subId,
                                        null,
                                        monthNum,
                                        months,
                                        months[monthNum],
                                        avgCentsFinal
                                    ));
                                });
                            }

                            totalCell.setOnMouseClicked(ev -> {
                                insightDrawer.show(new SummaryInsightDrawer.Context(
                                    userUid,
                                    subLabel,
                                    subtitleBase,
                                    y == null ? currentYear : y,
                                    k,
                                    currencyCode,
                                    accountId,
                                    subId,
                                    null,
                                    null,
                                    months,
                                    rowTotalFinal,
                                    avgCentsFinal
                                ));
                            });

                            avgCell.setOnMouseClicked(ev -> {
                                insightDrawer.show(new SummaryInsightDrawer.Context(
                                    userUid,
                                    subLabel,
                                    subtitleBase,
                                    y == null ? currentYear : y,
                                    k,
                                    currencyCode,
                                    accountId,
                                    subId,
                                    null,
                                    null,
                                    months,
                                    rowTotalFinal,
                                    avgCentsFinal
                                ));
                            });
                        }

                        List<String> exportRow = new ArrayList<>();
                        exportRow.add(subNameByRoot.get(r.id()).getOrDefault(subId, subId));
                        for (int m = 1; m <= 12; m++) {
                            exportRow.add(DashboardFormatters.formatMoney(months[m], currencyCode));
                        }
                        exportRow.add(DashboardFormatters.formatMoney(rowTotal, currencyCode));
                        exportRow.add(DashboardFormatters.formatMoney(avgCents, currencyCode));
                        exportRows.add(exportRow);
                        rowIdx++;

                        if (accountId == null) {
                            List<Node> accountRowNodes = new ArrayList<>();
                            Map<String, long[]> accs = byRootSubAccount
                                .getOrDefault(r.id(), Map.of())
                                .getOrDefault(subId, Map.of());
                            List<String> accountIds = new ArrayList<>(accs.keySet());
                            accountIds.sort((a1, a2) -> {
                                String n1 = accountNameById.getOrDefault(a1, a1);
                                String n2 = accountNameById.getOrDefault(a2, a2);
                                return n1.compareToIgnoreCase(n2);
                            });
                            for (String accId : accountIds) {
                                long[] am = accs.getOrDefault(accId, new long[13]);
                                boolean anyAcc = false;
                                for (int m = 1; m <= 12; m++) {
                                    if (am[m] != 0) {
                                        anyAcc = true;
                                        break;
                                    }
                                }
                                if (!anyAcc) {
                                    continue;
                                }

                                String accName = accountNameById.getOrDefault(accId, accId);
                                Label accLabel = new Label("      • " + accName);
                                accLabel.getStyleClass().add("text-secondary");
                                accLabel.getStyleClass().add("summary-sub-name");
                                accLabel.setMaxWidth(320);
                                accLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                                Tooltip.install(accLabel, new Tooltip(accName));
                                String zebraAcc = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                                accLabel.getStyleClass().add(zebraAcc);
                                fixedTable.add(accLabel, 0, rowIdx);
                                accountRowNodes.add(accLabel);

                                long accTotal = 0;
                                for (int m = 1; m <= 12; m++) {
                                    accTotal += am[m];
                                    Label vv = new Label(DashboardFormatters.formatMoney(am[m], currencyCode));
                                    vv.setMinWidth(100);
                                    vv.setAlignment(Pos.CENTER_RIGHT);
                                    vv.getStyleClass().add("summary-amount-cell");
                                    vv.getStyleClass().add(zebraAcc);
                                    if (m == currentMonth) {
                                        vv.getStyleClass().add("summary-current-month");
                                    }
                                    monthsTable.add(vv, m - 1, rowIdx);
                                    accountRowNodes.add(vv);
                                }

                                Label accTotalCell = new Label(DashboardFormatters.formatMoney(accTotal, currencyCode));
                                accTotalCell.setMinWidth(100);
                                accTotalCell.setAlignment(Pos.CENTER_RIGHT);
                                accTotalCell.getStyleClass().add("summary-amount-cell");
                                accTotalCell.getStyleClass().add("summary-total-col");
                                accTotalCell.getStyleClass().add(zebraAcc);
                                fixedTable.add(accTotalCell, 1, rowIdx);
                                accountRowNodes.add(accTotalCell);

                                long accAvgBase = accTotal;
                                if (selectedYear == currentYear) {
                                    long prevSum = 0;
                                    for (int m = 1; m < currentMonth; m++) {
                                        prevSum += am[m];
                                    }
                                    accAvgBase = prevSum;
                                }
                                long accAvg = monthsElapsed <= 0 ? 0 : (accAvgBase / monthsElapsed);
                                Label accAvgCell = new Label(DashboardFormatters.formatMoney(accAvg, currencyCode));
                                accAvgCell.setMinWidth(100);
                                accAvgCell.setAlignment(Pos.CENTER_RIGHT);
                                accAvgCell.getStyleClass().add("summary-amount-cell");
                                accAvgCell.getStyleClass().add("summary-avg-col");
                                accAvgCell.getStyleClass().add(zebraAcc);
                                monthsTable.add(accAvgCell, 12, rowIdx);
                                accountRowNodes.add(accAvgCell);

                                rowIdx++;
                            }

                            final String subtitleBase = (y == null ? String.valueOf(currentYear) : String.valueOf(y)) + " · " + ("INCOME".equalsIgnoreCase(k) ? "Ingresos" : "Gastos");
                            final long rowTotalFinal = rowTotal;
                            final long avgCentsFinal = avgCents;

                            boolean initialExpanded = expandedAccountsBySubId.getOrDefault(subId, false);
                            for (Node n : accountRowNodes) {
                                n.setVisible(initialExpanded);
                                n.setManaged(initialExpanded);
                            }

                            if (canToggleAccounts) {
                                final FontIcon chevronIconFinal = chevronIcon;
                                name.setOnMouseClicked(ev -> {
                                    boolean cur = expandedAccountsBySubId.getOrDefault(subId, false);
                                    boolean newV = !cur;
                                    expandedAccountsBySubId.put(subId, newV);
                                    if (chevronIconFinal != null) {
                                        chevronIconFinal.setIconCode(newV ? FontAwesomeSolid.CHEVRON_DOWN : FontAwesomeSolid.CHEVRON_RIGHT);
                                    }
                                    for (Node n : accountRowNodes) {
                                        n.setVisible(newV);
                                        n.setManaged(newV);
                                    }
                                    if (insightDrawer != null && ev != null && ev.getClickCount() >= 2) {
                                        insightDrawer.show(new SummaryInsightDrawer.Context(
                                            userUid,
                                            subLabel,
                                            subtitleBase,
                                            y == null ? currentYear : y,
                                            k,
                                            currencyCode,
                                            accountId,
                                            subId,
                                            null,
                                            null,
                                            months,
                                            rowTotalFinal,
                                            avgCentsFinal
                                        ));
                                    }
                                });
                            }
                        }
                    }
                }

                Label totalName = SummaryTableCell.category("TOTAL", true, true);
                fixedTable.add(totalName, 0, rowIdx);
                for (int m = 1; m <= 12; m++) {
                    Label v = new Label(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
                    v.getStyleClass().add("account-name");
                    v.setMinWidth(100);
                    v.setAlignment(Pos.CENTER_RIGHT);
                    v.getStyleClass().add("summary-total-amount");
                    if (m == currentMonth) {
                        v.getStyleClass().add("summary-current-month");
                    }
                    monthsTable.add(v, m - 1, rowIdx);
                }

                Label grand = new Label(DashboardFormatters.formatMoney(grandTotal, currencyCode));
                grand.getStyleClass().add("account-name");
                grand.setMinWidth(100);
                grand.setAlignment(Pos.CENTER_RIGHT);
                grand.getStyleClass().add("summary-total-amount");
                grand.getStyleClass().add("summary-total-col");
                fixedTable.add(grand, 1, rowIdx);

                long avgTotalBase = grandTotal;
                if (selectedYear == currentYear) {
                    long prevSum = 0;
                    for (int m = 1; m < currentMonth; m++) {
                        prevSum += totalByMonth[m];
                    }
                    avgTotalBase = prevSum;
                }
                long avgTotal = monthsElapsed <= 0 ? 0 : (avgTotalBase / monthsElapsed);
                Label grandAvg = new Label(DashboardFormatters.formatMoney(avgTotal, currencyCode));
                grandAvg.getStyleClass().add("account-name");
                grandAvg.setMinWidth(100);
                grandAvg.setAlignment(Pos.CENTER_RIGHT);
                grandAvg.getStyleClass().add("summary-total-amount");
                grandAvg.getStyleClass().add("summary-avg-col");
                monthsTable.add(grandAvg, 12, rowIdx);

                List<String> totalExportRow = new ArrayList<>();
                totalExportRow.add("TOTAL");
                for (int m = 1; m <= 12; m++) {
                    totalExportRow.add(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
                }
                totalExportRow.add(DashboardFormatters.formatMoney(grandTotal, currencyCode));
                totalExportRow.add(DashboardFormatters.formatMoney(avgTotal, currencyCode));
                exportRows.add(totalExportRow);

                exportRowsRef.set(exportRows);

                applySummaryRowHover(fixedTable, monthsTable);

                return;
            }

            Map<String, long[]> byRoot = new HashMap<>();
            for (CategoryRepository.Category r : roots) {
                byRoot.put(r.id(), new long[13]);
            }

            try {
                List<TransactionRepository.MonthlyCategoryTotal> rows = txRepo.listMonthlyTotalsByRootCategory(userUid, accountId, y == null ? currentYear : y, k);
                for (TransactionRepository.MonthlyCategoryTotal row : rows) {
                    long[] months = byRoot.computeIfAbsent(row.rootCategoryId(), __ -> new long[13]);
                    int m = row.month();
                    if (m >= 1 && m <= 12) {
                        months[m] = row.totalAmountCents();
                    }
                }
            } catch (Exception ignored) {
            }

            String[] monthNames = new String[] {
                "DESCRIPCIÓN",
                "ENERO",
                "FEBRERO",
                "MARZO",
                "ABRIL",
                "MAYO",
                "JUNIO",
                "JULIO",
                "AGOSTO",
                "SEPTIEMBRE",
                "OCTUBRE",
                "NOVIEMBRE",
                "DICIEMBRE",
                "TOTAL",
                "PROMEDIO"
            };

            exportRows.add(List.of(monthNames));

            for (int col = 0; col < monthNames.length; col++) {
                Label h = new Label(monthNames[col]);
                h.getStyleClass().add("account-name");
                h.getStyleClass().add("summary-header-cell");
                if (col == currentMonth) {
                    h.getStyleClass().add("summary-current-month");
                }
                if (col == 0) {
                    fixedTable.add(h, 0, 0);
                } else if (col >= 1 && col <= 12) {
                    monthsTable.add(h, col - 1, 0);
                } else if (col == 13) {
                    fixedTable.add(h, 1, 0);
                } else if (col == 14) {
                    monthsTable.add(h, 12, 0);
                }
            }

            long[] totalByMonth = new long[13];
            int rowIdx = 1;
            long grandTotal = 0;
            for (CategoryRepository.Category r : roots) {
                if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                    continue;
                }
                long[] months = byRoot.getOrDefault(r.id(), new long[13]);

                org.kordamp.ikonli.Ikon icon = "EXPENSE".equalsIgnoreCase(r.kind())
                    ? org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.SHOPPING_CART
                    : org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.WALLET;
                Node name = SummaryTableCell.categoryWithIcon(r.name(), true, false, icon);
                String zebra = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                name.getStyleClass().add(zebra);
                fixedTable.add(name, 0, rowIdx);

                long rowTotal = 0;
                List<Label> monthCells = new ArrayList<>();
                for (int m = 1; m <= 12; m++) {
                    totalByMonth[m] += months[m];
                    rowTotal += months[m];
                    Label v = SummaryTableCell.amountPlain(DashboardFormatters.formatMoney(months[m], currencyCode), m == currentMonth);
                    v.getStyleClass().add(zebra);
                    monthsTable.add(v, m - 1, rowIdx);
                    monthCells.add(v);
                }

                grandTotal += rowTotal;
                Label totalCell = SummaryTableCell.amountPlain(DashboardFormatters.formatMoney(rowTotal, currencyCode), false);
                totalCell.getStyleClass().addAll("summary-total-col", zebra);
                fixedTable.add(totalCell, 1, rowIdx);

                long avgBase = rowTotal;
                if (selectedYear == currentYear) {
                    long prevSum = 0;
                    for (int m = 1; m < currentMonth; m++) {
                        prevSum += months[m];
                    }
                    avgBase = prevSum;
                }
                long avgCents = monthsElapsed <= 0 ? 0 : (avgBase / monthsElapsed);
                Label avgCell = SummaryTableCell.amountPlain(DashboardFormatters.formatMoney(avgCents, currencyCode), false);
                avgCell.getStyleClass().addAll("summary-avg-col", zebra);
                monthsTable.add(avgCell, 12, rowIdx);

                if (insightDrawer != null) {
                    String subtitleBase = (y == null ? String.valueOf(currentYear) : String.valueOf(y)) + " · " + ("INCOME".equalsIgnoreCase(k) ? "Ingresos" : "Gastos");

                    List<String> ids = new ArrayList<>();
                    try {
                        ids.add(r.id());
                        for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, r.id())) {
                            if (c != null && c.id() != null) {
                                ids.add(c.id());
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    final List<String> idsFinal = ids;
                    final long rowTotalFinal = rowTotal;
                    final long avgCentsFinal = avgCents;

                    name.setOnMouseClicked(ev -> {
                        insightDrawer.show(new SummaryInsightDrawer.Context(
                            userUid,
                            r.name(),
                            subtitleBase,
                            y == null ? currentYear : y,
                            k,
                            currencyCode,
                            accountId,
                            r.id(),
                            idsFinal,
                            null,
                            months,
                            rowTotalFinal,
                            avgCentsFinal
                        ));
                    });

                    for (int i = 0; i < monthCells.size(); i++) {
                        int monthNum = i + 1;
                        Label cell = monthCells.get(i);
                        cell.setOnMouseClicked(ev -> {
                            java.util.Locale esCo = java.util.Locale.forLanguageTag("es-CO");
                            String ml = java.time.Month.of(monthNum).getDisplayName(java.time.format.TextStyle.FULL, esCo);
                            String mc = ml == null || ml.isBlank() ? "" : (ml.substring(0, 1).toUpperCase(esCo) + ml.substring(1));
                            String subtitle = mc + " " + (y == null ? String.valueOf(currentYear) : String.valueOf(y)) + " · " + ("INCOME".equalsIgnoreCase(k) ? "Ingresos" : "Gastos");
                            insightDrawer.show(new SummaryInsightDrawer.Context(
                                userUid,
                                r.name(),
                                subtitle,
                                y == null ? currentYear : y,
                                k,
                                currencyCode,
                                accountId,
                                r.id(),
                                idsFinal,
                                monthNum,
                                months,
                                months[monthNum],
                                avgCentsFinal
                            ));
                        });
                    }

                    totalCell.setOnMouseClicked(ev -> {
                        insightDrawer.show(new SummaryInsightDrawer.Context(
                            userUid,
                            r.name(),
                            subtitleBase,
                            y == null ? currentYear : y,
                            k,
                            currencyCode,
                            accountId,
                            r.id(),
                            idsFinal,
                            null,
                            months,
                            rowTotalFinal,
                            avgCentsFinal
                        ));
                    });

                    avgCell.setOnMouseClicked(ev -> {
                        insightDrawer.show(new SummaryInsightDrawer.Context(
                            userUid,
                            r.name(),
                            subtitleBase,
                            y == null ? currentYear : y,
                            k,
                            currencyCode,
                            accountId,
                            r.id(),
                            idsFinal,
                            null,
                            months,
                            rowTotalFinal,
                            avgCentsFinal
                        ));
                    });
                }

                List<String> exportRow = new ArrayList<>();
                exportRow.add(r.name());
                for (int m = 1; m <= 12; m++) {
                    exportRow.add(DashboardFormatters.formatMoney(months[m], currencyCode));
                }
                exportRow.add(DashboardFormatters.formatMoney(rowTotal, currencyCode));
                exportRow.add(DashboardFormatters.formatMoney(avgCents, currencyCode));
                exportRows.add(exportRow);
                rowIdx++;
            }

            Label totalName = SummaryTableCell.category("TOTAL", true, true);
            fixedTable.add(totalName, 0, rowIdx);
            for (int m = 1; m <= 12; m++) {
                Label v = new Label(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
                v.getStyleClass().add("account-name");
                v.setMinWidth(100);
                v.setAlignment(Pos.CENTER_RIGHT);
                v.getStyleClass().add("summary-total-amount");
                if (m == currentMonth) {
                    v.getStyleClass().add("summary-current-month");
                }
                monthsTable.add(v, m - 1, rowIdx);
            }

            Label grand = new Label(DashboardFormatters.formatMoney(grandTotal, currencyCode));
            grand.getStyleClass().add("account-name");
            grand.setMinWidth(100);
            grand.setAlignment(Pos.CENTER_RIGHT);
            grand.getStyleClass().add("summary-total-amount");
            grand.getStyleClass().add("summary-total-col");
            fixedTable.add(grand, 1, rowIdx);

            int totalMonthCount = 0;
            for (int m = 1; m <= 12; m++) {
                if (totalByMonth[m] != 0) {
                    totalMonthCount++;
                }
            }
            long avgTotal = totalMonthCount == 0 ? 0 : (grandTotal / totalMonthCount);
            Label grandAvg = new Label(DashboardFormatters.formatMoney(avgTotal, currencyCode));
            grandAvg.getStyleClass().add("account-name");
            grandAvg.setMinWidth(100);
            grandAvg.setAlignment(Pos.CENTER_RIGHT);
            grandAvg.getStyleClass().add("summary-total-amount");
            grandAvg.getStyleClass().add("summary-avg-col");
            monthsTable.add(grandAvg, 12, rowIdx);

            List<String> totalExportRow = new ArrayList<>();
            totalExportRow.add("TOTAL");
            for (int m = 1; m <= 12; m++) {
                totalExportRow.add(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
            }
            totalExportRow.add(DashboardFormatters.formatMoney(grandTotal, currencyCode));
            totalExportRow.add(DashboardFormatters.formatMoney(avgTotal, currencyCode));
            exportRows.add(totalExportRow);

            exportRowsRef.set(exportRows);

            applySummaryRowHover(fixedTable, monthsTable);
        };
        refreshSummaryRef.set(refreshSummary);

        if (viewModeProp != null) {
            viewModeProp.addListener((obs, o, n) -> refreshSummary.run());
        }

        year.valueProperty().addListener((obs, o, n) -> refreshSummary.run());
        kind.valueProperty().addListener((obs, o, n) -> {
            refreshRootCategories.run();
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        view.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        account.valueProperty().addListener((obs, o, n) -> refreshSummary.run());
        rootCategory.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        year.valueProperty().addListener((obs, o, n) -> refreshAccountsForSummary.run());
        kind.valueProperty().addListener((obs, o, n) -> refreshAccountsForSummary.run());

        refreshAccountsForSummary.run();

        Button exportCsv = new Button("Exportar CSV");
        exportCsv.getStyleClass().add("summary-action-btn");
        exportCsv.getStyleClass().add("summary-action-btn-primary");
        exportCsv.setMaxWidth(Double.MAX_VALUE);
        exportCsv.setOnAction(e -> {
            try {
                String yy = year.getValue() == null ? String.valueOf(currentYear) : String.valueOf(year.getValue());
                String kk = kind.getValue() == null ? "" : kind.getValue();
                String vv = view.getValue() == null ? "" : view.getValue();

                FileChooser chooser = new FileChooser();
                chooser.setTitle("Exportar resumen a CSV");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
                chooser.setInitialFileName("resumen_" + yy + "_" + kk + "_" + vv + ".csv");
                java.io.File out = chooser.showSaveDialog(exportCsv.getScene() == null ? null : exportCsv.getScene().getWindow());
                if (out == null) {
                    return;
                }

                List<List<String>> rows = exportRowsRef.get();
                StringBuilder sb = new StringBuilder();
                for (List<String> row : rows) {
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) {
                            sb.append(';');
                        }
                        String cell = row.get(i) == null ? "" : row.get(i);
                        cell = cell.replace("\"", "\"\"");
                        sb.append('"').append(cell).append('"');
                    }
                    sb.append("\r\n");
                }
                Path p = out.toPath();
                Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        });

        Button exportPdf = new Button("Exportar PDF");
        exportPdf.getStyleClass().add("summary-action-btn");
        exportPdf.getStyleClass().add("summary-action-btn-primary");
        exportPdf.setMaxWidth(Double.MAX_VALUE);
        exportPdf.setOnAction(e -> pdfDrawer.show());

        VBox actionsRow = new VBox(8, exportCsv, exportPdf);
        actionsRow.getStyleClass().add("summary-actions-row");
        actionsRow.setAlignment(Pos.CENTER_RIGHT);
        actionsRow.setMaxWidth(Double.MAX_VALUE);

        VBox actionsCard = new VBox(10, actionsRow);
        actionsCard.getStyleClass().add("summary-filters-card");
        actionsCard.setPadding(new Insets(12));
        actionsCard.setMinWidth(180);
        actionsCard.setAlignment(Pos.TOP_RIGHT);

        HBox filtersAndActionsRow = new HBox(12, filtersCard, actionsCard);
        filtersAndActionsRow.setAlignment(Pos.CENTER_LEFT);

        refreshSummary.run();
        return new MonthlySummaryParts(filtersAndActionsRow, contentHost);
    }

    private static Node buildGoalsPane(
        String userUid,
        AccountRepository accountRepo,
        GoalRepository goalRepo,
        Runnable refreshBalances
    ) {
        GridPane goalsTable = new GridPane();
        goalsTable.setHgap(10);
        goalsTable.setVgap(8);
        goalsTable.setPadding(new Insets(10));

        VBox goalsCard = new VBox(10, new Label("Metas"), goalsTable);
        goalsCard.getStyleClass().addAll("card", "content-card");
        goalsCard.getChildren().getFirst().getStyleClass().add("account-name");

        Runnable refreshGoals = () -> {
            goalsTable.getChildren().clear();
            try {
                if (goalRepo == null) {
                    return;
                }
                List<GoalRepository.Goal> goals = goalRepo.listByUser(userUid);
                if (goals.isEmpty()) {
                    goalsTable.add(new Label("Sin metas"), 0, 0);
                    return;
                }

                Label h1 = new Label("Meta");
                Label h2 = new Label("Guardado");
                Label h3 = new Label("Objetivo");
                Label h4 = new Label("Falta");
                Label h5 = new Label("%");
                h1.getStyleClass().add("text-secondary");
                h2.getStyleClass().add("text-secondary");
                h3.getStyleClass().add("text-secondary");
                h4.getStyleClass().add("text-secondary");
                h5.getStyleClass().add("text-secondary");
                goalsTable.add(h1, 0, 0);
                goalsTable.add(h2, 1, 0);
                goalsTable.add(h3, 2, 0);
                goalsTable.add(h4, 3, 0);
                goalsTable.add(h5, 4, 0);

                int row = 1;
                for (GoalRepository.Goal g : goals) {
                    long savedCents;
                    try {
                        savedCents = accountRepo.computeBalanceCents(userUid, g.accountId());
                    } catch (Exception ignored) {
                        savedCents = 0L;
                    }
                    long remaining = Math.max(0L, g.targetCents() - savedCents);
                    double pct = g.targetCents() <= 0 ? 0.0 : Math.min(1.0, (double) savedCents / (double) g.targetCents());

                    Label n = new Label(g.name());
                    Label saved = new Label(DashboardFormatters.formatMoney(savedCents, g.currency()));
                    Label target = new Label(DashboardFormatters.formatMoney(g.targetCents(), g.currency()));
                    Label falta = new Label(DashboardFormatters.formatMoney(remaining, g.currency()));
                    Label p = new Label(String.format(java.util.Locale.ROOT, "%.0f%%", pct * 100.0));

                    goalsTable.add(n, 0, row);
                    goalsTable.add(saved, 1, row);
                    goalsTable.add(target, 2, row);
                    goalsTable.add(falta, 3, row);
                    goalsTable.add(p, 4, row);
                    row++;
                }
            } catch (Exception ignored) {
            }
        };

        refreshGoals.run();
        if (refreshBalances != null) {
            refreshBalances.run();
        }

        return goalsCard;
    }

    private static void applySummaryRowHover(GridPane fixedTable, GridPane monthsTable) {
        Map<Integer, List<Node>> nodesByRow = new HashMap<>();

        AtomicReference<List<Node>> selectedRowRef = new AtomicReference<>(List.of());

        for (Node n : fixedTable.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            int row = r == null ? 0 : r;
            nodesByRow.computeIfAbsent(row, __ -> new ArrayList<>()).add(n);
        }

        for (Node n : monthsTable.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            int row = r == null ? 0 : r;
            nodesByRow.computeIfAbsent(row, __ -> new ArrayList<>()).add(n);
        }

        for (Map.Entry<Integer, List<Node>> e : nodesByRow.entrySet()) {
            int row = e.getKey();
            if (row <= 0) {
                continue;
            }
            List<Node> rowNodes = e.getValue();
            for (Node n : rowNodes) {
                EventHandler<? super MouseEvent> prevClick = n.getOnMouseClicked();
                n.setOnMouseEntered(ev -> {
                    for (Node x : rowNodes) {
                        if (!x.getStyleClass().contains("summary-row-hover")) {
                            x.getStyleClass().add("summary-row-hover");
                        }
                    }
                });
                n.setOnMouseExited(ev -> {
                    for (Node x : rowNodes) {
                        x.getStyleClass().remove("summary-row-hover");
                    }
                });

                n.setOnMouseClicked(ev -> {
                    List<Node> prev = selectedRowRef.get();
                    for (Node x : prev) {
                        x.getStyleClass().remove("summary-row-selected");
                    }

                    for (Node x : rowNodes) {
                        if (!x.getStyleClass().contains("summary-row-selected")) {
                            x.getStyleClass().add("summary-row-selected");
                        }
                    }
                    selectedRowRef.set(rowNodes);

                    if (prevClick != null) {
                        prevClick.handle(ev);
                    }
                });
            }
        }
    }
}
