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

public final class SummaryInsightCard {

    private SummaryInsightCard() {
    }

    public static Node build(
        String label,
        String value,
        String context,
        String iconLiteral,
        String toneStyleClass
    ) {
        Label l = new Label(label);
        l.getStyleClass().add("summary-insight-label");

        Label v = new Label(value);
        v.getStyleClass().add("summary-insight-value");

        Label c = new Label(context);
        c.getStyleClass().add("summary-insight-context");
        c.setWrapText(true);

        VBox text = new VBox(6, l, v, c);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        FontIcon icon = new FontIcon(iconLiteral == null ? "fas-lightbulb" : iconLiteral);
        icon.getStyleClass().add("summary-insight-icon");

        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("summary-insight-badge");
        if (toneStyleClass != null && !toneStyleClass.isBlank()) {
            badge.getStyleClass().add(toneStyleClass);
        }

        HBox header = new HBox(10, badge, text);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMinWidth(0);

        VBox card = new VBox(header);
        card.getStyleClass().add("summary-insight-card");
        card.setPadding(new Insets(14));
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);

        return card;
    }
}
