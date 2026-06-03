package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

public final class HeatmapLegend {

    private HeatmapLegend() {
    }

    public static Node build() {
        HBox bar = new HBox(6);
        bar.getStyleClass().add("heatmap-legend-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Region s1 = swatch("heatmap-swatch-1");
        Region s2 = swatch("heatmap-swatch-2");
        Region s3 = swatch("heatmap-swatch-3");
        Region s4 = swatch("heatmap-swatch-4");
        Region s5 = swatch("heatmap-swatch-5");

        Label low = new Label("Menor");
        low.getStyleClass().add("text-secondary");
        Label high = new Label("Mayor");
        high.getStyleClass().add("text-secondary");

        HBox root = new HBox(10, low, s1, s2, s3, s4, s5, high);
        root.getStyleClass().add("heatmap-legend");
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(0));
        return root;
    }

    private static Region swatch(String cssClass) {
        Region r = new Region();
        r.getStyleClass().addAll("heatmap-swatch", cssClass);
        r.setMinSize(16, 10);
        r.setPrefSize(16, 10);
        return r;
    }
}
