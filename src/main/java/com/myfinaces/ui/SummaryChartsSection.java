package com.myfinaces.ui;

import com.myfinaces.db.TransactionRepository;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryChartsSection {

    private SummaryChartsSection() {
    }

    public static Node build(String userUid, TransactionRepository txRepo) {
        Label sectionTitle = new Label("Gráficos");
        sectionTitle.getStyleClass().add("account-name");

        Node mainChartCard = SummaryChartCard.build(
            "Ingresos vs Gastos",
            ExpensesIncomeChart.build(userUid, txRepo)
        );
        if (mainChartCard instanceof VBox v) {
            v.getStyleClass().add("summary-chart-card-hero");
        }

        Node donutChartCard = SummaryChartCard.build(
            "Distribución por Categoría",
            CategoryDistributionChart.build(userUid, txRepo)
        );

        HBox secondaryRow = new HBox(16, donutChartCard);
        secondaryRow.getStyleClass().add("summary-chart-secondary-row");
        HBox.setHgrow(donutChartCard, Priority.ALWAYS);
        secondaryRow.setMinWidth(0);

        VBox charts = new VBox(16, mainChartCard, secondaryRow);
        charts.setMinWidth(0);

        VBox section = new VBox(12, sectionTitle, charts);
        section.setMinWidth(0);
        return section;
    }
}
