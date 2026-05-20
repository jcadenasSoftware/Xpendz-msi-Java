package com.myfinaces.ui;

import com.myfinaces.config.AccountStyles;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable colour-picker component for account personalisation.
 * <p>
 * Renders a {@link FlowPane} of circular colour swatches from a configurable
 * palette (defaults to {@link AccountStyles#ACCOUNT_COLORS}). Provides:
 * <ul>
 *   <li>Active selection with border highlight + check icon</li>
 *   <li>Hover elevation via CSS class</li>
 *   <li>Single callback when the user selects a colour</li>
 *   <li>Programmatic value get/set</li>
 * </ul>
 *
 * <b>Usage:</b>
 * <pre>
 *   AccountColorPicker picker = new AccountColorPicker.Builder()
 *       .value("#2563EB")
 *       .onChange(hex -> updatePreview(hex))
 *       .build();
 *   someContainer.getChildren().add(picker.getNode());
 * </pre>
 *
 * Prepared for future {@code allowCustom(true)} which will append a
 * custom-colour trigger at the end of the palette (not yet implemented).
 */
public final class AccountColorPicker {

    // ══════════════════════════════════════════════════════════════════
    //  C O N S T A N T S
    // ══════════════════════════════════════════════════════════════════

    private static final int SWATCH_SIZE = 28;
    private static final int CIRCLE_RADIUS = 12;
    private static final double STROKE_WIDTH = 2.5;
    private static final int CHECK_ICON_SIZE = 11;
    private static final String CHECK_ICON_LITERAL = "fas-check";

    // ══════════════════════════════════════════════════════════════════
    //  S T A T E
    // ══════════════════════════════════════════════════════════════════

    private final FlowPane root;
    private final List<StackPane> swatches = new ArrayList<>();
    private final List<String> colors;
    private final Consumer<String> onChange;
    private final boolean allowCustom;
    private String selectedValue;

    // ══════════════════════════════════════════════════════════════════
    //  B U I L D E R
    // ══════════════════════════════════════════════════════════════════

    public static final class Builder {
        private String value;
        private Consumer<String> onChange = hex -> {};
        private List<String> colors = AccountStyles.ACCOUNT_COLORS;
        private boolean allowCustom = false;
        private double hGap = 8;
        private double vGap = 8;

        public Builder value(String value) {
            this.value = value;
            return this;
        }

        public Builder onChange(Consumer<String> onChange) {
            this.onChange = onChange != null ? onChange : hex -> {};
            return this;
        }

        public Builder colors(List<String> colors) {
            if (colors != null && !colors.isEmpty()) {
                this.colors = colors;
            }
            return this;
        }

        public Builder allowCustom(boolean allowCustom) {
            this.allowCustom = allowCustom;
            return this;
        }

        public Builder gap(double hGap, double vGap) {
            this.hGap = hGap;
            this.vGap = vGap;
            return this;
        }

        public AccountColorPicker build() {
            return new AccountColorPicker(this);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  C O N S T R U C T O R
    // ══════════════════════════════════════════════════════════════════

    private AccountColorPicker(Builder b) {
        this.colors = b.colors;
        this.onChange = b.onChange;
        this.allowCustom = b.allowCustom;
        this.selectedValue = b.value;

        root = new FlowPane(b.hGap, b.vGap);
        root.setMaxWidth(Double.MAX_VALUE);
        root.getStyleClass().add("account-color-picker");

        buildSwatches();

        // Future: if allowCustom, append a "+" button for a native colour picker
        if (allowCustom) {
            root.getChildren().add(buildCustomTrigger());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  P U B L I C   A P I
    // ══════════════════════════════════════════════════════════════════

    /** Returns the root node to add to a layout. */
    public FlowPane getNode() {
        return root;
    }

    /** Returns the currently selected colour hex, or {@code null} if none. */
    public String getValue() {
        return selectedValue;
    }

    /** Programmatically sets the selected colour (updates visual state). */
    public void setValue(String hex) {
        if (hex == null || hex.isBlank()) {
            clearSelection();
            selectedValue = null;
            return;
        }
        selectedValue = hex;
        for (int i = 0; i < swatches.size(); i++) {
            if (colors.get(i).equalsIgnoreCase(hex)) {
                selectSwatch(swatches.get(i), i);
                return;
            }
        }
        // Colour not in palette — clear visual selection but keep value
        clearSelection();
    }

    // ══════════════════════════════════════════════════════════════════
    //  I N T E R N A L
    // ══════════════════════════════════════════════════════════════════

    private void buildSwatches() {
        for (int i = 0; i < colors.size(); i++) {
            String hex = colors.get(i);
            StackPane swatch = createSwatch(hex);
            swatches.add(swatch);
            root.getChildren().add(swatch);

            // Initial active state
            if (hex.equalsIgnoreCase(selectedValue)) {
                markActive(swatch, hex);
            }
        }
    }

    private StackPane createSwatch(String hex) {
        // Coloured circle
        Circle circle = new Circle(CIRCLE_RADIUS);
        try {
            circle.setFill(Color.web(hex));
        } catch (Exception e) {
            circle.setFill(Color.web("#64748B"));
        }
        circle.setStrokeWidth(0);

        // Check icon (hidden by default)
        FontIcon check = new FontIcon(CHECK_ICON_LITERAL);
        check.setIconSize(CHECK_ICON_SIZE);
        check.setIconColor(Color.WHITE);
        check.setVisible(false);
        check.setMouseTransparent(true);

        // Container
        StackPane swatch = new StackPane(circle, check);
        swatch.setAlignment(Pos.CENTER);
        swatch.getStyleClass().add("color-swatch");
        swatch.setMinSize(SWATCH_SIZE, SWATCH_SIZE);
        swatch.setPrefSize(SWATCH_SIZE, SWATCH_SIZE);
        swatch.setMaxSize(SWATCH_SIZE, SWATCH_SIZE);
        swatch.setUserData(hex);

        // Click handler
        swatch.setOnMouseClicked(ev -> {
            int idx = swatches.indexOf(swatch);
            if (idx >= 0) {
                selectSwatch(swatch, idx);
                onChange.accept(hex);
            }
        });

        return swatch;
    }

    private void selectSwatch(StackPane target, int index) {
        clearSelection();
        selectedValue = colors.get(index);
        markActive(target, selectedValue);
    }

    private void markActive(StackPane swatch, String hex) {
        swatch.getStyleClass().add("color-swatch-active");
        // Update circle stroke
        Node first = swatch.getChildren().get(0);
        if (first instanceof Circle circle) {
            circle.setStrokeWidth(STROKE_WIDTH);
            try {
                circle.setStroke(Color.web(hex).darker());
            } catch (Exception e) {
                circle.setStroke(Color.web("#64748B").darker());
            }
        }
        // Show check icon
        if (swatch.getChildren().size() > 1) {
            Node second = swatch.getChildren().get(1);
            if (second instanceof FontIcon check) {
                check.setVisible(true);
            }
        }
    }

    private void clearSelection() {
        for (StackPane sw : swatches) {
            sw.getStyleClass().remove("color-swatch-active");
            Node first = sw.getChildren().get(0);
            if (first instanceof Circle circle) {
                circle.setStrokeWidth(0);
                circle.setStroke(null);
            }
            if (sw.getChildren().size() > 1) {
                Node second = sw.getChildren().get(1);
                if (second instanceof FontIcon check) {
                    check.setVisible(false);
                }
            }
        }
    }

    /**
     * Placeholder for future custom colour trigger.
     * Currently returns a disabled "+" button.
     */
    private StackPane buildCustomTrigger() {
        Circle bg = new Circle(CIRCLE_RADIUS);
        bg.setFill(Color.web("#1E293B", 0.08));
        bg.setStroke(Color.web("#94A3B8"));
        bg.setStrokeWidth(1.5);
        bg.getStrokeDashArray().addAll(3.0, 3.0);

        FontIcon plus = new FontIcon("fas-plus");
        plus.setIconSize(CHECK_ICON_SIZE);
        plus.setIconColor(Color.web("#64748B"));

        StackPane trigger = new StackPane(bg, plus);
        trigger.setAlignment(Pos.CENTER);
        trigger.getStyleClass().add("color-swatch");
        trigger.setMinSize(SWATCH_SIZE, SWATCH_SIZE);
        trigger.setPrefSize(SWATCH_SIZE, SWATCH_SIZE);
        trigger.setMaxSize(SWATCH_SIZE, SWATCH_SIZE);
        trigger.setDisable(true); // Not yet implemented
        trigger.setOpacity(0.5);

        return trigger;
    }
}
