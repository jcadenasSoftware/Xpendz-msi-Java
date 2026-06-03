package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryAnalyticsView {

    private SummaryAnalyticsView() {
    }

    public static VBox create() {
        VBox root = new VBox(32);
        root.setPadding(new Insets(8, 0, 8, 0));
        root.setMinWidth(0);
        return root;
    }

    public static void setContent(VBox root, Node kpiSection, Node chartsSection, Node compactSummarySection) {
        setContent(root, kpiSection, chartsSection, null, compactSummarySection);
    }

    public static void setContent(VBox root, Node kpiSection, Node chartsSection, Node insightsSection, Node compactSummarySection) {
        detachFromParent(kpiSection);
        detachFromParent(chartsSection);
        detachFromParent(insightsSection);
        detachFromParent(compactSummarySection);

        Node kpiBlock = kpiSection == null ? null : wrapSection(kpiSection);
        Node trendsBlock = wrapSection(chartsSection);
        Node insightsBlock = insightsSection == null ? null : wrapSection(insightsSection);
        Node summaryBlock = compactSummarySection == null ? null : wrapSection(compactSummarySection);

        if (summaryBlock == null) {
            if (kpiBlock == null && insightsBlock == null) {
                root.getChildren().setAll(trendsBlock);
            } else if (kpiBlock == null) {
                root.getChildren().setAll(insightsBlock, trendsBlock);
            } else if (insightsBlock == null) {
                root.getChildren().setAll(kpiBlock, trendsBlock);
            } else {
                root.getChildren().setAll(kpiBlock, insightsBlock, trendsBlock);
            }
            VBox.setVgrow(trendsBlock, Priority.ALWAYS);
            return;
        }

        if (kpiBlock == null && insightsBlock == null) {
            root.getChildren().setAll(trendsBlock, summaryBlock);
        } else if (kpiBlock == null) {
            root.getChildren().setAll(insightsBlock, trendsBlock, summaryBlock);
        } else if (insightsBlock == null) {
            root.getChildren().setAll(kpiBlock, trendsBlock, summaryBlock);
        } else {
            root.getChildren().setAll(kpiBlock, insightsBlock, trendsBlock, summaryBlock);
        }

        VBox.setVgrow(summaryBlock, Priority.ALWAYS);
    }

    private static Node wrapSection(Node content) {
        VBox wrapper = new VBox(content);
        wrapper.setMinWidth(0);
        wrapper.getStyleClass().add("analytics-section-wrapper");
        return wrapper;
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
