package com.myfinaces.ui;

import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.chart.PieChart;

public final class CategoryDistributionChart {

    private CategoryDistributionChart() {
    }

    public static Node build(String userUid, TransactionRepository txRepo) {
        int year = LocalDate.now().getYear();
        
        PieChart chart = new PieChart();
        chart.getStyleClass().add("summary-donut-chart");
        chart.setLegendVisible(false);
        chart.setLabelsVisible(true);
        chart.setStartAngle(90);
        
        Map<String, Long> categoryTotals = new HashMap<>();
        
        try {
            List<TransactionRepository.MonthlyCategoryTotal> expenseData = 
                txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, "EXPENSE");
            
            for (TransactionRepository.MonthlyCategoryTotal row : expenseData) {
                String categoryName = row.rootCategoryName();
                if (categoryName != null && !categoryName.isBlank()) {
                    categoryTotals.merge(categoryName, row.totalAmountCents(), Long::sum);
                }
            }
        } catch (Exception ignored) {
        }
        
        List<PieChart.Data> pieData = new ArrayList<>();
        for (Map.Entry<String, Long> entry : categoryTotals.entrySet()) {
            String label = entry.getKey();
            long cents = entry.getValue();
            double millions = cents / 1000000.0;
            pieData.add(new PieChart.Data(label, millions));
        }
        
        chart.getData().addAll(pieData);
        
        return chart;
    }
}
