package com.myfinaces.ui;

import com.myfinaces.config.AccountStyles;
import com.myfinaces.config.AccountStyles.AccountTypeStyle;
import com.myfinaces.db.AccountRepository;
import javafx.animation.FillTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Supplier;

/**
 * Reusable visual component for rendering account cards.
 * <p>
 * Consumes {@link AccountStyles} for colours, icons and labels so that every
 * module (Dashboard, Transfers, Loans, Budget, Edit-Account, etc.) shares the
 * same identity.
 *
 * <pre>
 *   // compact — identical to the current dashboard row
 *   HBox compact = AccountCard.build(account, balanceCents, AccountCard.Variant.COMPACT, darkTheme::get);
 *
 *   // detail — more padding, soft background, larger icon
 *   HBox detail = AccountCard.build(account, balanceCents, AccountCard.Variant.DETAIL, darkTheme::get);
 * </pre>
 */
public final class AccountCard {

    private AccountCard() {
    }

    // ══════════════════════════════════════════════════════════════════
    //  V A R I A N T
    // ══════════════════════════════════════════════════════════════════

    public enum Variant {
        /** Dashboard-style compact row: avatar · name/type · balance. */
        COMPACT,
        /** Expanded card: soft bg tint, larger avatar, extra spacing. */
        DETAIL
    }

    // ══════════════════════════════════════════════════════════════════
    //  P U B L I C   A P I
    // ══════════════════════════════════════════════════════════════════

    /**
     * Builds an account card node with balance.
     *
     * @param account      the account to render
     * @param balanceCents balance in cents (used for the amount label)
     * @param variant      visual variant
     * @param darkTheme    supplier that returns {@code true} when dark mode is active
     * @return a styled {@link HBox} ready to be added to any layout
     */
    public static HBox build(
        AccountRepository.Account account,
        long balanceCents,
        Variant variant,
        Supplier<Boolean> darkTheme
    ) {
        return buildInternal(account, balanceCents, variant, darkTheme, true);
    }

    /**
     * Builds an identity-only account card (no balance label).
     * Ideal for headers, modals and contexts where balance is shown separately.
     */
    public static HBox buildIdentity(
        AccountRepository.Account account,
        Variant variant,
        Supplier<Boolean> darkTheme
    ) {
        return buildInternal(account, 0L, variant, darkTheme, false);
    }

    private static HBox buildInternal(
        AccountRepository.Account account,
        long balanceCents,
        Variant variant,
        Supplier<Boolean> darkTheme,
        boolean showBalance
    ) {
        if (account == null) {
            return new HBox();
        }

        AccountTypeStyle style = AccountStyles.getStyle(account);
        String accentHex = AccountStyles.resolveColor(account);
        boolean detail = variant == Variant.DETAIL;

        // ── Avatar ────────────────────────────────────────────────
        StackPane avatar = buildAvatar(accentHex, style.icon(), detail);

        // ── Name ──────────────────────────────────────────────────
        Label name = new Label(account.name());
        name.getStyleClass().add("account-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        if (detail) {
            name.getStyleClass().add("ac-name-detail");
        }

        // ── Type dot + label ──────────────────────────────────────
        Circle typeDot = new Circle(detail ? 4 : 3);
        try {
            typeDot.setFill(Color.web(accentHex));
        } catch (Exception e) {
            typeDot.setFill(Color.web(AccountStyles.BANK.color()));
        }
        StackPane dotPane = new StackPane(typeDot);
        int dotSize = detail ? 10 : 8;
        dotPane.setMinSize(dotSize, dotSize);
        dotPane.setPrefSize(dotSize, dotSize);
        dotPane.setMaxSize(dotSize, dotSize);
        dotPane.setAlignment(Pos.CENTER);

        Label typeLabel = new Label(style.label());
        typeLabel.getStyleClass().addAll("text-secondary", "accounts-type-inline");
        typeLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        typeLabel.setMinWidth(0);
        typeLabel.setMaxWidth(Double.MAX_VALUE);

        HBox typeRow = new HBox(5, dotPane, typeLabel);
        typeRow.setAlignment(Pos.CENTER_LEFT);
        typeRow.setMinWidth(0);
        typeRow.setMaxWidth(Double.MAX_VALUE);

        // ── Text column ──────────────────────────────────────────
        VBox textCol = new VBox(detail ? 4 : 2, name, typeRow);
        textCol.setMinWidth(0);
        textCol.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        // ── Balance (optional) ───────────────────────────────────
        HBox row;
        if (showBalance) {
            Label amount = new Label(DashboardFormatters.formatMoney(balanceCents, account.currency()));
            amount.setMinWidth(Label.USE_PREF_SIZE);
            amount.setAlignment(Pos.CENTER_RIGHT);
            amount.setStyle(
                "-fx-font-size: " + (detail ? "16" : "14") + "px; " +
                "-fx-font-weight: 800; " +
                "-fx-padding: 4 0 4 10;"
            );
            amount.getStyleClass().add(
                balanceCents > 0 ? "money-positive" : (balanceCents < 0 ? "money-negative" : "money-neutral")
            );
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row = new HBox(detail ? 14 : 10, avatar, textCol, spacer, amount);
        } else {
            row = new HBox(detail ? 14 : 10, avatar, textCol);
        }

        // ── Row assembly ─────────────────────────────────────────
        row.getStyleClass().add("account-item");
        if (detail) {
            row.getStyleClass().add("ac-detail");
        }
        row.setMaxWidth(Double.MAX_VALUE);
        row.setMinHeight(Region.USE_PREF_SIZE);
        row.setAlignment(Pos.CENTER_LEFT);

        // ── Left accent border + hover ───────────────────────────
        applyAccentBorder(row, accentHex, detail, darkTheme);

        // ── Detail: soft background tint ─────────────────────────
        if (detail) {
            String softHex = AccountStyles.resolveSoftColor(account.type());
            row.setStyle(row.getStyle() + " -fx-background-color: " + toFxRgba(softHex, 0.10) + ";");
        }

        return row;
    }

    // ══════════════════════════════════════════════════════════════════
    //  I N T E R N A L   H E L P E R S
    // ══════════════════════════════════════════════════════════════════

    /**
     * Builds the circular avatar with an Ikonli icon.
     */
    static StackPane buildAvatar(AccountRepository.Account account, boolean detail) {
        String hex = AccountStyles.resolveColor(account);
        String iconLiteral = AccountStyles.resolveIcon(account);
        return buildAvatar(hex, iconLiteral, detail);
    }

    private static StackPane buildAvatar(String hex, String iconLiteral, boolean detail) {
        int radius = detail ? 20 : 16;
        Circle bg = new Circle(radius);
        try {
            Color base = Color.web(hex);
            bg.setFill(base.deriveColor(0, 1.0, 1.0, 0.18));
        } catch (Exception ignored) {
            bg.setFill(Color.web(AccountStyles.BANK.color(), 0.18));
        }

        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(detail ? 17 : 13);
        try {
            icon.setIconColor(Color.web(hex));
        } catch (Exception ignored) {
            icon.setIconColor(Color.web(AccountStyles.BANK.color()));
        }

        int size = radius * 2;
        StackPane avatar = new StackPane(bg, icon);
        avatar.setMinSize(size, size);
        avatar.setPrefSize(size, size);
        avatar.setMaxSize(size, size);
        avatar.setAlignment(Pos.CENTER);
        return avatar;
    }

    /**
     * Applies the left accent border and hover effect.
     */
    private static void applyAccentBorder(HBox row, String accentHex, boolean detail, Supplier<Boolean> darkTheme) {
        int borderLeft = detail ? 3 : 2;
        Supplier<String> subtleBorder = () ->
            darkTheme.get() ? "rgba(255,255,255,0.08)" : "rgba(15,23,42,0.10)";

        Supplier<String> baseStyleFn = () ->
            "-fx-border-color: " + subtleBorder.get() + " " + subtleBorder.get() + " " + subtleBorder.get() + " " + accentHex + ";" +
            "-fx-border-width: 1 1 1 " + borderLeft + ";";

        Supplier<String> hoverStyleFn = () ->
            "-fx-border-color: rgba(59,130,246,0.18) rgba(59,130,246,0.18) rgba(59,130,246,0.18) " + accentHex + ";" +
            "-fx-border-width: 1 1 1 " + borderLeft + ";" +
            "-fx-effect: dropshadow(gaussian, rgba(59,130,246,0.12), " + (detail ? "16" : "12") + ", 0, 0, 0);";

        row.setStyle(baseStyleFn.get());

        row.hoverProperty().addListener((obs, o, n) ->
            row.setStyle(Boolean.TRUE.equals(n) ? hoverStyleFn.get() : baseStyleFn.get())
        );
    }

    /**
     * Converts a hex colour to an {@code rgba()} CSS function with the given alpha.
     */
    private static String toFxRgba(String hex, double alpha) {
        try {
            Color c = Color.web(hex);
            return String.format(
                "rgba(%d,%d,%d,%.2f)",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255),
                alpha
            );
        } catch (Exception e) {
            return "transparent";
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  L I V E   C O L O R   U P D A T E
    // ══════════════════════════════════════════════════════════════════

    private static final Duration TRANSITION_DURATION = Duration.millis(200);

    /**
     * Updates the colour of an existing DETAIL card in-place with smooth animated
     * transitions (~200 ms). Updates: avatar background, icon colour, type dot,
     * left accent border, soft background tint, and hover glow.
     * <p>
     * Does NOT recreate the node — only mutates fills and inline styles.
     *
     * @param card      the HBox previously returned by {@code build} or {@code buildIdentity}
     * @param newHex    the new accent colour hex (e.g. "#2563EB")
     * @param darkTheme supplier for current theme state
     */
    public static void updateColor(HBox card, String newHex, Supplier<Boolean> darkTheme) {
        if (card == null || newHex == null || newHex.isBlank()) return;

        Color newColor;
        try {
            newColor = Color.web(newHex);
        } catch (Exception e) {
            return;
        }

        boolean detail = card.getStyleClass().contains("ac-detail");

        // ── 1. Avatar: background circle + icon ─────────────────────
        Node firstChild = card.getChildren().isEmpty() ? null : card.getChildren().get(0);
        if (firstChild instanceof StackPane avatar) {
            for (Node n : avatar.getChildren()) {
                if (n instanceof Circle bg) {
                    Color targetBg = newColor.deriveColor(0, 1.0, 1.0, 0.18);
                    FillTransition ft = new FillTransition(TRANSITION_DURATION, bg);
                    ft.setToValue(targetBg);
                    ft.play();
                } else if (n instanceof FontIcon icon) {
                    icon.setIconColor(newColor);
                }
            }
        }

        // ── 2. Type dot (inside textCol → typeRow → dotPane → Circle)
        Node secondChild = card.getChildren().size() > 1 ? card.getChildren().get(1) : null;
        if (secondChild instanceof VBox textCol) {
            for (Node vChild : textCol.getChildren()) {
                if (vChild instanceof HBox typeRow) {
                    for (Node hChild : typeRow.getChildren()) {
                        if (hChild instanceof StackPane dotPane) {
                            for (Node dChild : dotPane.getChildren()) {
                                if (dChild instanceof Circle dot) {
                                    FillTransition ft = new FillTransition(TRANSITION_DURATION, dot);
                                    ft.setToValue(newColor);
                                    ft.play();
                                }
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }

        // ── 3. Accent border + hover (re-apply with new colour) ─────
        applyAccentBorder(card, newHex, detail, darkTheme);

        // ── 4. Soft background tint (detail only) ───────────────────
        if (detail) {
            String softRgba = toFxRgba(newHex, 0.10);
            // Preserve existing border style, append background
            String currentStyle = card.getStyle();
            // Remove old -fx-background-color
            currentStyle = currentStyle.replaceAll("-fx-background-color:[^;]+;?", "").trim();
            card.setStyle(currentStyle + " -fx-background-color: " + softRgba + ";");
        }
    }
}
