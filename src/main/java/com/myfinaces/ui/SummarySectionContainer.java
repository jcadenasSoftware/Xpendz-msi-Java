package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

public final class SummarySectionContainer {

    private SummarySectionContainer() {
    }

    public static Node build(Node content) {
        VBox container = new VBox(content);
        container.getStyleClass().add("summary-section-container");
        container.setPadding(new Insets(0));
        container.setSpacing(0);
        container.setMinWidth(0);
        return container;
    }

    public static Node buildWithSpacing(Node content, double spacing) {
        VBox container = new VBox(spacing, content);
        container.getStyleClass().add("summary-section-container");
        container.setPadding(new Insets(0));
        container.setMinWidth(0);
        return container;
    }
}
