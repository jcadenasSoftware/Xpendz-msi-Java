package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class HeatmapTooltip {

    private HeatmapTooltip() {
    }

    public static Tooltip build(String category, String month, String value, String pct) {
        Label title = new Label(category == null ? "" : category);
        title.getStyleClass().add("heatmap-tooltip-title");
        title.setMaxWidth(Double.MAX_VALUE);

        Label meta = new Label(month == null ? "" : month);
        meta.getStyleClass().add("heatmap-tooltip-meta");

        Label val = new Label(value == null ? "" : value);
        val.getStyleClass().add("heatmap-tooltip-value");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, meta, spacer, val);
        row.setAlignment(Pos.CENTER_LEFT);

        Label pctLabel = new Label(pct == null ? "" : pct);
        pctLabel.getStyleClass().add("heatmap-tooltip-pct");

        VBox root = new VBox(6, title, row, pctLabel);
        root.getStyleClass().add("heatmap-tooltip-root");
        root.setPadding(new Insets(2, 2, 2, 2));

        Tooltip t = new Tooltip();
        t.setText("");
        t.setGraphic(root);
        t.getStyleClass().add("heatmap-tooltip");
        return t;
    }
}
