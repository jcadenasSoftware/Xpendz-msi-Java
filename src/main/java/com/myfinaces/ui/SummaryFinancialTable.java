package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class SummaryFinancialTable {

    public record Parts(
        GridPane fixedTable,
        GridPane monthsTable,
        ScrollPane fixedScroll,
        ScrollPane monthsScroll,
        HBox host
    ) {
    }

    private SummaryFinancialTable() {
    }

    public static Parts create() {
        GridPane fixedTable = new GridPane();
        fixedTable.setHgap(0);
        fixedTable.setVgap(0);
        fixedTable.setPadding(new Insets(0));
        fixedTable.setMinWidth(Region.USE_PREF_SIZE);
        fixedTable.getStyleClass().addAll("summary-table", "summary-table-fixed");

        GridPane monthsTable = new GridPane();
        monthsTable.setHgap(0);
        monthsTable.setVgap(0);
        monthsTable.setPadding(new Insets(0));
        monthsTable.setMinWidth(Region.USE_PREF_SIZE);
        monthsTable.getStyleClass().addAll("summary-table", "summary-table-months");

        ScrollPane fixedScroll = new ScrollPane(fixedTable);
        fixedScroll.setFitToWidth(true);
        fixedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fixedScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fixedScroll.setPannable(false);
        fixedScroll.setMinViewportWidth(480);
        fixedScroll.setPrefViewportWidth(480);
        fixedScroll.getStyleClass().addAll("summary-table-scroll", "summary-table-fixed-scroll");

        ScrollPane monthsScroll = new ScrollPane(monthsTable);
        monthsScroll.setFitToHeight(true);
        monthsScroll.setFitToWidth(false);
        monthsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setPannable(false);
        monthsScroll.getStyleClass().addAll("summary-table-scroll", "summary-table-months-scroll");

        fixedScroll.vvalueProperty().bindBidirectional(monthsScroll.vvalueProperty());

        HBox host = new HBox(0, fixedScroll, monthsScroll);
        HBox.setHgrow(monthsScroll, Priority.ALWAYS);
        host.getStyleClass().add("summary-table-host");

        return new Parts(fixedTable, monthsTable, fixedScroll, monthsScroll, host);
    }

    public static void applyDefaultColumnConstraints(GridPane fixedTable) {
        try {
            javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
            c0.setMinWidth(240);
            c0.setPrefWidth(320);
            c0.setHgrow(Priority.ALWAYS);
            javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
            c1.setMinWidth(120);
            c1.setPrefWidth(130);
            c1.setHgrow(Priority.NEVER);
            fixedTable.getColumnConstraints().setAll(c0, c1);
        } catch (Exception ignored) {
        }
    }

    public static Node wrapAsCard(Node tableHost) {
        VBox card = new VBox(tableHost);
        card.getStyleClass().add("summary-table-card");
        card.setPadding(new Insets(20));
        card.setSpacing(0);
        card.setMinWidth(0);
        return card;
    }
}
