package com.myfinaces.ui;

import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;

public final class MonthlyExpenseVariationChart {

    private MonthlyExpenseVariationChart() {
    }

    public static Node build(String userUid, TransactionRepository txRepo) {
        int year = LocalDate.now().getYear();

        CategoryAxis xAxis = new CategoryAxis();
        xAxis.getStyleClass().add("summary-chart-axis");

        NumberAxis yAxis = new NumberAxis();
        yAxis.getStyleClass().add("summary-chart-axis");
        yAxis.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number value) {
                if (value == null) {
                    return "";
                }
                return String.format("%.0f%%", value.doubleValue());
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });

        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.getStyleClass().add("summary-variation-chart");
        chart.setLegendVisible(false);
        chart.setCategoryGap(10);
        chart.setBarGap(4);

        Map<Integer, Long> totalsByMonth = new HashMap<>();
        try {
            List<TransactionRepository.MonthlyCategoryTotal> rows = txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, "EXPENSE");
            for (TransactionRepository.MonthlyCategoryTotal row : rows) {
                if (row == null) {
                    continue;
                }
                int m = row.month();
                if (m < 1 || m > 12) {
                    continue;
                }
                totalsByMonth.merge(m, row.totalAmountCents(), Long::sum);
            }
        } catch (Exception ignored) {
        }

        String[] monthNames = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};

        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName("Variación");

        for (int m = 1; m <= 12; m++) {
            long current = totalsByMonth.getOrDefault(m, 0L);
            long prev = m <= 1 ? 0L : totalsByMonth.getOrDefault(m - 1, 0L);

            double pct;
            if (m <= 1 || prev <= 0L) {
                pct = 0.0;
            } else {
                pct = ((double) current / (double) prev) - 1.0;
            }

            double v = pct * 100.0;
            XYChart.Data<String, Number> d = new XYChart.Data<>(monthNames[m - 1], v);
            s.getData().add(d);

            final double vv = v;
            final String mm = monthNames[m - 1];

            Platform.runLater(() -> {
                if (d.getNode() == null) {
                    return;
                }
                d.getNode().getStyleClass().add(vv >= 0 ? "var-pos" : "var-neg");
                Tooltip.install(d.getNode(), new Tooltip(String.format("%s: %+.1f%%", mm, vv)));
            });
        }

        chart.getData().add(s);
        return chart;
    }
}
