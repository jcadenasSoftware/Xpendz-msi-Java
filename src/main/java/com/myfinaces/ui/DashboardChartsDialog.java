package com.myfinaces.ui;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
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
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
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

public final class DashboardChartsDialog {

    private DashboardChartsDialog() {
    }

    public static void showChartsDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
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
        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();
        ChoiceBox<String> month = new ChoiceBox<>();
        ChoiceBox<String> chartType = new ChoiceBox<>();

        int currentYear = LocalDate.now().getYear();
        for (int y = currentYear; y >= currentYear - 5; y--) {
            year.getItems().add(y);
        }
        year.getSelectionModel().selectFirst();

        kind.getItems().addAll("Gastos", "Ingresos");
        kind.getSelectionModel().select("Gastos");

        view.getItems().addAll("Categorías", "Subcategorías");
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
            rootCategory.getItems().addAll(categoryRepo.listRoots(userUid));
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

        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las subcategorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshSubcatsCharts = () -> {
            subCategory.getItems().clear();
            subCategory.getItems().add(null);

            boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
            CategoryRepository.Category root = rootCategory.getValue();
            if (!bySub || root == null) {
                subCategory.setDisable(true);
                subCategory.getSelectionModel().selectFirst();
                return;
            }
            subCategory.setDisable(false);
            try {
                subCategory.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
            } catch (Exception ignored) {
            }
            subCategory.getSelectionModel().selectFirst();
        };
        refreshSubcatsCharts.run();

        Runnable refreshAccountsForCharts = () -> {
            try {
                AccountRepository.Account selected = account.getValue();

                account.getItems().clear();
                account.getItems().add(null);

                boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
                CategoryRepository.Category sub = subCategory.getValue();
                Integer y = year.getValue();
                String kindLabel = kind.getValue();
                String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";

                List<AccountRepository.Account> allAccounts;
                try {
                    allAccounts = accountRepo.list(userUid);
                } catch (Exception ignored) {
                    allAccounts = List.of();
                }

                if (bySub && sub != null) {
                    List<String> ids;
                    try {
                        ids = txRepo.listAccountIdsUsedInCategory(userUid, y == null ? currentYear : y, k, sub.id());
                    } catch (Exception ignored) {
                        ids = List.of();
                    }

                    Set<String> idSet = new HashSet<>(ids);
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
        chartPane.getStyleClass().addAll("card", "content-card");
        chartPane.setPadding(new Insets(10));
        VBox.setVgrow(chartPane, Priority.ALWAYS);

        Runnable refreshChart = () -> {
            String currencyCode = "COP";
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            boolean bySubcategory = "Subcategorías".equalsIgnoreCase(view.getValue());
            AccountRepository.Account a = account.getValue();
            String accountId = a == null ? null : a.id();
            int y = year.getValue() == null ? currentYear : year.getValue();

            int monthIdx;
            String mLabel = month.getValue();
            if (mLabel == null || "TOTAL".equalsIgnoreCase(mLabel)) {
                monthIdx = 0;
            } else {
                monthIdx = Math.max(1, month.getSelectionModel().getSelectedIndex());
            }

            CategoryRepository.Category rootFilter = rootCategory.getValue();
            CategoryRepository.Category subFilter = subCategory.getValue();

            Map<String, String> nameById = new HashMap<>();
            Map<String, long[]> centsById = new HashMap<>();

            if (bySubcategory) {
                if (accountId == null && subFilter != null) {
                    try {
                        List<TransactionRepository.MonthlyCategoryDetailAccountTotal> rows =
                            txRepo.listMonthlyTotalsBySubcategoryAndAccount(userUid, y, k);
                        for (TransactionRepository.MonthlyCategoryDetailAccountTotal row : rows) {
                            if (rootFilter != null && !rootFilter.id().equals(row.rootCategoryId())) {
                                continue;
                            }
                            if (!subFilter.id().equals(row.categoryId())) {
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
                    }
                } catch (Exception ignored) {
                }

                try {
                    List<TransactionRepository.MonthlyCategoryDetailTotal> rows = txRepo.listMonthlyTotalsBySubcategory(userUid, accountId, y, k);
                    for (TransactionRepository.MonthlyCategoryDetailTotal row : rows) {
                        if (rootFilter != null && !rootFilter.id().equals(row.rootCategoryId())) {
                            continue;
                        }
                        if (subFilter != null && !subFilter.id().equals(row.categoryId())) {
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

        year.valueProperty().addListener((obs, o, n) -> refreshChart.run());
        kind.valueProperty().addListener((obs, o, n) -> refreshChart.run());
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
        subCategory.valueProperty().addListener((obs, o, n) -> {
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
