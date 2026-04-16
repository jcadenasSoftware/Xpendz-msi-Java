package com.myfinaces.ui;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class DashboardChartsDialog {

    private DashboardChartsDialog() {
    }

    public static void showChartsDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        GoalRepository goalRepo,
        TransferRepository transferRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Gráficos");
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

                        final double normalW = Math.min(1200, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(820, bounds.getHeight() * 0.90);
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

        Label headerTitle = new Label("Gráficos");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Visualiza ingresos y gastos por categoría o subcategoría.");
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

        ChoiceBox<Integer> year = new ChoiceBox<>();
        ChoiceBox<String> kind = new ChoiceBox<>();
        ChoiceBox<String> view = new ChoiceBox<>();
        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        MenuButton subCategory = new MenuButton("(Todas las subcategorías)");
        Set<String> selectedSubIds = new HashSet<>();
        ChoiceBox<String> month = new ChoiceBox<>();
        ChoiceBox<String> chartType = new ChoiceBox<>();

        int currentYear = LocalDate.now().getYear();
        for (int y = currentYear; y >= currentYear - 5; y--) {
            year.getItems().add(y);
        }
        year.getSelectionModel().selectFirst();

        kind.getItems().addAll("Gastos", "Ingresos");
        kind.getSelectionModel().select("Gastos");

        view.getItems().addAll("Categorías", "Subcategorías", "Metas");
        view.getSelectionModel().select("Categorías");

        month.getItems().addAll(
            "TOTAL",
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
            "DICIEMBRE"
        );
        month.getSelectionModel().select("TOTAL");

        chartType.getItems().addAll("Torta", "Barras");
        chartType.getSelectionModel().select("Barras");

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

        AtomicReference<Runnable> refreshAccountsForChartsRef = new AtomicReference<>(null);
        AtomicReference<Runnable> refreshChartRef = new AtomicReference<>(null);

        Runnable refreshSubcatsCharts = () -> {
            boolean goalsView = "Metas".equalsIgnoreCase(view.getValue());
            boolean bySub = !goalsView && "Subcategorías".equalsIgnoreCase(view.getValue());
            CategoryRepository.Category root = rootCategory.getValue();

            subCategory.getItems().clear();
            selectedSubIds.clear();
            subCategory.setText("(Todas las subcategorías)");

            if (goalsView) {
                rootCategory.setDisable(true);
                subCategory.setDisable(true);
                account.setDisable(true);
                kind.setDisable(true);
                return;
            }

            rootCategory.setDisable(false);
            account.setDisable(false);
            kind.setDisable(false);

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
                Runnable ra = refreshAccountsForChartsRef.get();
                if (ra != null) {
                    ra.run();
                }
                Runnable rc = refreshChartRef.get();
                if (rc != null) {
                    rc.run();
                }
            });
            subCategory.getItems().add(all);

            try {
                List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, root.id());
                children.sort((c1, c2) -> c1.name().compareToIgnoreCase(c2.name()));
                for (CategoryRepository.Category c : children) {
                    CheckMenuItem item = new CheckMenuItem(c.name());
                    item.setStyle("-fx-font-weight: bold;");
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

                        Runnable ra = refreshAccountsForChartsRef.get();
                        if (ra != null) {
                            ra.run();
                        }
                        Runnable rc = refreshChartRef.get();
                        if (rc != null) {
                            rc.run();
                        }
                    });
                    subCategory.getItems().add(item);
                }

                CheckMenuItem noneItem = new CheckMenuItem("(Sin subcategoría)");
                noneItem.setStyle("-fx-font-weight: bold;");
                String noneId = root.id() + ":NONE";
                noneItem.setOnAction(ev -> {
                    if (noneItem.isSelected()) {
                        selectedSubIds.add(noneId);
                    } else {
                        selectedSubIds.remove(noneId);
                    }
                    if (selectedSubIds.isEmpty()) {
                        subCategory.setText("(Todas las subcategorías)");
                    } else {
                        subCategory.setText(selectedSubIds.size() + " seleccionadas");
                    }

                    Runnable ra = refreshAccountsForChartsRef.get();
                    if (ra != null) {
                        ra.run();
                    }
                    Runnable rc = refreshChartRef.get();
                    if (rc != null) {
                        rc.run();
                    }
                });
                subCategory.getItems().add(noneItem);
            } catch (Exception ignored) {
            }
        };
        refreshSubcatsCharts.run();

        Runnable refreshAccountsForCharts = () -> {
            try {
                AccountRepository.Account selected = account.getValue();

                boolean goalsView = "Metas".equalsIgnoreCase(view.getValue());
                if (goalsView) {
                    account.getItems().clear();
                    account.getItems().add(null);
                    account.getSelectionModel().selectFirst();
                    return;
                }

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
                            String actualCategoryId = subId;
                            if (subId != null && subId.endsWith(":NONE")) {
                                int idx = subId.indexOf(':');
                                if (idx > 0) {
                                    actualCategoryId = subId.substring(0, idx);
                                }
                            }
                            idSet.addAll(txRepo.listAccountIdsUsedInCategory(userUid, y == null ? currentYear : y, k, actualCategoryId));
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
        refreshAccountsForChartsRef.set(refreshAccountsForCharts);

        Label fYear = new Label("Año");
        fYear.getStyleClass().add("text-secondary");
        Label fKind = new Label("Tipo");
        fKind.getStyleClass().add("text-secondary");
        Label fView = new Label("Vista");
        fView.getStyleClass().add("text-secondary");
        Label fRoot = new Label("Categoría");
        fRoot.getStyleClass().add("text-secondary");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("text-secondary");
        Label fAccount = new Label("Cuenta");
        fAccount.getStyleClass().add("text-secondary");
        Label fMonth = new Label("Mes");
        fMonth.getStyleClass().add("text-secondary");
        Label fChart = new Label("Gráfico");
        fChart.getStyleClass().add("text-secondary");

        HBox pYear = new HBox(8, fYear, year);
        pYear.setAlignment(Pos.CENTER_LEFT);
        HBox pKind = new HBox(8, fKind, kind);
        pKind.setAlignment(Pos.CENTER_LEFT);
        HBox pView = new HBox(8, fView, view);
        pView.setAlignment(Pos.CENTER_LEFT);
        HBox pRoot = new HBox(8, fRoot, rootCategory);
        pRoot.setAlignment(Pos.CENTER_LEFT);
        HBox pSub = new HBox(8, fSub, subCategory);
        pSub.setAlignment(Pos.CENTER_LEFT);
        HBox pAccount = new HBox(8, fAccount, account);
        pAccount.setAlignment(Pos.CENTER_LEFT);
        HBox pMonth = new HBox(8, fMonth, month);
        pMonth.setAlignment(Pos.CENTER_LEFT);
        HBox pChart = new HBox(8, fChart, chartType);
        pChart.setAlignment(Pos.CENTER_LEFT);

        FlowPane filtersRow = new FlowPane(12, 10);
        filtersRow.getChildren().addAll(pYear, pKind, pView, pRoot, pSub, pAccount, pMonth, pChart);
        VBox filtersCard = new VBox(10, filtersRow);
        filtersCard.getStyleClass().addAll("card", "content-card");
        filtersCard.setPadding(new Insets(10));

        Button toggleFilters = new Button("Ocultar filtros");
        toggleFilters.getStyleClass().add("btn-secondary");
        toggleFilters.setOnAction(e -> {
            boolean show = !filtersCard.isVisible();
            filtersCard.setVisible(show);
            filtersCard.setManaged(show);
            toggleFilters.setText(show ? "Ocultar filtros" : "Mostrar filtros");
        });

        BorderPane chartPane = new BorderPane();

        Runnable refreshChart = () -> {
            chartPane.setCenter(null);
            Integer y = year.getValue();
            int selectedYear = y == null ? currentYear : y;
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            boolean bySubcategory = "Subcategorías".equalsIgnoreCase(view.getValue());
            AccountRepository.Account a = account.getValue();
            String accountId = a == null ? null : a.id();
            String currencyCode = "COP";

            if ("Metas".equalsIgnoreCase(view.getValue())) {
                try {
                    if (goalRepo == null || transferRepo == null) {
                        chartPane.setCenter(new Label("Sin datos"));
                        return;
                    }
                    List<GoalRepository.Goal> goals = goalRepo.listByUser(userUid);
                    if (goals.isEmpty()) {
                        chartPane.setCenter(new Label("Sin metas"));
                        return;
                    }

                    Set<String> goalAccountIds = new HashSet<>();
                    for (GoalRepository.Goal g : goals) {
                        if (g != null && g.accountId() != null && !g.accountId().isBlank()) {
                            goalAccountIds.add(g.accountId());
                        }
                    }

                    long[] inMonths = new long[13];
                    long[] outMonths = new long[13];
                    for (TransferRepository.MonthlyInOutTotal row : transferRepo.listMonthlyInOutTotalsForAccounts(userUid, selectedYear, goalAccountIds)) {
                        int m = row.month();
                        if (m >= 1 && m <= 12) {
                            inMonths[m] = row.inAmountCents();
                            outMonths[m] = row.outAmountCents();
                        }
                    }

                    int mi = month.getSelectionModel().getSelectedIndex();
                    long inValue;
                    long outValue;
                    if (mi == 0) {
                        long inT = 0;
                        long outT = 0;
                        for (int m = 1; m <= 12; m++) {
                            inT += inMonths[m];
                            outT += outMonths[m];
                        }
                        inValue = inT;
                        outValue = outT;
                    } else {
                        int m = (mi >= 1 && mi <= 12) ? mi : 0;
                        inValue = m == 0 ? 0 : inMonths[m];
                        outValue = m == 0 ? 0 : outMonths[m];
                    }

                    String ct = chartType.getValue();
                    boolean pie = "Torta".equalsIgnoreCase(ct);
                    if (pie) {
                        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();
                        data.add(new PieChart.Data("Aportes", Math.abs(inValue) / 100.0));
                        data.add(new PieChart.Data("Retiros", Math.abs(outValue) / 100.0));
                        PieChart chart = new PieChart(data);
                        chart.setLegendVisible(true);
                        chart.setLabelsVisible(true);
                        chartPane.setCenter(chart);
                        Platform.runLater(() -> {
                            for (PieChart.Data d : data) {
                                try {
                                    String label = d.getName();
                                    long cents = "Aportes".equalsIgnoreCase(label) ? inValue : outValue;
                                    Tooltip.install(d.getNode(), new Tooltip(label + ": " + DashboardFormatters.formatMoney(cents, currencyCode)));
                                } catch (Exception ignored) {
                                }
                            }
                        });
                        return;
                    }

                    CategoryAxis xAxis = new CategoryAxis();
                    NumberAxis yAxis = new NumberAxis();
                    yAxis.setForceZeroInRange(true);
                    BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
                    chart.setLegendVisible(false);
                    chart.setAnimated(false);
                    XYChart.Series<String, Number> series = new XYChart.Series<>();
                    series.getData().add(new XYChart.Data<>("Aportes", Math.abs(inValue) / 100.0));
                    series.getData().add(new XYChart.Data<>("Retiros", Math.abs(outValue) / 100.0));
                    chart.getData().setAll(List.of(series));
                    chartPane.setCenter(chart);

                    Platform.runLater(() -> {
                        for (XYChart.Data<String, Number> d : series.getData()) {
                            try {
                                String label = String.valueOf(d.getXValue());
                                long cents = "Aportes".equalsIgnoreCase(label) ? inValue : outValue;
                                Tooltip.install(d.getNode(), new Tooltip(label + ": " + DashboardFormatters.formatMoney(cents, currencyCode)));
                            } catch (Exception ignored) {
                            }
                        }
                    });
                    return;
                } catch (Exception ignored) {
                    chartPane.setCenter(new Label("Sin datos"));
                    return;
                }
            }

            // Existing logic for Categorías/Subcategorías continues below
            CategoryRepository.Category rootFilter = rootCategory.getValue();
            Set<String> subFilterIds = bySubcategory ? new HashSet<>(selectedSubIds) : Set.of();

            Map<String, String> nameById = new HashMap<>();
            Map<String, long[]> centsById = new HashMap<>();

            if (bySubcategory) {
                boolean singleSubSelected = subFilterIds.size() == 1;
                String singleSubId = singleSubSelected ? subFilterIds.iterator().next() : null;

                if (accountId == null && singleSubSelected) {
                    try {
                        List<TransactionRepository.MonthlyCategoryDetailAccountTotal> rows =
                            txRepo.listMonthlyTotalsBySubcategoryAndAccount(userUid, y, k);
                        for (TransactionRepository.MonthlyCategoryDetailAccountTotal row : rows) {
                            if (rootFilter != null && !rootFilter.id().equals(row.rootCategoryId())) {
                                continue;
                            }
                            if (!singleSubId.equals(row.categoryId())) {
                                continue;
                            }

                            long[] monthsArr = centsById.computeIfAbsent(row.accountId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                monthsArr[m] += row.totalAmountCents();
                            }

                            if (row.accountId() != null && row.accountName() != null) {
                                nameById.putIfAbsent(row.accountId(), row.accountName());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                } else {
                    try {
                        List<CategoryRepository.Category> roots = categoryRepo.listRoots(userUid);
                        for (CategoryRepository.Category r : roots) {
                            if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                                continue;
                            }
                            try {
                                for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, r.id())) {
                                    nameById.put(c.id(), c.name());
                                }
                            } catch (Exception ignored) {
                            }
                            nameById.putIfAbsent(r.id() + ":NONE", "(Sin subcategoría)");
                        }
                    } catch (Exception ignored) {
                    }

                    try {
                        List<TransactionRepository.MonthlyCategoryDetailTotal> rows = txRepo.listMonthlyTotalsBySubcategory(userUid, accountId, y, k);
                        for (TransactionRepository.MonthlyCategoryDetailTotal row : rows) {
                            if (rootFilter != null && !rootFilter.id().equals(row.rootCategoryId())) {
                                continue;
                            }
                            if (!subFilterIds.isEmpty() && !subFilterIds.contains(row.categoryId())) {
                                continue;
                            }
                            long[] monthsArr = centsById.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                monthsArr[m] = row.totalAmountCents();
                            }
                            nameById.putIfAbsent(row.categoryId(), row.categoryName());
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else {
                try {
                    List<CategoryRepository.Category> roots = categoryRepo.listRoots(userUid);
                    for (CategoryRepository.Category r : roots) {
                        if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                            continue;
                        }
                        nameById.put(r.id(), r.name());
                    }
                } catch (Exception ignored) {
                }

                try {
                    List<TransactionRepository.MonthlyCategoryTotal> rows = txRepo.listMonthlyTotalsByRootCategory(userUid, accountId, y, k);
                    for (TransactionRepository.MonthlyCategoryTotal row : rows) {
                        if (rootFilter != null && !rootFilter.id().equals(row.rootCategoryId())) {
                            continue;
                        }
                        long[] monthsArr = centsById.computeIfAbsent(row.rootCategoryId(), __ -> new long[13]);
                        int m = row.month();
                        if (m >= 1 && m <= 12) {
                            monthsArr[m] = row.totalAmountCents();
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            List<Map.Entry<String, Long>> items = new ArrayList<>();
            for (Map.Entry<String, long[]> e : centsById.entrySet()) {
                long[] monthsArr = e.getValue();
                long value;
                int monthIdx = month.getSelectionModel().getSelectedIndex();
                if (monthIdx == 0) {
                    long t = 0;
                    for (int m = 1; m <= 12; m++) {
                        t += monthsArr[m];
                    }
                    value = t;
                } else {
                    value = (monthIdx >= 1 && monthIdx <= 12) ? monthsArr[monthIdx] : 0;
                }
                if (value == 0) {
                    continue;
                }
                items.add(Map.entry(e.getKey(), value));
            }
            items.sort((a1, a2) -> Long.compare(Math.abs(a2.getValue()), Math.abs(a1.getValue())));

            String ct = chartType.getValue();
            boolean pie = "Torta".equalsIgnoreCase(ct);
            if (pie) {
                ObservableList<PieChart.Data> data = FXCollections.observableArrayList();
                for (var it : items) {
                    String name = nameById.getOrDefault(it.getKey(), it.getKey());
                    data.add(new PieChart.Data(name, Math.abs(it.getValue()) / 100.0));
                }
                PieChart chart = new PieChart(data);
                chart.setLegendVisible(true);
                chart.setLabelsVisible(true);
                chartPane.setCenter(chart);

                Platform.runLater(() -> {
                    for (PieChart.Data d : data) {
                        try {
                            String label = d.getName();
                            long cents = 0;
                            for (var it : items) {
                                if (label.equals(nameById.getOrDefault(it.getKey(), it.getKey()))) {
                                    cents = it.getValue();
                                    break;
                                }
                            }
                            Tooltip.install(d.getNode(), new Tooltip(label + ": " + DashboardFormatters.formatMoney(cents, currencyCode)));
                        } catch (Exception ignored) {
                        }
                    }
                });
                return;
            }

            CategoryAxis xAxis = new CategoryAxis();
            NumberAxis yAxis = new NumberAxis();
            yAxis.setForceZeroInRange(true);
            BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
            chart.setLegendVisible(false);
            chart.setAnimated(false);
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            for (var it : items) {
                String name = nameById.getOrDefault(it.getKey(), it.getKey());
                double v = Math.abs(it.getValue()) / 100.0;
                XYChart.Data<String, Number> d = new XYChart.Data<>(name, v);
                d.setExtraValue(it.getKey());
                series.getData().add(d);
            }
            chart.getData().setAll(List.of(series));
            chartPane.setCenter(chart);

            Platform.runLater(() -> {
                for (XYChart.Data<String, Number> d : series.getData()) {
                    try {
                        Object extra = d.getExtraValue();
                        String id = extra == null ? null : String.valueOf(extra);
                        String color = colorFromKey(id == null ? String.valueOf(d.getXValue()) : id);
                        if (d.getNode() != null) {
                            d.getNode().setStyle("-fx-bar-fill: " + color + ";");
                        } else {
                            d.nodeProperty().addListener((o, oldN, newN) -> {
                                if (newN != null) {
                                    newN.setStyle("-fx-bar-fill: " + color + ";");
                                }
                            });
                        }

                        String label = String.valueOf(d.getXValue());
                        long cents = 0;
                        for (var it : items) {
                            if (label.equals(nameById.getOrDefault(it.getKey(), it.getKey()))) {
                                cents = it.getValue();
                                break;
                            }
                        }
                        Tooltip.install(d.getNode(), new Tooltip(label + ": " + DashboardFormatters.formatMoney(cents, currencyCode)));
                    } catch (Exception ignored) {
                    }
                }
            });
        };
        refreshChartRef.set(refreshChart);

        year.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        kind.valueProperty().addListener((obs, o, n) -> {
            refreshRootCategories.run();
            refreshSubcatsCharts.run();
            refreshAccountsForCharts.run();
            refreshChart.run();
        });
        view.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsCharts.run();
            refreshAccountsForCharts.run();
            refreshChart.run();
        });
        account.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        rootCategory.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsCharts.run();
            refreshAccountsForCharts.run();
            refreshChart.run();
        });
        month.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        chartType.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        year.valueProperty().addListener((obs, o, n) -> refreshAccountsForCharts.run());
        kind.valueProperty().addListener((obs, o, n) -> refreshAccountsForCharts.run());

        refreshAccountsForCharts.run();

        HBox actionsRow = new HBox(10, toggleFilters);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(12, actionsRow, filtersCard, chartPane);
        body.setPadding(new Insets(10));
        VBox.setVgrow(chartPane, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);

        refreshChart.run();
        dialog.showAndWait();
    }

    private static String colorFromKey(String key) {
        String k = key == null ? "" : key;
        int h = k.hashCode();
        double hue = (h & 0x7fffffff) % 360;
        double s = 0.68;
        double l = 0.52;
        int rgb = hslToRgb(hue / 360.0, s, l);
        return String.format("#%06X", (0xFFFFFF & rgb));
    }

    private static int hslToRgb(double h, double s, double l) {
        double r;
        double g;
        double b;

        if (s == 0) {
            r = g = b = l;
        } else {
            double q = l < 0.5 ? (l * (1 + s)) : (l + s - l * s);
            double p = 2 * l - q;
            r = hueToRgb(p, q, h + 1.0 / 3.0);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1.0 / 3.0);
        }

        int ri = (int) Math.round(r * 255);
        int gi = (int) Math.round(g * 255);
        int bi = (int) Math.round(b * 255);
        ri = Math.max(0, Math.min(255, ri));
        gi = Math.max(0, Math.min(255, gi));
        bi = Math.max(0, Math.min(255, bi));
        return (ri << 16) | (gi << 8) | bi;
    }

    private static double hueToRgb(double p, double q, double t) {
        double tt = t;
        if (tt < 0) {
            tt += 1;
        }
        if (tt > 1) {
            tt -= 1;
        }
        if (tt < 1.0 / 6.0) {
            return p + (q - p) * 6 * tt;
        }
        if (tt < 1.0 / 2.0) {
            return q;
        }
        if (tt < 2.0 / 3.0) {
            return p + (q - p) * (2.0 / 3.0 - tt) * 6;
        }
        return p;
    }
}
