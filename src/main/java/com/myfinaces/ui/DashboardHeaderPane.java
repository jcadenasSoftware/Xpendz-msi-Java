package com.myfinaces.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public final class DashboardHeaderPane {

    private DashboardHeaderPane() {
    }

    public static HBox build(Label title, Button toggleTheme) {
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerBar = new HBox(12, title, headerSpacer, toggleTheme);
        headerBar.setFillHeight(true);
        return headerBar;
    }
}
