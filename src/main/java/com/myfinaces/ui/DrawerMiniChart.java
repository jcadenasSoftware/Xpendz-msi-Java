package com.myfinaces.ui;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;

public final class DrawerMiniChart {

    private final VBox root;
    private final StackPane chart;
    private final HBox legend;
    private final Polygon area;
    private final Polyline line;
    private final Circle dot;

    public DrawerMiniChart() {
        area = new Polygon();
        area.getStyleClass().add("sid-mini-area");

        line = new Polyline();
        line.getStyleClass().add("sid-mini-line");

        dot = new Circle(3);
        dot.getStyleClass().add("sid-mini-dot");

        chart = new StackPane(area, line, dot);
        chart.getStyleClass().add("sid-mini-chart");
        chart.setPadding(new Insets(6, 8, 6, 8));
        chart.setMinWidth(0);
        chart.setMaxWidth(Double.MAX_VALUE);
        chart.setMinHeight(72);
        chart.setPrefHeight(72);
        chart.setMaxHeight(72);
        chart.widthProperty().addListener((o, a, b) -> layout(lastSeries, lastFocusMonth, lastN));
        chart.heightProperty().addListener((o, a, b) -> layout(lastSeries, lastFocusMonth, lastN));

        legend = new HBox();
        legend.setAlignment(Pos.CENTER);
        legend.getStyleClass().add("sid-mini-legend");
        legend.setPadding(new Insets(2, 2, 0, 2));
        legend.setMinWidth(0);
        legend.setMaxWidth(Double.MAX_VALUE);

        root = new VBox(6, chart, legend);
        root.setFillWidth(true);
        root.setMinWidth(0);
        root.setMaxWidth(Double.MAX_VALUE);
    }

    private long[] lastSeries = new long[13];
    private Integer lastFocusMonth;
    private int lastN = 12;

    public Node getNode() {
        return root;
    }

    public void setSeries(long[] monthsCents, Integer focusMonth) {
        setSeriesLastN(monthsCents, focusMonth, 12);
    }

    public void setSeriesLastN(long[] monthsCents, Integer focusMonth, int lastN) {
        if (monthsCents == null || monthsCents.length < 13) {
            this.lastSeries = new long[13];
        } else {
            this.lastSeries = monthsCents;
        }
        this.lastFocusMonth = focusMonth;
        this.lastN = Math.max(2, Math.min(12, lastN));
        layout(this.lastSeries, this.lastFocusMonth, this.lastN);
    }

    private void layout(long[] monthsCents, Integer focusMonth, int lastN) {
        double w = Math.max(1, chart.getWidth() - 16);
        double h = Math.max(1, chart.getHeight() - 12);

        int mFocus = focusMonth == null ? 12 : Math.max(1, Math.min(12, focusMonth));
        int start = Math.max(1, mFocus - (lastN - 1));
        int end = mFocus;

        legend.getChildren().clear();
        Locale esCo = Locale.forLanguageTag("es-CO");
        for (int m = start; m <= end; m++) {
            Label l = new Label(Month.of(m).getDisplayName(TextStyle.SHORT, esCo));
            l.getStyleClass().add("sid-mini-legend-item");
            l.setMaxWidth(Double.MAX_VALUE);
            l.setAlignment(Pos.CENTER);
            HBox.setHgrow(l, Priority.ALWAYS);
            legend.getChildren().add(l);
        }

        long max = 0;
        for (int i = start; i <= end; i++) {
            max = Math.max(max, Math.abs(monthsCents[i]));
        }
        if (max <= 0) {
            max = 1;
        }

        ListBuilder pts = new ListBuilder();
        int span = Math.max(1, end - start);
        for (int m = start; m <= end; m++) {
            double x = (w * (m - start)) / (double) span;
            double y = h - (h * (Math.abs(monthsCents[m]) / (double) max));
            pts.add(x + 8.0, y + 6.0);
        }
        line.getPoints().setAll(pts.values);

        // Build a filled area under the line for a modern analytic look.
        ListBuilder areaPts = new ListBuilder();
        // Start at bottom-left
        areaPts.add(8.0, h + 6.0);
        for (int m = start; m <= end; m++) {
            double x = (w * (m - start)) / (double) span;
            double y = h - (h * (Math.abs(monthsCents[m]) / (double) max));
            areaPts.add(x + 8.0, y + 6.0);
        }
        // End at bottom-right
        areaPts.add(w + 8.0, h + 6.0);
        area.getPoints().setAll(areaPts.values);

        double dx = (w * (mFocus - start)) / (double) span;
        double dy = h - (h * (Math.abs(monthsCents[mFocus]) / (double) max));
        dot.setTranslateX(dx - (w / 2.0) + 8.0);
        dot.setTranslateY(dy - (h / 2.0) + 6.0);
    }

    private static final class ListBuilder {
        private final java.util.ArrayList<Double> values = new java.util.ArrayList<>();

        void add(double x, double y) {
            values.add(x);
            values.add(y);
        }
    }
}
