package com.myfinaces.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryTableView {

    private SummaryTableView() {
    }

    public static VBox create() {
        VBox root = new VBox(16);
        root.setMinWidth(0);
        return root;
    }

    public static void setContent(VBox root, Node analysisSection, Node compactChartsSection) {
        detachFromParent(analysisSection);
        detachFromParent(compactChartsSection);
        root.getChildren().setAll(analysisSection, compactChartsSection);
        VBox.setVgrow(analysisSection, Priority.ALWAYS);
    }

    public static void setContent(VBox root, Node analysisSection, Node kpiSection, Node compactChartsSection) {
        detachFromParent(analysisSection);
        detachFromParent(kpiSection);
        detachFromParent(compactChartsSection);
        root.getChildren().setAll(analysisSection, kpiSection, compactChartsSection);
        VBox.setVgrow(analysisSection, Priority.ALWAYS);
    }

    public static void setContent(VBox root, Node filtersRow, Node kpiSection, Node tablesCard, Node compactChartsSection) {
        detachFromParent(filtersRow);
        detachFromParent(kpiSection);
        detachFromParent(tablesCard);
        detachFromParent(compactChartsSection);
        root.getChildren().setAll(kpiSection, filtersRow, tablesCard, compactChartsSection);
        VBox.setVgrow(tablesCard, Priority.ALWAYS);
    }

    public static void setContent(VBox root, Node sectionTitle, Node filtersRow, Node kpiSection, Node tablesCard, Node compactChartsSection) {
        detachFromParent(sectionTitle);
        detachFromParent(filtersRow);
        detachFromParent(kpiSection);
        detachFromParent(tablesCard);
        detachFromParent(compactChartsSection);
        root.getChildren().setAll(sectionTitle, kpiSection, filtersRow, tablesCard, compactChartsSection);
        VBox.setVgrow(tablesCard, Priority.ALWAYS);
    }

    private static void detachFromParent(Node node) {
        if (node == null) {
            return;
        }
        Parent p = node.getParent();
        if (p instanceof Pane pane) {
            pane.getChildren().remove(node);
        }
    }
}
