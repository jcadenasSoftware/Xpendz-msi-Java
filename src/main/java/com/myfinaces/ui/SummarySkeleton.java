package com.myfinaces.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public final class SummarySkeleton {

    private SummarySkeleton() {
    }

    public static Node buildKPICard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("summary-kpi-card");
        card.setPadding(new Insets(14));
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMinHeight(108);

        HBox headerRow = new HBox(10);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox textSkeleton = new VBox(8);
        textSkeleton.setMinWidth(0);
        HBox.setHgrow(textSkeleton, Priority.ALWAYS);

        Rectangle titleRect = new Rectangle(80, 12);
        titleRect.getStyleClass().add("skeleton-shimmer");
        titleRect.setArcWidth(4);
        titleRect.setArcHeight(4);

        Rectangle valueRect = new Rectangle(100, 20);
        valueRect.getStyleClass().add("skeleton-shimmer");
        valueRect.setArcWidth(4);
        valueRect.setArcHeight(4);

        Rectangle metaRect = new Rectangle(60, 10);
        metaRect.getStyleClass().add("skeleton-shimmer");
        metaRect.setArcWidth(4);
        metaRect.setArcHeight(4);

        textSkeleton.getChildren().addAll(titleRect, valueRect, metaRect);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Rectangle iconRect = new Rectangle(24, 24);
        iconRect.getStyleClass().add("skeleton-shimmer");
        iconRect.setArcWidth(6);
        iconRect.setArcHeight(6);

        headerRow.getChildren().addAll(textSkeleton, spacer, iconRect);

        card.getChildren().add(headerRow);

        startShimmerAnimation(card);

        return card;
    }

    public static Node buildChartCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("summary-chart-card");
        card.setPadding(new Insets(16));
        card.setMinWidth(0);

        Rectangle titleRect = new Rectangle(120, 16);
        titleRect.getStyleClass().add("skeleton-shimmer");
        titleRect.setArcWidth(4);
        titleRect.setArcHeight(4);

        Rectangle chartRect = new Rectangle(300, 180);
        chartRect.getStyleClass().add("skeleton-shimmer");
        chartRect.setArcWidth(8);
        chartRect.setArcHeight(8);

        card.getChildren().addAll(titleRect, chartRect);

        startShimmerAnimation(card);

        return card;
    }

    public static Node buildTableRow() {
        HBox row = new HBox(8);
        row.getStyleClass().add("summary-row-skeleton");
        row.setMinWidth(0);

        for (int i = 0; i < 5; i++) {
            Rectangle cell = new Rectangle(60 + (i * 20), 16);
            cell.getStyleClass().add("skeleton-shimmer");
            cell.setArcWidth(4);
            cell.setArcHeight(4);
            HBox.setHgrow(cell, Priority.ALWAYS);
            row.getChildren().add(cell);
        }

        startShimmerAnimation(row);

        return row;
    }

    private static void startShimmerAnimation(Node container) {
        Timeline shimmer = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(container.opacityProperty(), 0.6)),
            new KeyFrame(Duration.millis(800), new KeyValue(container.opacityProperty(), 0.8)),
            new KeyFrame(Duration.millis(1600), new KeyValue(container.opacityProperty(), 0.6))
        );
        shimmer.setCycleCount(Timeline.INDEFINITE);
        shimmer.play();
    }

    public static void fadeIn(Node node, Duration duration) {
        FadeTransition ft = new FadeTransition(duration, node);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();
    }

    public static void fadeOut(Node node, Duration duration) {
        FadeTransition ft = new FadeTransition(duration, node);
        ft.setFromValue(node.getOpacity());
        ft.setToValue(0);
        ft.play();
    }
}
