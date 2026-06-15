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
        GridPane fixedHeaderTable,
        GridPane fixedTable,
        GridPane monthsHeaderTable,
        GridPane monthsTable,
        ScrollPane fixedScroll,
        ScrollPane monthsScroll,
        VBox host
    ) {
    }

    private SummaryFinancialTable() {
    }

    public static Parts create() {
        GridPane fixedHeaderTable = new GridPane();
        fixedHeaderTable.setHgap(0);
        fixedHeaderTable.setVgap(0);
        fixedHeaderTable.setPadding(new Insets(0));
        fixedHeaderTable.setMinWidth(480);
        fixedHeaderTable.setPrefWidth(480);
        fixedHeaderTable.setMaxWidth(480);
        fixedHeaderTable.getStyleClass().addAll("summary-table", "summary-table-header", "summary-table-fixed-header");

        GridPane fixedTable = new GridPane();
        fixedTable.setHgap(0);
        fixedTable.setVgap(0);
        fixedTable.setPadding(new Insets(0));
        fixedTable.setMinWidth(Region.USE_PREF_SIZE);
        fixedTable.getStyleClass().addAll("summary-table", "summary-table-fixed");

        GridPane monthsHeaderTable = new GridPane();
        monthsHeaderTable.setHgap(0);
        monthsHeaderTable.setVgap(0);
        monthsHeaderTable.setPadding(new Insets(0));
        monthsHeaderTable.setMinWidth(Region.USE_PREF_SIZE);
        monthsHeaderTable.setMaxWidth(Double.MAX_VALUE);
        monthsHeaderTable.getStyleClass().addAll("summary-table", "summary-table-header", "summary-table-months-header");

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

        ScrollPane monthsHeaderScroll = new ScrollPane(monthsHeaderTable);
        monthsHeaderScroll.setFitToHeight(true);
        monthsHeaderScroll.setFitToWidth(false);
        monthsHeaderScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        monthsHeaderScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        monthsHeaderScroll.setPannable(false);
        monthsHeaderScroll.setMouseTransparent(true);
        monthsHeaderScroll.getStyleClass().addAll("summary-table-scroll", "summary-table-months-header-scroll");

        ScrollPane monthsScroll = new ScrollPane(monthsTable);
        monthsScroll.setFitToHeight(true);
        monthsScroll.setFitToWidth(false);
        monthsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setPannable(false);
        monthsScroll.getStyleClass().addAll("summary-table-scroll", "summary-table-months-scroll");

        fixedScroll.vvalueProperty().bindBidirectional(monthsScroll.vvalueProperty());
        monthsHeaderScroll.hvalueProperty().bind(monthsScroll.hvalueProperty());
        monthsHeaderScroll.vvalueProperty().bindBidirectional(monthsScroll.vvalueProperty());

        HBox headerRow = new HBox(0, fixedHeaderTable, monthsHeaderScroll);
        headerRow.getStyleClass().add("summary-table-header-row");
        HBox.setHgrow(monthsHeaderScroll, Priority.ALWAYS);

        HBox bodyRow = new HBox(0, fixedScroll, monthsScroll);
        HBox.setHgrow(monthsScroll, Priority.ALWAYS);

        VBox host = new VBox(0, headerRow, bodyRow);
        HBox.setHgrow(monthsScroll, Priority.ALWAYS);
        host.getStyleClass().add("summary-table-host");
        VBox.setVgrow(bodyRow, Priority.ALWAYS);

        return new Parts(fixedHeaderTable, fixedTable, monthsHeaderTable, monthsTable, fixedScroll, monthsScroll, host);
    }

    public static void applyDefaultColumnConstraints(GridPane fixedTable) {
        try {
            javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
            c0.setMinWidth(240);
            c0.setPrefWidth(320);
            c0.setHgrow(Priority.ALWAYS);
            javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
            c1.setMinWidth(180);
            c1.setPrefWidth(190);
            c1.setHgrow(Priority.NEVER);
            fixedTable.getColumnConstraints().setAll(c0, c1);
        } catch (Exception ignored) {
        }
    }

    public static void applyMonthColumnConstraints(GridPane monthsTable) {
        try {
            javafx.scene.layout.ColumnConstraints month = new javafx.scene.layout.ColumnConstraints();
            month.setMinWidth(120);
            month.setPrefWidth(120);
            month.setMaxWidth(120);
            month.setHgrow(Priority.NEVER);

            javafx.scene.layout.ColumnConstraints avg = new javafx.scene.layout.ColumnConstraints();
            avg.setMinWidth(120);
            avg.setPrefWidth(120);
            avg.setMaxWidth(120);
            avg.setHgrow(Priority.NEVER);

            monthsTable.getColumnConstraints().clear();
            for (int i = 0; i < 12; i++) {
                monthsTable.getColumnConstraints().add(month);
            }
            monthsTable.getColumnConstraints().add(avg);
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
