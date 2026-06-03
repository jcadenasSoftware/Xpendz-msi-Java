package com.myfinaces.ui;

import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

public final class DrawerTrendIndicator {

    private final HBox root;
    private final FontIcon icon;
    private final Label label;
    private boolean compact;

    public DrawerTrendIndicator() {
        icon = new FontIcon("fas-arrow-up");
        icon.setIconSize(12);
        icon.getStyleClass().add("sid-trend-icon");

        label = new Label(" ");
        label.getStyleClass().add("sid-trend-text");

        root = new HBox(8, icon, label);
        root.setAlignment(Pos.CENTER_LEFT);
        root.getStyleClass().add("sid-trend");
        compact = false;
    }

    public Node getNode() {
        return root;
    }

    public void setValue(double pctChange, boolean up) {
        icon.setIconLiteral(up ? "fas-arrow-up" : "fas-arrow-down");
        root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("up"), up);
        root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("down"), !up);
        if (compact) {
            label.setText(String.format(Locale.ROOT, "%+.1f%%", pctChange));
        } else {
            label.setText(String.format(Locale.ROOT, "%+.1f%% vs mes anterior", pctChange));
        }
    }

    public void setCompact(boolean compact) {
        this.compact = compact;
        if (compact) {
            if (!root.getStyleClass().contains("sid-trend-compact")) {
                root.getStyleClass().add("sid-trend-compact");
            }
        } else {
            root.getStyleClass().remove("sid-trend-compact");
        }
    }
}
