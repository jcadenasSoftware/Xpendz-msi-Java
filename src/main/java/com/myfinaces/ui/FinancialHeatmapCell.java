package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;

public final class FinancialHeatmapCell {

    private FinancialHeatmapCell() {
    }

    public static Label build(String text, Color fill, boolean currentMonth) {
        return build(text, fill, currentMonth, 54, 28, 8);
    }

    public static Label build(
        String text,
        Color fill,
        boolean currentMonth,
        double width,
        double height,
        double radius
    ) {
        Label cell = new Label(text);
        cell.getStyleClass().add("heatmap-cell");
        cell.setAlignment(Pos.CENTER);
        cell.setMinWidth(width);
        cell.setPrefWidth(width);
        cell.setMinHeight(height);
        cell.setPrefHeight(height);
        cell.setPadding(new Insets(0));
        cell.setStyle("-fx-background-color: " + toRgba(fill) + "; -fx-background-radius: " + radius + ";");
        if (currentMonth) {
            cell.getStyleClass().add("heatmap-current-month");
        }
        return cell;
    }

    private static String toRgba(Color c) {
        if (c == null) {
            return "transparent";
        }
        int r = (int) Math.round(c.getRed() * 255.0);
        int g = (int) Math.round(c.getGreen() * 255.0);
        int b = (int) Math.round(c.getBlue() * 255.0);
        double a = Math.max(0.0, Math.min(1.0, c.getOpacity()));
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r, g, b, a);
    }
}
