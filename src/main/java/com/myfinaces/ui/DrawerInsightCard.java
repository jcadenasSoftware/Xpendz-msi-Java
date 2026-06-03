package com.myfinaces.ui;

import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

public final class DrawerInsightCard {

    private final VBox root;
    private final Label value;

    public DrawerInsightCard(String title, String initialValue, String iconLiteral) {
        Objects.requireNonNull(title, "title");
        value = new Label(initialValue == null ? "" : initialValue);
        value.getStyleClass().add("sid-insight-value");

        Label t = new Label(title);
        t.getStyleClass().add("sid-insight-title");

        FontIcon icon = new FontIcon(iconLiteral == null ? "far-lightbulb" : iconLiteral);
        icon.setIconSize(14);
        icon.getStyleClass().add("sid-insight-icon");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox top = new HBox(8, icon, t, spacer);
        top.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(8, top, value);
        root.setPadding(new Insets(12));
        root.getStyleClass().add("sid-insight-card");
    }

    public Node getNode() {
        return root;
    }

    public void setValue(String text) {
        value.setText(text == null ? "" : text);
    }
}
