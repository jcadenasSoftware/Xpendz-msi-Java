package com.myfinaces.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DashboardBalancesPane {

    private DashboardBalancesPane() {
    }

    public record Parts(
        Label totalValue,
        HBox totalTrend,
        StackPane totalCard,
        Label totalTrendHint,
        Pane totalTrendBackdrop,
        Polyline totalWave1,
        Polyline totalWave2,
        Polygon totalSparkArea,
        Polyline totalSparkGlow,
        Polyline totalSparkline,
        Circle totalSparkDot,
        VBox accountsBox,
        VBox goalsBox,
        ScrollPane accountsScroll,
        ScrollPane goalsScroll,
        AtomicBoolean hideTotalBalance,
        AtomicReference<FontIcon> trendIconRef,
        AtomicReference<Label> trendTextRef,
        AtomicReference<Label> trendBadgeRef
    ) {
    }

    public static Parts build(AtomicReference<Runnable> refreshBalancesRef) {
        Label totalCaption = new Label("Saldo total");
        totalCaption.getStyleClass().add("dashboard-total-caption");
        Label totalValue = new Label();
        totalValue.getStyleClass().add("dashboard-total-value");

        FontIcon trendIcon = new FontIcon("fas-arrow-up");
        trendIcon.getStyleClass().add("icon");
        trendIcon.setIconSize(14);
        Label trendText = new Label("Tendencia");
        Label trendBadge = new Label();
        trendBadge.getStyleClass().add("trend-badge-neutral");
        HBox totalTrend = new HBox(8, trendIcon, trendText, trendBadge);
        totalTrend.getStyleClass().add("dashboard-total-trend");
        totalTrend.setAlignment(Pos.CENTER_LEFT);

        Label trendHint = new Label("Línea de tendencia del mes (movimientos diarios)");
        trendHint.getStyleClass().add("dashboard-total-hint");
        trendHint.setWrapText(true);
        trendHint.setMaxWidth(Double.MAX_VALUE);

        AtomicBoolean hideTotalBalance = new AtomicBoolean(true);

        Region totalTopSpacer = new Region();
        HBox.setHgrow(totalTopSpacer, javafx.scene.layout.Priority.ALWAYS);

        trendHint.setMaxWidth(280);
        trendHint.setAlignment(Pos.CENTER_RIGHT);

        HBox totalTop = new HBox(10, totalCaption, totalTopSpacer, trendHint);
        totalTop.setAlignment(Pos.CENTER_LEFT);

        VBox totalCardContent = new VBox(8, totalTop, totalValue, totalTrend);
        totalCardContent.getStyleClass().add("dashboard-total-card-overlay");

        Pane trendBackdrop = new Pane();
        trendBackdrop.setMouseTransparent(true);

        Polyline wave1 = new Polyline();
        wave1.setMouseTransparent(true);

        Polyline wave2 = new Polyline();
        wave2.setMouseTransparent(true);

        Polygon sparkArea = new Polygon();
        sparkArea.setMouseTransparent(true);

        Polyline sparkGlow = new Polyline();
        sparkGlow.setMouseTransparent(true);

        Polyline sparkline = new Polyline();
        sparkline.setMouseTransparent(true);

        Circle sparkDot = new Circle();
        sparkDot.setMouseTransparent(true);

        trendBackdrop.getChildren().addAll(wave1, wave2, sparkArea, sparkGlow, sparkline, sparkDot);

        StackPane totalCard = new StackPane(trendBackdrop, totalCardContent);
        totalCard.getStyleClass().addAll("card", "dashboard-total-card");
        StackPane.setAlignment(totalCardContent, Pos.TOP_LEFT);
        StackPane.setAlignment(trendBackdrop, Pos.TOP_LEFT);

        totalCard.setMinWidth(0);
        totalCard.setMaxWidth(Double.MAX_VALUE);

        totalCard.setMinHeight(140);
        totalCard.setPrefHeight(140);
        totalCard.setMaxHeight(Region.USE_PREF_SIZE);

        trendBackdrop.prefWidthProperty().bind(totalCard.widthProperty());
        trendBackdrop.prefHeightProperty().bind(totalCard.heightProperty());
        trendBackdrop.minWidthProperty().bind(totalCard.widthProperty());
        trendBackdrop.minHeightProperty().bind(totalCard.heightProperty());

        VBox accountsBox = new VBox(6);
        accountsBox.getStyleClass().add("accounts-list");
        accountsBox.setMinWidth(0);

        VBox goalsBox = new VBox(6);
        goalsBox.getStyleClass().add("accounts-list");
        goalsBox.setMinWidth(0);

        ScrollPane accountsScroll = new ScrollPane(accountsBox);
        accountsScroll.setFitToWidth(true);
        accountsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        accountsScroll.getStyleClass().addAll("card", "content-card");

        ScrollPane goalsScroll = new ScrollPane(goalsBox);
        goalsScroll.setFitToWidth(true);
        goalsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        goalsScroll.getStyleClass().addAll("card", "content-card");
        goalsScroll.setMinViewportHeight(240);
        goalsScroll.setPrefViewportHeight(260);
        goalsScroll.setVisible(false);
        goalsScroll.setManaged(false);

        return new Parts(totalValue, totalTrend, totalCard, trendHint, trendBackdrop, wave1, wave2, sparkArea, sparkGlow, sparkline, sparkDot, accountsBox, goalsBox, accountsScroll, goalsScroll, hideTotalBalance,
            new AtomicReference<>(trendIcon), new AtomicReference<>(trendText), new AtomicReference<>(trendBadge));
    }
}
