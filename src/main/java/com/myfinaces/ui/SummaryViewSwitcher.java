package com.myfinaces.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

public final class SummaryViewSwitcher {

    public enum ViewMode {
        TABLE,
        ANALYTICS,
        HEATMAP
    }

    private final ObjectProperty<ViewMode> selectedView = new SimpleObjectProperty<>(ViewMode.TABLE);
    private final HBox root;

    public SummaryViewSwitcher() {
        ToggleGroup group = new ToggleGroup();

        ToggleButton table = buildPill("Tabla", FontAwesomeSolid.TABLE, ViewMode.TABLE, group);
        ToggleButton analytics = buildPill("Analítica", FontAwesomeSolid.CHART_LINE, ViewMode.ANALYTICS, group);
        ToggleButton heatmap = buildPill("Heatmap", FontAwesomeSolid.TH, ViewMode.HEATMAP, group);

        group.selectedToggleProperty().addListener((o, oldV, newV) -> {
            if (newV == null) {
                selectedView.set(ViewMode.TABLE);
                table.setSelected(true);
                return;
            }
            selectedView.set((ViewMode) newV.getUserData());
        });

        table.setSelected(true);

        root = new HBox(0, table, analytics, heatmap);
        root.getStyleClass().add("summary-view-switcher");
        root.setAlignment(Pos.CENTER_LEFT);
        root.setMinWidth(0);

        table.setMaxWidth(Double.MAX_VALUE);
        analytics.setMaxWidth(Double.MAX_VALUE);
        heatmap.setMaxWidth(Double.MAX_VALUE);

        HBox.setHgrow(table, Priority.ALWAYS);
        HBox.setHgrow(analytics, Priority.ALWAYS);
        HBox.setHgrow(heatmap, Priority.ALWAYS);
    }

    public Node getNode() {
        return root;
    }

    public ObjectProperty<ViewMode> selectedViewProperty() {
        return selectedView;
    }

    private static ToggleButton buildPill(String text, Ikon icon, ViewMode mode, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setUserData(mode);
        btn.getStyleClass().add("summary-view-pill");
        btn.setMinWidth(0);

        FontIcon iconNode = new FontIcon(icon);
        iconNode.getStyleClass().add("summary-view-pill-icon");
        iconNode.setIconSize(12);
        btn.setGraphic(iconNode);

        return btn;
    }
}
