package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

public final class SummaryGridLayout {

    private SummaryGridLayout() {
    }

    public static Node build(Node... sections) {
        VBox layout = new VBox(sections);
        layout.getStyleClass().add("summary-grid-layout");
        layout.setPadding(new Insets(0));
        layout.setSpacing(24);
        layout.setMinWidth(0);
        return layout;
    }

    public static Node buildWithSpacing(double spacing, Node... sections) {
        VBox layout = new VBox(spacing, sections);
        layout.getStyleClass().add("summary-grid-layout");
        layout.setPadding(new Insets(0));
        layout.setMinWidth(0);
        return layout;
    }
}
