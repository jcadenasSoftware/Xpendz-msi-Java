package com.myfinaces.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.effect.BoxBlur;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Global overlay infrastructure for XPENDZ fintech-style modals.
 * <p>
 * Provides four reusable component layers:
 * <ul>
 *   <li><b>OverlayBackdrop</b> — dark tint + blur behind content</li>
 *   <li><b>OverlayContainer</b> — lifecycle, animations, ESC, click-outside</li>
 *   <li><b>OverlayHeader</b>  — title + subtitle + close button</li>
 *   <li><b>OverlayFooter</b>  — primary CTA + cancel</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 *   ModalOverlay overlay = new ModalOverlay();
 *   overlay.register("new-loan", 480, header, form, footer);
 *   overlay.register("pay", 440, payHeader, payBody, payFooter);
 *
 *   // Wire into a module StackPane:
 *   StackPane stack = overlay.wrapContent(root);   // root + backdrop + modal container
 *   overlay.show("new-loan");
 *   overlay.hide();
 * </pre>
 */
public final class ModalOverlay {

    // ══════════════════════════════════════════════════════════════════
    //  D E S I G N   T O K E N S   (XPENDZ Fintech)
    // ══════════════════════════════════════════════════════════════════

    // ── Backdrop ─────────────────────────────────────────────────────
    private static final String BACKDROP_BG =
        "-fx-background-color: rgba(15,23,42,0.38);";
    private static final double BLUR_RADIUS     = 8.0;
    private static final int    BLUR_ITERATIONS = 2;

    // ── Modal body ───────────────────────────────────────────────────
    private static final String MODAL_BODY_STYLE =
        "-fx-background-color: white; "
        + "-fx-background-radius: 20; "
        + "-fx-border-radius: 20; "
        + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.18), 32, 0, 0, 8);";

    // ── Close button ─────────────────────────────────────────────────
    private static final String CLOSE_BTN_BASE =
        "-fx-background-color: #F8FAFC; "
        + "-fx-background-radius: 8; -fx-border-radius: 8; "
        + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
        + "-fx-min-width: 34; -fx-min-height: 34; "
        + "-fx-max-width: 34; -fx-max-height: 34; "
        + "-fx-cursor: hand; -fx-padding: 0;";

    private static final String CLOSE_BTN_HOVER =
        "-fx-background-color: #FEE2E2; "
        + "-fx-background-radius: 8; -fx-border-radius: 8; "
        + "-fx-border-color: #FECACA; -fx-border-width: 1; "
        + "-fx-min-width: 34; -fx-min-height: 34; "
        + "-fx-max-width: 34; -fx-max-height: 34; "
        + "-fx-cursor: hand; -fx-padding: 0;";

    // ── Primary button ───────────────────────────────────────────────
    static final String PRIMARY_BTN_STYLE =
        "-fx-text-fill: white; "
        + "-fx-background-radius: 12; -fx-border-radius: 12; "
        + "-fx-font-size: 14px; -fx-font-weight: 700; -fx-cursor: hand; "
        + "-fx-padding: 12 0 12 0;";

    // ── Cancel button ────────────────────────────────────────────────
    static final String CANCEL_BTN_BASE =
        "-fx-background-color: transparent; -fx-text-fill: #64748B; "
        + "-fx-background-radius: 12; -fx-border-radius: 12; "
        + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
        + "-fx-font-size: 13px; -fx-font-weight: 600; -fx-cursor: hand; "
        + "-fx-padding: 10 0 10 0;";

    static final String CANCEL_BTN_HOVER =
        "-fx-background-color: #F8FAFC; -fx-text-fill: #334155; "
        + "-fx-background-radius: 12; -fx-border-radius: 12; "
        + "-fx-border-color: #CBD5E1; -fx-border-width: 1; "
        + "-fx-font-size: 13px; -fx-font-weight: 600; -fx-cursor: hand; "
        + "-fx-padding: 10 0 10 0;";

    // ── Input fields ─────────────────────────────────────────────────
    public static final String INPUT_STYLE =
        "-fx-background-color: #F8FAFC; "
        + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
        + "-fx-border-radius: 10; -fx-background-radius: 10; "
        + "-fx-padding: 10 14 10 14; "
        + "-fx-font-size: 13px;";

    // ── Animation timing ─────────────────────────────────────────────
    private static final Duration ANIM_IN  = Duration.millis(220);
    private static final Duration ANIM_OUT = Duration.millis(160);

    // ══════════════════════════════════════════════════════════════════
    //  I N S T A N C E   S T A T E
    // ══════════════════════════════════════════════════════════════════

    private final Pane backdrop;
    private final StackPane modalContainer;
    private VBox activeModal;
    private Node blurTarget;           // content node to blur when overlay is open
    private boolean closeOnClickOutside = true;

    // ══════════════════════════════════════════════════════════════════
    //  C O N S T R U C T O R
    // ══════════════════════════════════════════════════════════════════

    public ModalOverlay() {
        // ── OverlayBackdrop ──────────────────────────────────────
        backdrop = new Pane();
        backdrop.setStyle(BACKDROP_BG);
        backdrop.setVisible(false);
        backdrop.setMouseTransparent(false);
        backdrop.setOnMouseClicked(ev -> {
            if (closeOnClickOutside) hide();
        });

        // ── OverlayContainer ─────────────────────────────────────
        modalContainer = new StackPane();
        modalContainer.setVisible(false);
        modalContainer.setMouseTransparent(true);
        modalContainer.setPickOnBounds(false);
        StackPane.setAlignment(modalContainer, Pos.CENTER);
    }

    // ══════════════════════════════════════════════════════════════════
    //  P U B L I C   A P I   –   L I F E C Y C L E
    // ══════════════════════════════════════════════════════════════════

    /**
     * Wraps a content root into a StackPane with backdrop + modal container,
     * binds sizes, and installs ESC handler. Returns the ready-to-use root.
     * <p>
     * This is the preferred one-call setup method.
     */
    public StackPane wrapContent(Node contentRoot) {
        blurTarget = contentRoot;

        StackPane stack = new StackPane(contentRoot, backdrop, modalContainer);
        VBox.setVgrow(stack, Priority.ALWAYS);

        backdrop.prefWidthProperty().bind(stack.widthProperty());
        backdrop.prefHeightProperty().bind(stack.heightProperty());

        installEscHandler(stack);
        return stack;
    }

    /**
     * Registers a modal with the given ID, max width, and content nodes.
     * Wraps content into a styled card body. Returns the VBox for reference.
     */
    public VBox register(String id, double maxWidth, Node... contentNodes) {
        modalContainer.getChildren().removeIf(n -> id.equals(n.getId()));

        VBox body = new VBox(18, contentNodes);
        body.setPadding(new Insets(28, 32, 28, 32));
        body.setFillWidth(true);
        body.setId(id);
        body.setVisible(false);
        body.setMaxWidth(maxWidth);
        body.setMaxHeight(Region.USE_PREF_SIZE);
        body.setOnMouseClicked(ev -> ev.consume());
        body.getStyleClass().add("modal-root");

        modalContainer.getChildren().add(body);
        StackPane.setAlignment(body, Pos.CENTER);
        return body;
    }

    /**
     * Shows a registered modal by ID with fade + scale animation.
     * Applies blur to the content behind the overlay.
     */
    public void show(String id) {
        modalContainer.getChildren().forEach(n -> n.setVisible(false));

        Node target = modalContainer.lookup("#" + id);
        if (target == null) return;

        // Blur content behind
        applyBlur(true);

        backdrop.setVisible(true);
        modalContainer.setVisible(true);
        modalContainer.setMouseTransparent(false);
        target.setVisible(true);
        activeModal = (target instanceof VBox) ? (VBox) target : null;

        // ── Animate entrance ─────────────────────────────────────
        target.setOpacity(0);
        target.setScaleX(0.94);
        target.setScaleY(0.94);

        FadeTransition fadeIn = new FadeTransition(ANIM_IN, target);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.setInterpolator(Interpolator.EASE_OUT);

        ScaleTransition scaleIn = new ScaleTransition(ANIM_IN, target);
        scaleIn.setFromX(0.94);
        scaleIn.setFromY(0.94);
        scaleIn.setToX(1);
        scaleIn.setToY(1);
        scaleIn.setInterpolator(Interpolator.EASE_OUT);

        // Backdrop fade
        FadeTransition backdropIn = new FadeTransition(ANIM_IN, backdrop);
        backdropIn.setFromValue(0);
        backdropIn.setToValue(1);
        backdropIn.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fadeIn, scaleIn, backdropIn).play();
    }

    /**
     * Hides the active modal with fade + scale animation.
     * Removes blur from content behind.
     */
    public void hide() {
        if (activeModal == null || !activeModal.isVisible()) {
            forceHide();
            return;
        }

        Node target = activeModal;

        FadeTransition fadeOut = new FadeTransition(ANIM_OUT, target);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setInterpolator(Interpolator.EASE_IN);

        ScaleTransition scaleOut = new ScaleTransition(ANIM_OUT, target);
        scaleOut.setToX(0.94);
        scaleOut.setToY(0.94);
        scaleOut.setInterpolator(Interpolator.EASE_IN);

        FadeTransition backdropOut = new FadeTransition(ANIM_OUT, backdrop);
        backdropOut.setFromValue(1);
        backdropOut.setToValue(0);
        backdropOut.setInterpolator(Interpolator.EASE_IN);

        ParallelTransition exit = new ParallelTransition(fadeOut, scaleOut, backdropOut);
        exit.setOnFinished(ev -> {
            target.setVisible(false);
            forceHide();
        });
        exit.play();
    }

    /** Whether clicking the backdrop dismisses the overlay. Default true. */
    public void setCloseOnClickOutside(boolean enabled) {
        this.closeOnClickOutside = enabled;
    }

    /** Check whether the overlay is currently visible. */
    public boolean isShowing() {
        return backdrop.isVisible();
    }

    // ══════════════════════════════════════════════════════════════════
    //  P U B L I C   A P I   –   L E G A C Y   S E T U P
    // ══════════════════════════════════════════════════════════════════

    /** Returns the backdrop pane (for manual StackPane assembly). */
    public Pane getOverlayPane() {
        return backdrop;
    }

    /** Returns the modal container (for manual StackPane assembly). */
    public StackPane getModalContainer() {
        return modalContainer;
    }

    /** Bind backdrop size manually (use wrapContent instead). */
    public void bindSize(StackPane root) {
        backdrop.prefWidthProperty().bind(root.widthProperty());
        backdrop.prefHeightProperty().bind(root.heightProperty());
    }

    /** Install ESC handler manually (use wrapContent instead). */
    public void installEscHandler(Node root) {
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && backdrop.isVisible()) {
                hide();
                e.consume();
            }
        });
    }

    /** Set the node to receive blur effect when overlay opens. */
    public void setBlurTarget(Node target) {
        this.blurTarget = target;
    }

    // ══════════════════════════════════════════════════════════════════
    //  O V E R L A Y H E A D E R
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a consistent modal header: title, subtitle, close button.
     */
    public HBox buildHeader(String titleText, String subtitleText) {
        Label titleLabel = new Label(titleText);
        titleLabel.getStyleClass().add("modal-title");

        Label subtitleLabel = new Label(subtitleText);
        subtitleLabel.getStyleClass().add("modal-subtitle");

        VBox textBox = new VBox(2, titleLabel, subtitleLabel);

        Button btnClose = buildCloseButton();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(textBox, spacer, btnClose);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("modal-title-bar");
        return header;
    }

    /**
     * Creates the standard close (×) button with hover-to-red animation.
     */
    public Button buildCloseButton() {
        FontIcon icon = new FontIcon("fas-times");
        icon.setIconSize(16);

        Button btn = new Button();
        btn.setGraphic(icon);
        btn.getStyleClass().add("modal-close-btn");
        icon.getStyleClass().add("modal-close-icon");
        btn.setOnAction(ev -> hide());
        return btn;
    }

    // ══════════════════════════════════════════════════════════════════
    //  O V E R L A Y F O O T E R
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a standard footer: separator + primary CTA + cancel button.
     */
    public VBox buildFooter(String primaryText, String primaryIcon,
                             String primaryColor, String primaryHover) {
        Separator sep = new Separator();

        Button primary = buildPrimaryButton(primaryText, primaryIcon,
            primaryColor, primaryHover);
        Button cancel = buildCancelButton("Cancelar");

        VBox footer = new VBox(8, sep, primary, cancel);
        footer.getStyleClass().add("modal-footer");
        return footer;
    }

    /**
     * Creates a primary action button (full-width, colored background).
     */
    public static Button buildPrimaryButton(String text, String iconLiteral,
                                             String bgColor, String hoverColor) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(13);
        icon.setIconColor(Color.WHITE);

        Button btn = new Button(text);
        btn.setGraphic(icon);
        btn.setGraphicTextGap(6);

        String base  = "-fx-background-color: " + bgColor + "; " + PRIMARY_BTN_STYLE;
        String hover = "-fx-background-color: " + hoverColor + "; " + PRIMARY_BTN_STYLE;

        btn.setStyle(base);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnMouseEntered(ev -> btn.setStyle(hover));
        btn.setOnMouseExited(ev -> btn.setStyle(base));
        return btn;
    }

    /**
     * Creates a cancel button (full-width, outline style).
     */
    public Button buildCancelButton(String text) {
        Button btn = new Button(text);
        btn.getStyleClass().add("modal-btn-cancel");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(ev -> hide());
        return btn;
    }

    // ══════════════════════════════════════════════════════════════════
    //  F I E L D   H E L P E R S
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a field label with small icon — consistent across all modals.
     */
    public static Label fieldLabel(String text, String iconLiteral) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(12);
        icon.getStyleClass().add("modal-field-icon");
        Label label = new Label(text);
        label.setGraphic(icon);
        label.setGraphicTextGap(6);
        label.getStyleClass().add("modal-field-label");
        return label;
    }

    // ══════════════════════════════════════════════════════════════════
    //  I N T E R N A L S
    // ══════════════════════════════════════════════════════════════════

    private void forceHide() {
        backdrop.setVisible(false);
        modalContainer.setVisible(false);
        modalContainer.setMouseTransparent(true);
        activeModal = null;
        applyBlur(false);
    }

    private void applyBlur(boolean on) {
        if (blurTarget == null) return;
        blurTarget.setEffect(on
            ? new BoxBlur(BLUR_RADIUS, BLUR_RADIUS, BLUR_ITERATIONS)
            : null);
    }
}
