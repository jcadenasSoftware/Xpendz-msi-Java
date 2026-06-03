package com.myfinaces.ui;

import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.util.List;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;

public final class ExpensesIncomeChart {

    private ExpensesIncomeChart() {
    }

    public static Node build(String userUid, TransactionRepository txRepo) {
        int year = LocalDate.now().getYear();
        
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Mes");
        xAxis.getStyleClass().add("summary-chart-axis");
        
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Monto");
        yAxis.getStyleClass().add("summary-chart-axis");
        yAxis.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number value) {
                if (value == null) return "";
                double d = value.doubleValue();
                // Values are already in millions (divided by 1,000,000)
                if (d >= 1_000) {
                    return String.format("%.0fB", d / 1_000);
                } else if (d >= 1) {
                    return String.format("%.0fM", d);
                } else if (d >= 0.001) {
                    return String.format("%.0fk", d * 1_000);
                } else {
                    return String.format("%.0f", d);
                }
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });
        
        LineChart<String, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.getStyleClass().add("summary-line-chart");
        chart.setLegendVisible(false);
        chart.setCreateSymbols(true);
        chart.setAnimated(false);
        
        XYChart.Series<String, Number> incomeSeries = new XYChart.Series<>();
        incomeSeries.setName("Ingresos");
        
        XYChart.Series<String, Number> expenseSeries = new XYChart.Series<>();
        expenseSeries.setName("Gastos");
        
        String[] monthNames = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", 
                                "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};
        
        for (int m = 1; m <= 12; m++) {
            String monthName = monthNames[m - 1];
            
            long incomeCents = 0;
            long expenseCents = 0;
            
            try {
                List<TransactionRepository.MonthlyCategoryTotal> incomeData = 
                    txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, "INCOME");
                for (TransactionRepository.MonthlyCategoryTotal row : incomeData) {
                    if (row.month() == m) {
                        incomeCents += row.totalAmountCents();
                    }
                }
                
                List<TransactionRepository.MonthlyCategoryTotal> expenseData = 
                    txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, "EXPENSE");
                for (TransactionRepository.MonthlyCategoryTotal row : expenseData) {
                    if (row.month() == m) {
                        expenseCents += row.totalAmountCents();
                    }
                }
            } catch (Exception ignored) {
            }
            
            double incomeMillions = incomeCents / 1000000.0;
            double expenseMillions = expenseCents / 1000000.0;

            XYChart.Data<String, Number> inc = new XYChart.Data<>(monthName, incomeMillions);
            XYChart.Data<String, Number> exp = new XYChart.Data<>(monthName, expenseMillions);
            incomeSeries.getData().add(inc);
            expenseSeries.getData().add(exp);

            final String mm = monthName;
            final long incC = incomeCents;
            final long expC = expenseCents;
            Platform.runLater(() -> {
                if (inc.getNode() != null) {
                    inc.getNode().getStyleClass().add("series-income");
                    Tooltip.install(inc.getNode(), new Tooltip(String.format("%s\nIngresos: %s", mm, DashboardFormatters.formatMoney(incC, "COP"))));
                }
                if (exp.getNode() != null) {
                    exp.getNode().getStyleClass().add("series-expense");
                    Tooltip.install(exp.getNode(), new Tooltip(String.format("%s\nGastos: %s", mm, DashboardFormatters.formatMoney(expC, "COP"))));
                }
            });
        }

        chart.getData().add(incomeSeries);
        chart.getData().add(expenseSeries);
        
        return chart;
    }

    public static Node buildBars(String userUid, TransactionRepository txRepo) {
        int year = LocalDate.now().getYear();

        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Mes");
        xAxis.getStyleClass().add("summary-chart-axis");

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Monto");
        yAxis.getStyleClass().add("summary-chart-axis");
        yAxis.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number value) {
                if (value == null) {
                    return "";
                }
                double d = value.doubleValue();
                if (d >= 1_000) {
                    return String.format("%.0fB", d / 1_000);
                } else if (d >= 1) {
                    return String.format("%.0fM", d);
                } else if (d >= 0.001) {
                    return String.format("%.0fk", d * 1_000);
                } else {
                    return String.format("%.0f", d);
                }
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });

        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.getStyleClass().add("summary-bar-chart");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCategoryGap(10);
        chart.setBarGap(3);

        XYChart.Series<String, Number> incomeSeries = new XYChart.Series<>();
        incomeSeries.setName("Ingresos");

        XYChart.Series<String, Number> expenseSeries = new XYChart.Series<>();
        expenseSeries.setName("Gastos");

        String[] monthNames = {"Ene", "Feb", "Mar", "Abr", "May", "Jun",
            "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};

        List<TransactionRepository.MonthlyCategoryTotal> incomeData;
        List<TransactionRepository.MonthlyCategoryTotal> expenseData;
        try {
            incomeData = txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, "INCOME");
        } catch (Exception ignored) {
            incomeData = List.of();
        }
        try {
            expenseData = txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, "EXPENSE");
        } catch (Exception ignored) {
            expenseData = List.of();
        }

        for (int m = 1; m <= 12; m++) {
            String monthName = monthNames[m - 1];

            long incomeCents = 0;
            long expenseCents = 0;

            for (TransactionRepository.MonthlyCategoryTotal row : incomeData) {
                if (row != null && row.month() == m) {
                    incomeCents += row.totalAmountCents();
                }
            }
            for (TransactionRepository.MonthlyCategoryTotal row : expenseData) {
                if (row != null && row.month() == m) {
                    expenseCents += row.totalAmountCents();
                }
            }

            double incomeMillions = incomeCents / 1000000.0;
            double expenseMillions = expenseCents / 1000000.0;

            XYChart.Data<String, Number> inc = new XYChart.Data<>(monthName, incomeMillions);
            XYChart.Data<String, Number> exp = new XYChart.Data<>(monthName, expenseMillions);
            incomeSeries.getData().add(inc);
            expenseSeries.getData().add(exp);

            final String mm = monthName;
            final long incC = incomeCents;
            final long expC = expenseCents;
            Platform.runLater(() -> {
                if (inc.getNode() != null) {
                    inc.getNode().getStyleClass().add("series-income");
                    Tooltip.install(inc.getNode(), new Tooltip(String.format("%s\nIngresos: %s", mm, DashboardFormatters.formatMoney(incC, "COP"))));
                }
                if (exp.getNode() != null) {
                    exp.getNode().getStyleClass().add("series-expense");
                    Tooltip.install(exp.getNode(), new Tooltip(String.format("%s\nGastos: %s", mm, DashboardFormatters.formatMoney(expC, "COP"))));
                }
            });
        }

        chart.getData().add(incomeSeries);
        chart.getData().add(expenseSeries);

        return chart;
    }
}
