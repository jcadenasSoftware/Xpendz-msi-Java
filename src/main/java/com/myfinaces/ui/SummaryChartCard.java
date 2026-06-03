package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public final class SummaryChartCard {

    private SummaryChartCard() {
    }

    public static Node build(String title, Node chart) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("summary-chart-title");

        VBox card = new VBox(12, titleLabel, chart);
        card.getStyleClass().add("summary-chart-card");
        card.setPadding(new Insets(16));
        card.setMinWidth(0);
        return card;
    }
}
