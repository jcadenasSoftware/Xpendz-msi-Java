package com.myfinaces.ui;

import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryTrendsSection {

    private SummaryTrendsSection() {
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo
    ) {
        Label sectionTitle = new Label("Tendencias");
        sectionTitle.getStyleClass().add("account-name");

        Node incomeVsExpense = SummaryChartCard.build(
            "Tendencia de ingresos vs gastos",
            ExpensesIncomeChart.build(userUid, txRepo)
        );
        if (incomeVsExpense instanceof VBox v) {
            v.getStyleClass().add("summary-chart-card-hero");
        }

        Node distribution = SummaryChartCard.build(
            "Distribución de gastos por categoría",
            CategoryDistributionChart.build(userUid, txRepo)
        );

        HBox topRow = new HBox(16, incomeVsExpense, distribution);
        topRow.getStyleClass().add("summary-trends-row");
        topRow.setMinWidth(0);
        HBox.setHgrow(incomeVsExpense, Priority.ALWAYS);
        HBox.setHgrow(distribution, Priority.ALWAYS);

        Node topCats = SummaryChartCard.build(
            "Top 5 categorías por gasto",
            SummaryTopCategoriesSection.build(userUid, txRepo, categoryRepo)
        );

        Node variation = SummaryChartCard.build(
            "Variación mensual de gastos",
            MonthlyExpenseVariationChart.build(userUid, txRepo)
        );

        HBox bottomRow = new HBox(16, topCats, variation);
        bottomRow.getStyleClass().add("summary-trends-row");
        bottomRow.setMinWidth(0);
        HBox.setHgrow(topCats, Priority.ALWAYS);
        HBox.setHgrow(variation, Priority.ALWAYS);

        VBox section = new VBox(12, sectionTitle, topRow, bottomRow);
        section.setMinWidth(0);
        return section;
    }
}
