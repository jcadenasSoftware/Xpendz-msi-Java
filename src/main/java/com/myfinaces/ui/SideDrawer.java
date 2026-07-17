package com.myfinaces.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.HashMap;
import java.util.Map;

/**
 * Global reusable side-drawer infrastructure for XPENDZ.
 * <p>
 * Renders a right-side panel on top of the content area (center of BorderPane),
 * without covering the sidebar. Uses translucent backdrop + slide-in animation.
 * <p>
 * Usage:
 * <pre>
 *   SideDrawer drawer = new SideDrawer();
 *   StackPane wrappedContent = drawer.wrapContent(contentHost);
 *   // replace root.setCenter(contentHost) with root.setCenter(wrappedContent)
 *
 *   // Register a drawer panel:
 *   drawer.register("new-account", header, body, footer);
 *
 *   // Show / hide:
 *   drawer.show("new-account");
 *   drawer.hide();
 * </pre>
 */
public final class SideDrawer {

    // ══════════════════════════════════════════════════════════════════
    //  D E S I G N   T O K E N S
    // ══════════════════════════════════════════════════════════════════

    private static final double DRAWER_WIDTH = 400;
    private static final Duration ANIM_IN = Duration.millis(280);
    private static final Duration ANIM_OUT = Duration.millis(200);

    // ══════════════════════════════════════════════════════════════════
    //  I N S T A N C E   S T A T E
    // ══════════════════════════════════════════════════════════════════

    private final Pane backdrop;
    private final StackPane drawerContainer;
    private VBox activeDrawer;
    private boolean closeOnClickOutside = true;
    private final Map<String, DrawerParts> drawersById = new HashMap<>();

    private record DrawerParts(VBox panel, VBox body, ScrollPane scroll) {
    }

    // ══════════════════════════════════════════════════════════════════
    //  C O N S T R U C T O R
    // ══════════════════════════════════════════════════════════════════

    public SideDrawer() {
        backdrop = new Pane();
        backdrop.getStyleClass().add("drawer-backdrop");
        backdrop.setStyle("-fx-background-color: rgba(0, 0, 0, 0.5);");
        backdrop.setVisible(false);
        backdrop.setMouseTransparent(false);
        backdrop.setOnMouseClicked(ev -> {
            if (closeOnClickOutside) hide();
        });

        drawerContainer = new StackPane();
        drawerContainer.setVisible(false);
        drawerContainer.setMouseTransparent(true);
        drawerContainer.setPickOnBounds(false);
        StackPane.setAlignment(drawerContainer, Pos.CENTER_RIGHT);
    }

    public void setDarkTheme(boolean enabled) {
        if (enabled) {
            if (!drawerContainer.getStyleClass().contains("dark")) {
                drawerContainer.getStyleClass().add("dark");
            }
        } else {
            drawerContainer.getStyleClass().remove("dark");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  P U B L I C   A P I   –   S E T U P
    // ══════════════════════════════════════════════════════════════════

    /**
     * Wraps the content node into a StackPane with backdrop + drawer container.
     * Installs ESC handler and binds sizes. Returns the ready-to-use StackPane.
     */
    public StackPane wrapContent(Node contentRoot) {
        StackPane stack = new StackPane(contentRoot, backdrop, drawerContainer);
        VBox.setVgrow(stack, Priority.ALWAYS);
        HBox.setHgrow(stack, Priority.ALWAYS);

        backdrop.prefWidthProperty().bind(stack.widthProperty());
        backdrop.prefHeightProperty().bind(stack.heightProperty());

        drawerContainer.prefHeightProperty().bind(stack.heightProperty());
        drawerContainer.setMaxWidth(DRAWER_WIDTH);
        drawerContainer.setPrefWidth(DRAWER_WIDTH);

        installEscHandler(stack);
        return stack;
    }

    /**
     * Registers a drawer panel with the given ID and content nodes.
     * Content is wrapped in a styled VBox with full height and scroll support.
     */
    public VBox register(String id, boolean darkTheme, Node... contentNodes) {
        setDarkTheme(darkTheme);

        VBox body = new VBox(12, contentNodes);
        body.setPadding(new Insets(20, 24, 20, 24));
        body.setFillWidth(true);
        body.getStyleClass().add("drawer-body");

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("drawer-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox panel = new VBox(scroll);
        panel.setId(id);
        panel.setVisible(false);
        panel.setMaxWidth(DRAWER_WIDTH);
        panel.setPrefWidth(DRAWER_WIDTH);
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.setFillWidth(true);
        panel.getStyleClass().add("drawer-panel");
        panel.setOnMouseClicked(ev -> ev.consume());

        drawersById.put(id, new DrawerParts(panel, body, scroll));

        drawerContainer.getChildren().add(panel);
        StackPane.setAlignment(panel, Pos.CENTER_RIGHT);
        return body;
    }

    // ══════════════════════════════════════════════════════════════════
    //  P U B L I C   A P I   –   L I F E C Y C L E
    // ══════════════════════════════════════════════════════════════════

    /**
     * Shows the registered drawer panel by ID with slide-in + fade animation.
     */
    public void show(String id) {
        drawerContainer.getChildren().forEach(n -> n.setVisible(false));

        DrawerParts parts = drawersById.get(id);
        if (parts == null) return;
        Node target = parts.panel();

        backdrop.setVisible(true);
        drawerContainer.setVisible(true);
        drawerContainer.setMouseTransparent(false);
        target.setVisible(true);
        activeDrawer = (target instanceof VBox) ? (VBox) target : null;

        // ── Animate entrance (slide from right + fade) ───────────
        target.setOpacity(0);
        target.setTranslateX(DRAWER_WIDTH);

        FadeTransition fadeIn = new FadeTransition(ANIM_IN, target);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition slideIn = new TranslateTransition(ANIM_IN, target);
        slideIn.setFromX(DRAWER_WIDTH);
        slideIn.setToX(0);
        slideIn.setInterpolator(Interpolator.SPLINE(0.25, 0.1, 0.25, 1.0));

        FadeTransition backdropIn = new FadeTransition(ANIM_IN, backdrop);
        backdropIn.setFromValue(0);
        backdropIn.setToValue(1);
        backdropIn.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fadeIn, slideIn, backdropIn).play();
    }

    /**
     * Hides the active drawer with slide-out + fade animation.
     */
    public void hide() {
        if (activeDrawer == null || !activeDrawer.isVisible()) {
            forceHide();
            return;
        }

        Node target = activeDrawer;

        FadeTransition fadeOut = new FadeTransition(ANIM_OUT, target);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setInterpolator(Interpolator.EASE_IN);

        TranslateTransition slideOut = new TranslateTransition(ANIM_OUT, target);
        slideOut.setFromX(0);
        slideOut.setToX(DRAWER_WIDTH);
        slideOut.setInterpolator(Interpolator.SPLINE(0.55, 0.0, 0.75, 0.0));

        FadeTransition backdropOut = new FadeTransition(ANIM_OUT, backdrop);
        backdropOut.setFromValue(1);
        backdropOut.setToValue(0);
        backdropOut.setInterpolator(Interpolator.EASE_IN);

        ParallelTransition exit = new ParallelTransition(fadeOut, slideOut, backdropOut);
        exit.setOnFinished(ev -> {
            target.setVisible(false);
            forceHide();
        });
        exit.play();
    }

    /** Whether the drawer is currently open. */
    public boolean isShowing() {
        return backdrop.isVisible();
    }

    /** Whether clicking the backdrop dismisses the drawer. Default true. */
    public void setCloseOnClickOutside(boolean enabled) {
        this.closeOnClickOutside = enabled;
    }

    // ══════════════════════════════════════════════════════════════════
    //  H E A D E R   /   F O O T E R   B U I L D E R S
    // ══════════════════════════════════════════════════════════════════

    /**
     * Builds a standard drawer header with title, subtitle, and close button.
     */
    public HBox buildHeader(String titleText, String subtitleText) {
        Label titleLabel = new Label(titleText);
        titleLabel.getStyleClass().add("drawer-title");

        Label subtitleLabel = new Label(subtitleText);
        subtitleLabel.getStyleClass().add("drawer-subtitle");
        subtitleLabel.setWrapText(true);

        VBox textBox = new VBox(2, titleLabel, subtitleLabel);
        textBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Button btnClose = buildCloseButton();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(12, textBox, spacer, btnClose);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("drawer-header");
        header.setPadding(new Insets(0, 0, 12, 0));
        return header;
    }

    /**
     * Builds the standard close (×) button.
     */
    public Button buildCloseButton() {
        FontIcon icon = new FontIcon("fas-times");
        icon.setIconSize(16);
        icon.getStyleClass().add("drawer-close-icon");

        Button btn = new Button();
        btn.setGraphic(icon);
        btn.getStyleClass().add("drawer-close-btn");
        btn.setOnAction(ev -> hide());
        return btn;
    }

    /**
     * Builds a primary action button (full-width, styled via CSS).
     */
    public static Button buildPrimaryButton(String text, String iconLiteral) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(14);
        icon.getStyleClass().add("drawer-primary-btn-icon");

        Button btn = new Button(text);
        btn.setGraphic(icon);
        btn.setGraphicTextGap(8);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.getStyleClass().add("drawer-primary-btn");
        return btn;
    }

    /**
     * Builds a cancel/secondary button (full-width, outline style via CSS).
     */
    public Button buildCancelButton(String text) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.getStyleClass().add("drawer-cancel-btn");
        btn.setOnAction(ev -> hide());
        return btn;
    }

    /**
     * Builds a footer with separator line, primary button, and cancel button.
     */
    public VBox buildFooter(String primaryText, String primaryIcon) {
        Region separator = new Region();
        separator.getStyleClass().add("drawer-footer-separator");
        separator.setMaxWidth(Double.MAX_VALUE);
        separator.setMinHeight(1);
        separator.setPrefHeight(1);

        Button primary = buildPrimaryButton(primaryText, primaryIcon);
        Button cancel = buildCancelButton("Cancelar");

        VBox footer = new VBox(12, separator, primary, cancel);
        footer.getStyleClass().add("drawer-footer");
        footer.setPadding(new Insets(12, 0, 0, 0));
        return footer;
    }

    // ══════════════════════════════════════════════════════════════════
    //  F I E L D   H E L P E R S
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a field label with icon for consistent form styling.
     */
    public static Label fieldLabel(String text, String iconLiteral) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(12);
        icon.getStyleClass().add("drawer-field-icon");

        Label label = new Label(text);
        label.setGraphic(icon);
        label.setGraphicTextGap(6);
        label.getStyleClass().add("drawer-field-label");
        return label;
    }

    // ══════════════════════════════════════════════════════════════════
    //  I N T E R N A L S
    // ══════════════════════════════════════════════════════════════════

    private void forceHide() {
        backdrop.setVisible(false);
        drawerContainer.setVisible(false);
        drawerContainer.setMouseTransparent(true);
        activeDrawer = null;
    }

    private void installEscHandler(Node root) {
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && backdrop.isVisible()) {
                hide();
                e.consume();
            }
        });
    }
}
