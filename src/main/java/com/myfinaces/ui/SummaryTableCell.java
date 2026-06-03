package com.myfinaces.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

public final class SummaryTableCell {

    private SummaryTableCell() {
    }

    public static Label header(String text, boolean currentMonth) {
        Label h = new Label(text);
        h.getStyleClass().addAll("account-name", "summary-header-cell");
        if (currentMonth) {
            h.getStyleClass().add("summary-current-month");
        }
        return h;
    }

    public static Label category(String text, boolean root, boolean totalRow) {
        Label l = new Label(text);
        l.getStyleClass().add(root ? "summary-root-name" : "summary-sub-name");
        if (totalRow) {
            l.getStyleClass().add("summary-total-name");
        }
        return l;
    }

    public static HBox categoryWithIcon(String text, boolean root, boolean totalRow, Ikon icon) {
        FontIcon iconNode = new FontIcon(icon);
        iconNode.setIconSize(14);
        Label l = new Label(text);
        l.getStyleClass().add(root ? "summary-root-name" : "summary-sub-name");
        if (totalRow) {
            l.getStyleClass().add("summary-total-name");
        }
        HBox box = new HBox(8, iconNode, l);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static Label amount(String formatted, boolean currentMonth) {
        Label v = new Label(formatted);
        v.setMinWidth(100);
        v.setAlignment(Pos.CENTER_RIGHT);
        v.getStyleClass().addAll("summary-amount-cell", "summary-cell-strong");
        if (currentMonth) {
            v.getStyleClass().add("summary-current-month");
        }
        return v;
    }

    public static Label amountPlain(String formatted, boolean currentMonth) {
        Label v = new Label(formatted);
        v.setMinWidth(100);
        v.setAlignment(Pos.CENTER_RIGHT);
        v.getStyleClass().add("summary-amount-cell");
        if (currentMonth) {
            v.getStyleClass().add("summary-current-month");
        }
        return v;
    }
}
