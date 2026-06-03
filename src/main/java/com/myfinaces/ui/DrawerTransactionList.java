package com.myfinaces.ui;

import com.myfinaces.db.TransactionRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

public final class DrawerTransactionList {

    private final VBox root;
    private List<TransactionRepository.TransactionRow> lastRows;
    private String lastCurrencyCode;
    private boolean expanded;

    public DrawerTransactionList() {
        root = new VBox(10);
        root.getStyleClass().add("sid-tx-list");
        root.setPadding(new Insets(0));
        expanded = false;
        setEmpty("Sin movimientos");
    }

    public Node getNode() {
        return root;
    }

    public void setLoading() {
        root.getChildren().setAll(skeletonRow(), skeletonRow(), skeletonRow());
    }

    public void setEmpty(String message) {
        Label l = new Label(message == null ? "" : message);
        l.getStyleClass().add("sid-empty");
        lastRows = null;
        lastCurrencyCode = null;
        expanded = false;
        root.getChildren().setAll(l);
    }

    public void setTransactions(List<TransactionRepository.TransactionRow> rows, String currencyCode) {
        if (rows == null || rows.isEmpty()) {
            setEmpty("Sin movimientos");
            return;
        }

        lastRows = rows;
        lastCurrencyCode = currencyCode;
        expanded = false;
        render();
    }

    private void render() {
        if (lastRows == null || lastRows.isEmpty()) {
            setEmpty("Sin movimientos");
            return;
        }

        VBox list = new VBox(8);
        list.setFillWidth(true);
        list.setMinWidth(0);
        list.setMaxWidth(Double.MAX_VALUE);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM", new Locale("es", "CO"));

        int limit = expanded ? 1000 : 5;
        int shown = 0;
        for (TransactionRepository.TransactionRow r : lastRows) {
            if (r == null) {
                continue;
            }
            if (shown >= limit) {
                break;
            }
            String when = fmt.format(Instant.ofEpochSecond(r.occurredAtEpochSec()).atZone(ZoneId.systemDefault()).toLocalDate());

            FontIcon ico = new FontIcon("far-credit-card");
            ico.setIconSize(14);
            ico.getStyleClass().add("sid-tx-icon");

            StackPane badge = new StackPane(ico);
            badge.getStyleClass().add("sid-tx-badge");
            badge.setMinSize(30, 30);
            badge.setPrefSize(30, 30);

            Label title = new Label(r.categoryName() == null ? "Movimiento" : r.categoryName());
            title.getStyleClass().add("sid-tx-title");
            title.setMaxWidth(Double.MAX_VALUE);

            Label meta = new Label(when + " · " + (r.accountName() == null ? "" : r.accountName()));
            meta.getStyleClass().add("sid-tx-meta");

            VBox left = new VBox(2, title, meta);
            left.setMinWidth(0);
            HBox.setHgrow(left, Priority.ALWAYS);

            if (r.note() != null && !r.note().isBlank()) {
                Label note = new Label(r.note());
                note.getStyleClass().add("sid-tx-note");
                note.setMaxWidth(Double.MAX_VALUE);
                note.setWrapText(true);
                left.getChildren().add(note);
            }

            Label amount = new Label(DashboardFormatters.formatMoney(r.amountCents(), lastCurrencyCode));
            amount.getStyleClass().add("sid-tx-amount");

            VBox right = new VBox(amount);
            right.setAlignment(Pos.CENTER_RIGHT);
            right.getStyleClass().add("sid-tx-right");
            right.setMinWidth(Region.USE_PREF_SIZE);
            right.setMaxWidth(Region.USE_PREF_SIZE);
            HBox.setHgrow(right, Priority.NEVER);

            HBox row = new HBox(12, badge, left, right);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("sid-tx-row");
            row.setPadding(new Insets(8, 10, 8, 10));
            row.setMinWidth(0);
            row.setMaxWidth(Double.MAX_VALUE);

            list.getChildren().add(row);
            shown++;
        }

        if (lastRows.size() > 5) {
            Button toggle = new Button(expanded ? "Ver menos" : "Ver todos");
            toggle.getStyleClass().add("sid-link");
            toggle.setOnAction(e -> {
                expanded = !expanded;
                render();
            });

            HBox actions = new HBox(toggle);
            actions.setAlignment(Pos.CENTER_LEFT);
            actions.getStyleClass().add("sid-tx-actions");
            list.getChildren().add(actions);
        }

        root.getChildren().setAll(list);
    }

    private static Node skeletonRow() {
        Region r = new Region();
        r.getStyleClass().add("sid-skeleton");
        r.setMinHeight(46);
        r.setPrefHeight(46);
        r.setMaxHeight(46);
        return r;
    }
}
