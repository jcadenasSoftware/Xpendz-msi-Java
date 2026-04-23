package com.myfinaces.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class DashboardHeaderPane {

    private DashboardHeaderPane() {
    }

    public static HBox build(Label greeting, Label month, Button toggleVisibility, Button toggleTheme) {
        VBox text = new VBox(2, greeting, month);
        text.getStyleClass().add("dashboard-header-text");
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerBar = new HBox(12, text, headerSpacer, toggleVisibility, toggleTheme);
        headerBar.setFillHeight(true);
        headerBar.setMaxWidth(Double.MAX_VALUE);
        headerBar.getStyleClass().add("dashboard-header");
        return headerBar;
    }
}
