package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

public final class SummaryKPICard {

    private SummaryKPICard() {
    }

    public static Node build(String label, String value, String meta, String iconLiteral) {
        return build(label, value, meta, iconLiteral, null);
    }

    public static Node build(String label, String value, String meta, String iconLiteral, String badgeStyleClass) {
        Label l = new Label(label);
        l.getStyleClass().add("summary-kpi-label");

        Label v = new Label(value);
        v.getStyleClass().add("summary-kpi-value");

        Label m = new Label(meta);
        m.getStyleClass().add("summary-kpi-meta");
        m.setWrapText(true);

        VBox text = new VBox(6, l, v, m);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);

        FontIcon icon = new FontIcon(iconLiteral == null ? "fas-chart-line" : iconLiteral);
        icon.getStyleClass().add("summary-kpi-icon");

        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("summary-kpi-badge");
        if (badgeStyleClass != null && !badgeStyleClass.isBlank()) {
            badge.getStyleClass().add(badgeStyleClass);
        }

        HBox headerRow = new HBox(10, badge, text);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        VBox card = new VBox(headerRow);
        card.getStyleClass().add("summary-kpi-card");
        card.setPadding(new Insets(14));
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMinHeight(108);

        return card;
    }
}
