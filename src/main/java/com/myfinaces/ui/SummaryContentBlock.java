package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

public final class SummaryContentBlock {

    private SummaryContentBlock() {
    }

    public static Node build(Node content) {
        VBox block = new VBox(content);
        block.getStyleClass().add("summary-content-block");
        block.setSpacing(0);
        block.setMinWidth(0);
        return block;
    }

    public static Node buildWithoutPadding(Node content) {
        VBox block = new VBox(content);
        block.getStyleClass().add("summary-content-block");
        block.setSpacing(0);
        block.setMinWidth(0);
        return block;
    }

    public static Node buildWithSpacing(Node content, double spacing) {
        VBox block = new VBox(spacing, content);
        block.getStyleClass().add("summary-content-block");
        block.setPadding(new Insets(16));
        block.setMinWidth(0);
        return block;
    }
}
