package com.myfinaces.ui;

import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;

public final class FinancialHeatmapGrid {

    public record DataRow(String categoryId, String categoryName, long[] monthCents) {
    }

    private static void refreshRowSelectionStyles(VBox matrix, VBox catsCol, String selectedKey) {
        if (matrix == null || catsCol == null) {
            return;
        }
        String selectedCatId = null;
        if (selectedKey != null) {
            int idx = selectedKey.indexOf(':');
            if (idx > 0) {
                selectedCatId = selectedKey.substring(0, idx);
            }
        }

        for (int i = 0; i < matrix.getChildren().size(); i++) {
            Node rowNode = matrix.getChildren().get(i);
            // catsCol includes the header at index 0.
            int catsIdx = i + 1;
            Node catNode = catsIdx < catsCol.getChildren().size() ? catsCol.getChildren().get(catsIdx) : null;

            if (rowNode instanceof HBox row) {
                boolean selected = false;
                if (selectedCatId != null) {
                    for (Node c : row.getChildren()) {
                        if (c instanceof HBox cells) {
                            for (Node dot : cells.getChildren()) {
                                Object key = dot.getUserData();
                                if (key instanceof String s && s.startsWith(selectedCatId + ":")) {
                                    selected = true;
                                    break;
                                }
                            }
                        }
                        if (selected) {
                            break;
                        }
                    }
                }

                if (selected) {
                    if (!row.getStyleClass().contains("heatmap-row-selected")) {
                        row.getStyleClass().add("heatmap-row-selected");
                    }
                } else {
                    row.getStyleClass().remove("heatmap-row-selected");
                }
            }

            if (catNode != null) {
                boolean selected = selectedCatId != null && catNode.getUserData() != null && selectedCatId.equals(catNode.getUserData());
                // Fallback: infer by index if userData not set.
                if (selectedCatId != null && catNode.getUserData() == null) {
                    selected = false;
                }
                if (selected) {
                    if (!catNode.getStyleClass().contains("heatmap-cat-selected")) {
                        catNode.getStyleClass().add("heatmap-cat-selected");
                    }
                } else {
                    catNode.getStyleClass().remove("heatmap-cat-selected");
                }
            }
        }
    }

    public record Selection(
        String categoryId,
        String categoryName,
        int year,
        int month,
        long cents,
        long[] monthsCents
    ) {
    }

    private FinancialHeatmapGrid() {
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        int year,
        String kind,
        String currencyCode,
        String accountId
    ) {
        return build(userUid, txRepo, year, kind, currencyCode, accountId, null);
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        int year,
        String kind,
        String currencyCode,
        String accountId,
        java.util.function.Consumer<Selection> onSelect
    ) {
        final double colW = 54;
        final double cellH = 28;
        final double cellRadius = 8;
        int currentMonth = LocalDate.now().getMonthValue();
        Locale esCo = Locale.forLanguageTag("es-CO");

        List<DataRow> rows = loadRows(userUid, txRepo, year, kind, accountId);
        rows.sort(Comparator.comparingLong((DataRow r) -> -sumAbs(r.monthCents)).thenComparing(DataRow::categoryName, String.CASE_INSENSITIVE_ORDER));

        long max = 0;
        long grandTotal = 0;
        long[] monthTotalsAbs = new long[13];
        for (DataRow r : rows) {
            for (int m = 1; m <= 12; m++) {
                max = Math.max(max, Math.abs(r.monthCents[m]));
                grandTotal += Math.abs(r.monthCents[m]);
                monthTotalsAbs[m] += Math.abs(r.monthCents[m]);
            }
        }
        if (max <= 0) {
            max = 1;
        }

        Label title = new Label("Heatmap");
        title.getStyleClass().add("heatmap-title");

        String subtitleText = ("INCOME".equalsIgnoreCase(kind) ? "Ingresos" : "Gastos") + " · " + year;
        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("heatmap-subtitle");

        VBox titleBox = new VBox(2, title, subtitle);
        titleBox.getStyleClass().add("heatmap-title-box");
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Node legend = HeatmapLegend.build();
        HBox topRow = new HBox(12, titleBox, legend);
        topRow.getStyleClass().add("heatmap-top-row");
        topRow.setAlignment(Pos.CENTER_LEFT);

        // Header for categories column
        Label catsHeader = new Label("CATEGORÍAS");
        catsHeader.getStyleClass().addAll("text-secondary", "heatmap-cats-header");
        catsHeader.setMinWidth(200);
        catsHeader.setPrefWidth(200);
        catsHeader.setMaxWidth(200);
        catsHeader.setMinHeight(cellH);
        catsHeader.setPrefHeight(cellH);
        catsHeader.setAlignment(Pos.CENTER_LEFT);
        catsHeader.setPadding(new Insets(0, 0, 0, 0));

        HBox monthsHeader = new HBox(6);
        monthsHeader.getStyleClass().add("heatmap-months-header");
        monthsHeader.setAlignment(Pos.CENTER_LEFT);
        monthsHeader.setMinHeight(cellH);
        monthsHeader.setPrefHeight(cellH);
        monthsHeader.setMaxWidth(Double.MAX_VALUE);

        for (int m = 1; m <= 12; m++) {
            String monthName = java.time.Month.of(m).getDisplayName(TextStyle.SHORT, esCo);
            Label hm = new Label(cap(monthName).toUpperCase());
            hm.getStyleClass().addAll("text-secondary", "heatmap-month-label");
            hm.setMinWidth(colW);
            hm.setPrefWidth(colW);
            hm.setMinHeight(cellH);
            hm.setPrefHeight(cellH);
            hm.setAlignment(Pos.CENTER);
            if (m == currentMonth) {
                hm.getStyleClass().add("heatmap-month-current");
            }
            monthsHeader.getChildren().add(hm);
        }

        VBox matrix = new VBox(8);
        matrix.getStyleClass().add("heatmap-matrix");
        matrix.setFillWidth(true);

        VBox catsCol = new VBox(8);
        catsCol.getStyleClass().add("heatmap-cats");
        catsCol.setFillWidth(true);

        // Add categories header to catsCol for sticky behavior
        catsCol.getChildren().add(catsHeader);

        final String[] selectedKey = new String[] { null };

        for (DataRow r : rows) {
            HBox row = new HBox(10);
            row.getStyleClass().add("heatmap-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);
            row.setMinHeight(cellH);
            row.setPrefHeight(cellH);

            Label cat = new Label(r.categoryName);
            cat.getStyleClass().addAll("heatmap-row-label", "heatmap-cat-label");
            cat.setMinWidth(200);
            cat.setPrefWidth(200);
            cat.setMaxWidth(200);
            cat.setMinHeight(cellH);
            cat.setPrefHeight(cellH);
            cat.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            Tooltip.install(cat, new Tooltip(r.categoryName));

            VBox catWrap = new VBox(cat);
            catWrap.getStyleClass().add("heatmap-cat-wrap");
            catWrap.setMinWidth(200);
            catWrap.setPrefWidth(200);
            catWrap.setMaxWidth(200);
            catWrap.setMinHeight(cellH);
            catWrap.setPrefHeight(cellH);

            HBox cells = new HBox(6);
            cells.getStyleClass().add("heatmap-row-cells");
            cells.setAlignment(Pos.CENTER_LEFT);

            for (int m = 1; m <= 12; m++) {
                long cents = r.monthCents[m];
                double ratio = Math.min(1.0, (double) Math.abs(cents) / (double) max);
                int level = levelFor(ratio, cents);
                Color fill = cents == 0 ? Color.TRANSPARENT : colorFor(kind, level);

                Label cell = FinancialHeatmapCell.build("", fill, false, colW, cellH, cellRadius);
                cell.getStyleClass().add("heatmap-dot");
                if (cents == 0) {
                    cell.getStyleClass().add("heatmap-dot-empty");
                }

                String monthLabel = java.time.Month.of(m).getDisplayName(TextStyle.FULL, esCo);
                String val = DashboardFormatters.formatMoney(cents, currencyCode);
                String pct = grandTotal <= 0 ? "" : String.format(Locale.ROOT, "%.1f%% del total", (100.0 * (double) Math.abs(cents) / (double) grandTotal));
                Tooltip t = HeatmapTooltip.build(r.categoryName, cap(monthLabel) + " " + year, val, pct);
                Tooltip.install(cell, t);

                int mm = m;
                String key = r.categoryId + ":" + mm;

                cell.setOnMouseEntered(ev -> {
                    if (!cell.getStyleClass().contains("heatmap-cell-hover")) {
                        cell.getStyleClass().add("heatmap-cell-hover");
                    }
                });
                cell.setOnMouseExited(ev -> {
                    cell.getStyleClass().remove("heatmap-cell-hover");
                });

                if (onSelect != null) {
                    cell.setOnMouseClicked(ev -> {
                        selectedKey[0] = key;
                        refreshSelectionStyles(matrix, selectedKey[0]);
                        refreshRowSelectionStyles(matrix, catsCol, selectedKey[0]);
                        onSelect.accept(new Selection(r.categoryId(), r.categoryName(), year, mm, r.monthCents[mm], r.monthCents));
                    });

                    cat.setOnMouseClicked(ev -> {
                        selectedKey[0] = r.categoryId + ":" + currentMonth;
                        refreshSelectionStyles(matrix, selectedKey[0]);
                        refreshRowSelectionStyles(matrix, catsCol, selectedKey[0]);
                        onSelect.accept(new Selection(r.categoryId(), r.categoryName(), year, currentMonth, r.monthCents[currentMonth], r.monthCents));
                    });
                }

                cell.setUserData(key);
                cells.getChildren().add(cell);
            }

            row.getChildren().add(cells);
            matrix.getChildren().add(row);
            catsCol.getChildren().add(catWrap);

            row.setOnMouseEntered(ev -> {
                row.getStyleClass().add("heatmap-row-hover");
                catWrap.getStyleClass().add("heatmap-cat-hover");
            });
            row.setOnMouseExited(ev -> {
                row.getStyleClass().remove("heatmap-row-hover");
                catWrap.getStyleClass().remove("heatmap-cat-hover");
            });
        }

        VBox right = new VBox(10, monthsHeader, matrix);
        right.getStyleClass().add("heatmap-right");
        right.setFillWidth(true);

        HBox body = new HBox(14, catsCol, right);
        body.getStyleClass().add("heatmap-body");
        HBox.setHgrow(right, Priority.ALWAYS);

        Label totalLabel = new Label("TOTAL MENSUAL");
        totalLabel.getStyleClass().addAll("text-secondary", "heatmap-cats-header", "heatmap-total-label");
        totalLabel.setMinWidth(200);
        totalLabel.setPrefWidth(200);
        totalLabel.setMaxWidth(200);
        totalLabel.setMinHeight(cellH);
        totalLabel.setPrefHeight(cellH);
        totalLabel.setAlignment(Pos.CENTER_LEFT);

        VBox totalLeft = new VBox(totalLabel);
        totalLeft.setMinWidth(200);
        totalLeft.setPrefWidth(200);
        totalLeft.setMaxWidth(200);
        totalLeft.setMinHeight(cellH);
        totalLeft.setPrefHeight(cellH);
        totalLeft.setAlignment(Pos.CENTER_LEFT);

        HBox totalsRow = new HBox(6);
        totalsRow.setAlignment(Pos.CENTER_LEFT);
        totalsRow.setMinHeight(cellH);
        totalsRow.setPrefHeight(cellH);
        for (int m = 1; m <= 12; m++) {
            Label tm = new Label(formatCompactMoney(monthTotalsAbs[m], currencyCode));
            tm.getStyleClass().addAll("text-secondary", "heatmap-month-label", "heatmap-total-value");
            tm.setMinHeight(cellH);
            tm.setPrefHeight(cellH);
            tm.setMinWidth(colW);
            tm.setPrefWidth(colW);
            tm.setAlignment(Pos.CENTER);
            if (m == currentMonth) {
                tm.getStyleClass().add("heatmap-month-current");
            }
            totalsRow.getChildren().add(tm);
        }

        HBox totalsLine = new HBox(14, totalLeft, totalsRow);
        totalsLine.getStyleClass().add("heatmap-body");
        HBox.setHgrow(totalsRow, Priority.ALWAYS);

        FontIcon tipIcon = new FontIcon("far-lightbulb");
        tipIcon.setIconSize(14);
        tipIcon.getStyleClass().add("heatmap-tip-icon");

        StackPane tipBadge = new StackPane(tipIcon);
        tipBadge.getStyleClass().add("heatmap-tip-badge");
        tipBadge.setMinSize(26, 26);
        tipBadge.setPrefSize(26, 26);

        Label tipText = new Label("Consejo: Haz clic en cualquier celda para ver el detalle y análisis completo.");
        tipText.getStyleClass().add("heatmap-tip-text");
        tipText.setWrapText(true);
        tipText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tipText, Priority.ALWAYS);

        HBox tip = new HBox(10, tipBadge, tipText);
        tip.getStyleClass().add("heatmap-tip");
        tip.setAlignment(Pos.CENTER_LEFT);
        tip.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(12, topRow, body, totalsLine, tip);
        content.getStyleClass().add("heatmap-content");
        content.setPadding(new Insets(10, 12, 12, 12));
        content.setMinWidth(720);

        ScrollPane scroller = new ScrollPane(content);
        scroller.getStyleClass().add("heatmap-scroller");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPannable(false);

        VBox root = new VBox(12, SummaryContentBlock.build(scroller));
        root.getStyleClass().add("heatmap-root");
        root.setMinWidth(0);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return root;
    }

    private static long sumAbs(long[] cents) {
        if (cents == null) {
            return 0;
        }
        long s = 0;
        for (int i = 1; i <= 12 && i < cents.length; i++) {
            s += Math.abs(cents[i]);
        }
        return s;
    }

    private static void refreshSelectionStyles(VBox matrix, String selectedKey) {
        if (matrix == null) {
            return;
        }
        for (Node n : matrix.getChildren()) {
            if (!(n instanceof HBox row)) {
                continue;
            }
            if (row.getChildren().isEmpty()) {
                continue;
            }
            Node maybeCells = row.getChildren().get(0);
            if (!(maybeCells instanceof HBox cells)) {
                continue;
            }
            for (Node c : cells.getChildren()) {
                Object key = c.getUserData();
                boolean sel = selectedKey != null && selectedKey.equals(key);
                if (sel) {
                    if (!c.getStyleClass().contains("heatmap-dot-selected")) {
                        c.getStyleClass().add("heatmap-dot-selected");
                    }
                } else {
                    c.getStyleClass().remove("heatmap-dot-selected");
                }
            }
        }
    }

    private static List<DataRow> loadRows(
        String userUid,
        TransactionRepository txRepo,
        int year,
        String kind,
        String accountId
    ) {
        Map<String, DataRow> byCat = new HashMap<>();
        try {
            List<TransactionRepository.MonthlyCategoryTotal> data = txRepo.listMonthlyTotalsByRootCategory(userUid, accountId, year, kind);
            for (TransactionRepository.MonthlyCategoryTotal row : data) {
                DataRow r = byCat.computeIfAbsent(row.rootCategoryId(), id -> new DataRow(id, row.rootCategoryName(), new long[13]));
                int m = row.month();
                if (m >= 1 && m <= 12) {
                    r.monthCents[m] = row.totalAmountCents();
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(byCat.values());
    }

    private static int levelFor(double ratio, long cents) {
        if (cents == 0 || ratio <= 0.000001) {
            return 0;
        }
        double r = Math.max(0.0, Math.min(1.0, ratio));
        if (r < 0.15) {
            return 1;
        }
        if (r < 0.30) {
            return 2;
        }
        if (r < 0.50) {
            return 3;
        }
        if (r < 0.70) {
            return 4;
        }
        return 5;
    }

    private static Color colorFor(String kind, int level) {
        double a = switch (level) {
            case 0 -> 0.05;
            case 1 -> 0.10;
            case 2 -> 0.26;
            case 3 -> 0.44;
            case 4 -> 0.64;
            default -> 0.88;
        };
        // Avoid aggressive red/green. Use a fintech blue scale and a secondary indigo.
        Color base = "INCOME".equalsIgnoreCase(kind)
            ? Color.rgb(16, 185, 129) // muted emerald
            : Color.rgb(59, 130, 246); // fintech blue
        return Color.color(base.getRed(), base.getGreen(), base.getBlue(), a);
    }

    private static String cap(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.trim();
        Locale esCo = Locale.forLanguageTag("es-CO");
        return t.substring(0, 1).toUpperCase(esCo) + t.substring(1);
    }

    private static String formatCompactMoney(long cents, String currencyCode) {
        long absCents = Math.abs(cents);
        double abs = absCents / 100.0;

        String symbol = DashboardFormatters.currencySymbol(currencyCode);
        boolean neg = cents < 0;
        String sign = neg ? "-" : "";

        if (abs < 1000.0) {
            return DashboardFormatters.formatMoney(cents, currencyCode);
        }

        double value;
        String suffix;
        if (abs >= 1_000_000_000.0) {
            value = abs / 1_000_000_000.0;
            suffix = "B";
        } else if (abs >= 1_000_000.0) {
            value = abs / 1_000_000.0;
            suffix = "M";
        } else {
            value = abs / 1_000.0;
            suffix = "K";
        }

        java.text.DecimalFormat df = new java.text.DecimalFormat("0.##", new java.text.DecimalFormatSymbols(java.util.Locale.ROOT));
        return sign + symbol + df.format(value) + suffix;
    }
}
