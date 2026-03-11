package com.myfinaces.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DashboardBalancesPane {

    private DashboardBalancesPane() {
    }

    public record Parts(
        Label totalValue,
        VBox totalCard,
        VBox accountsBox,
        VBox goalsBox,
        ScrollPane accountsScroll,
        ScrollPane goalsScroll,
        AtomicBoolean hideTotalBalance
    ) {
    }

    public static Parts build(AtomicReference<Runnable> refreshBalancesRef) {
        Label totalCaption = new Label("Saldo total");
        totalCaption.getStyleClass().add("text-secondary");
        Label totalValue = new Label();
        totalValue.getStyleClass().add("account-name");
        totalValue.getStyleClass().add("money-neutral");
        totalValue.getStyleClass().add("dashboard-total-value");

        AtomicBoolean hideTotalBalance = new AtomicBoolean(true);
        Button toggleTotal = new Button("");
        toggleTotal.getStyleClass().add("btn-primary-soft");
        toggleTotal.setGraphic(new FontIcon("far-eye"));
        toggleTotal.setTooltip(new Tooltip("Mostrar saldo"));
        toggleTotal.setMinWidth(42);
        toggleTotal.setPrefWidth(42);
        toggleTotal.setMinHeight(34);
        toggleTotal.setPrefHeight(34);
        toggleTotal.setOnAction(e -> {
            hideTotalBalance.set(!hideTotalBalance.get());

            toggleTotal.setText("");
            toggleTotal.setGraphic(new FontIcon(hideTotalBalance.get() ? "far-eye" : "far-eye-slash"));
            toggleTotal.setTooltip(new Tooltip(hideTotalBalance.get() ? "Mostrar saldo" : "Ocultar saldo"));
            Runnable r = refreshBalancesRef.get();
            if (r != null) {
                r.run();
            }
        });

        HBox totalTop = new HBox(10, totalCaption);
        totalTop.setAlignment(Pos.CENTER_LEFT);

        Region totalValueSpacer = new Region();
        HBox.setHgrow(totalValueSpacer, Priority.ALWAYS);
        HBox totalRow = new HBox(10, totalValue, totalValueSpacer, toggleTotal);
        totalRow.setAlignment(Pos.CENTER_LEFT);

        VBox totalCard = new VBox(6, totalTop, totalRow);
        totalCard.getStyleClass().addAll("card", "summary-card");

        VBox accountsBox = new VBox(6);
        accountsBox.getStyleClass().add("accounts-list");

        VBox goalsBox = new VBox(6);
        goalsBox.getStyleClass().add("accounts-list");

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

        return new Parts(totalValue, totalCard, accountsBox, goalsBox, accountsScroll, goalsScroll, hideTotalBalance);
    }
}
