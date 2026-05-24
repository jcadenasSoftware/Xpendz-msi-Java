package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AccountStyles;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Builds the visual UI for the "Nueva cuenta" side drawer
 * and wires it to the real account creation flow.
 */
public final class NewAccountDrawer {

    private NewAccountDrawer() {
    }

    private static final String DRAWER_ID = "new-account";

    private static final List<Consumer<AccountRepository.Account>> onAccountCreatedListeners = new CopyOnWriteArrayList<>();

    /**
     * Register a listener that will be called on the FX thread whenever a new
     * account is successfully created via the drawer. Useful for modules that
     * need to refresh their account lists (transfers, loans, etc.).
     */
    public static void addOnAccountCreated(Consumer<AccountRepository.Account> listener) {
        onAccountCreatedListeners.add(listener);
    }

    public static void removeOnAccountCreated(Consumer<AccountRepository.Account> listener) {
        onAccountCreatedListeners.remove(listener);
    }

    // Account type & colour definitions are centralised in AccountStyles

    // ══════════════════════════════════════════════════════════════════
    //  P U B L I C   A P I
    // ══════════════════════════════════════════════════════════════════

    /**
     * Registers the "new-account" drawer in the given SideDrawer instance
     * and wires it to the real account creation flow.
     */
    public static VBox install(SideDrawer drawer, AuthSession session,
                               AccountRepository accountRepo, Runnable refreshBalances, boolean darkTheme) {
        HBox header = drawer.buildHeader("Nueva cuenta", "Crea una cuenta para gestionar tus finanzas.");

        // ── Account type selector ────────────────────────────────────
        Label typeLabel = SideDrawer.fieldLabel("Tipo de cuenta", "fas-layer-group");
        FlowPane typeGrid = buildTypeSelector();

        VBox typeSection = new VBox(6, typeLabel, typeGrid);
        typeSection.getStyleClass().add("drawer-section");

        // ── Account name ─────────────────────────────────────────────
        Label nameLabel = SideDrawer.fieldLabel("Nombre de la cuenta", "fas-pen");
        TextField nameField = new TextField();
        nameField.setPromptText("Ej: Banco X - Ahorros");
        nameField.getStyleClass().add("drawer-input");
        nameField.setMaxWidth(Double.MAX_VALUE);

        VBox nameSection = new VBox(6, nameLabel, nameField);
        nameSection.getStyleClass().add("drawer-section");

        // ── Currency selector ────────────────────────────────────────
        Label currencyLabel = SideDrawer.fieldLabel("Moneda", "fas-coins");
        ComboBox<String> currencyCombo = new ComboBox<>();
        currencyCombo.getItems().addAll("COP", "USD", "EUR", "GBP", "MXN", "ARS", "CLP", "PEN", "VES");
        currencyCombo.getSelectionModel().select("COP");
        currencyCombo.setMaxWidth(Double.MAX_VALUE);
        currencyCombo.getStyleClass().add("account-combo");

        VBox currencySection = new VBox(6, currencyLabel, currencyCombo);
        currencySection.getStyleClass().add("drawer-section");

        HBox nameCurrencyRow = new HBox(10, nameSection, currencySection);
        HBox.setHgrow(nameSection, Priority.ALWAYS);
        currencySection.setMinWidth(130);
        currencySection.setMaxWidth(130);

        // ── Initial balance ──────────────────────────────────────────
        Label balanceLabel = SideDrawer.fieldLabel("Balance inicial", "fas-dollar-sign");

        Label currencyPrefix = new Label("$");
        currencyPrefix.getStyleClass().add("drawer-input-prefix");

        TextField balanceField = new TextField("0.00");
        balanceField.getStyleClass().addAll("drawer-input", "drawer-input-money", "drawer-input-no-left-radius");
        balanceField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(balanceField, Priority.ALWAYS);

        // Allow only digits and a single decimal point
        balanceField.setTextFormatter(new javafx.scene.control.TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches("\\d*(\\.\\d{0,2})?")) return change;
            return null;
        }));

        // Select all on focus for quick replacement
        balanceField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) javafx.application.Platform.runLater(balanceField::selectAll);
        });

        HBox balanceRow = new HBox(currencyPrefix, balanceField);
        balanceRow.getStyleClass().add("drawer-input-prefix-row");
        balanceRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label balanceHint = new Label("Opcional — puedes dejarlo en 0");
        balanceHint.getStyleClass().add("drawer-hint");

        VBox balanceSection = new VBox(6, balanceLabel, balanceRow, balanceHint);
        balanceSection.getStyleClass().add("drawer-section");

        // ── Visual personalization (color picker) ────────────────────
        Label colorLabel = SideDrawer.fieldLabel("Color de la cuenta", "fas-palette");

        AccountColorPicker colorPicker = new AccountColorPicker.Builder()
            .value(AccountStyles.ACCOUNT_COLORS.get(0))
            .build();

        Label colorHint = new Label("Opcional — se usará para identificar visualmente la cuenta");
        colorHint.getStyleClass().add("drawer-hint");

        VBox colorSection = new VBox(6, colorLabel, colorPicker.getNode(), colorHint);
        colorSection.getStyleClass().add("drawer-section");

        // ── Footer ───────────────────────────────────────────────────
        VBox footer = drawer.buildFooter("Crear cuenta", "fas-plus");
        Button primaryBtn = (Button) footer.getChildren().stream()
            .filter(n -> n.getStyleClass().contains("drawer-primary-btn"))
            .findFirst().orElse(null);

        // ── Register all in drawer ───────────────────────────────────
        VBox body = drawer.register(DRAWER_ID,
            header,
            typeSection,
            nameCurrencyRow,
            balanceSection,
            colorSection,
            footer
        );

        // Apply dark theme if enabled
        if (darkTheme) {
            body.getStyleClass().add("dark");
        }

        // ── Wire create action ───────────────────────────────────────
        if (primaryBtn != null) {
            primaryBtn.setOnAction(ev -> {
                String name = nameField.getText() == null ? "" : nameField.getText().trim();
                if (name.isBlank()) {
                    shakeNode(nameField);
                    nameField.requestFocus();
                    return;
                }

                // Resolve selected type
                String selectedType = "BANK";
                for (javafx.scene.Node card : typeGrid.getChildren()) {
                    if (card.getStyleClass().contains("drawer-type-card-selected")) {
                        Object ud = card.getUserData();
                        if (ud instanceof String s) selectedType = s;
                        break;
                    }
                }

                String currency = currencyCombo.getValue() == null ? "COP" : currencyCombo.getValue();

                // Resolve selected color
                String selectedColor = colorPicker.getValue();

                // Loading state
                primaryBtn.setDisable(true);
                String originalText = primaryBtn.getText();
                primaryBtn.setText("Creando...");

                try {
                    AccountRepository.Account created = accountRepo.create(
                        session.uid(), name, selectedType, currency, selectedColor
                    );

                    // Sync Firebase in background
                    Thread syncThread = new Thread(() -> {
                        try {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncAccount(session, created);
                        } catch (Exception ignored) {
                        }
                    }, "sync-new-account");
                    syncThread.setDaemon(true);
                    syncThread.start();

                    // Refresh balances
                    refreshBalances.run();

                    // Notify listeners (for other modules)
                    for (Consumer<AccountRepository.Account> listener : onAccountCreatedListeners) {
                        try {
                            listener.accept(created);
                        } catch (Exception ignored) {
                        }
                    }

                    // Success feedback then close
                    primaryBtn.setText("\u2713 Cuenta creada");
                    primaryBtn.getStyleClass().add("drawer-primary-btn-success");

                    PauseTransition successDelay = new PauseTransition(Duration.millis(600));
                    successDelay.setOnFinished(done -> {
                        // Reset form
                        nameField.clear();
                        balanceField.setText("0.00");
                        currencyCombo.getSelectionModel().select("COP");
                        primaryBtn.setText(originalText);
                        primaryBtn.setDisable(false);
                        primaryBtn.getStyleClass().remove("drawer-primary-btn-success");

                        // Close drawer
                        drawer.hide();
                    });
                    successDelay.play();
                } catch (Exception ex) {
                    primaryBtn.setText(originalText);
                    primaryBtn.setDisable(false);
                    shakeNode(primaryBtn);
                    ex.printStackTrace();
                }
            });
        }

        return body;
    }

    // ══════════════════════════════════════════════════════════════════
    //  I N T E R N A L   B U I L D E R S
    // ══════════════════════════════════════════════════════════════════

    private static FlowPane buildTypeSelector() {
        FlowPane grid = new FlowPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.getStyleClass().add("drawer-type-grid");

        List<StackPane> cards = new ArrayList<>();

        for (AccountStyles.AccountTypeStyle opt : AccountStyles.ALL_TYPES) {
            FontIcon icon = new FontIcon(opt.icon());
            icon.setIconSize(20);
            icon.setIconColor(javafx.scene.paint.Color.web(opt.color()));

            Label label = new Label(opt.label());
            label.getStyleClass().add("drawer-type-label");

            VBox content = new VBox(3, new StackPane(icon), label);
            content.setAlignment(Pos.CENTER);
            content.setPadding(new Insets(8, 6, 8, 6));

            StackPane card = new StackPane(content);
            card.getStyleClass().add("drawer-type-card");
            card.setPrefWidth(110);
            card.setPrefHeight(62);
            card.setUserData(opt.key());

            // Hover scale micro-animation
            card.setOnMouseEntered(me -> {
                ScaleTransition st = new ScaleTransition(Duration.millis(120), card);
                st.setToX(1.04);
                st.setToY(1.04);
                st.setInterpolator(Interpolator.EASE_OUT);
                st.play();
            });
            card.setOnMouseExited(me -> {
                ScaleTransition st = new ScaleTransition(Duration.millis(120), card);
                st.setToX(1.0);
                st.setToY(1.0);
                st.setInterpolator(Interpolator.EASE_OUT);
                st.play();
            });

            cards.add(card);

            card.setOnMouseClicked(ev -> {
                cards.forEach(c -> c.getStyleClass().remove("drawer-type-card-selected"));
                card.getStyleClass().add("drawer-type-card-selected");
                // Selection pulse
                pulseNode(card);
            });

            grid.getChildren().add(card);
        }

        // Select first by default
        if (!cards.isEmpty()) {
            cards.get(0).getStyleClass().add("drawer-type-card-selected");
        }

        return grid;
    }

    // ══════════════════════════════════════════════════════════════════
    //  M I C R O   A N I M A T I O N S
    // ══════════════════════════════════════════════════════════════════

    private static void shakeNode(javafx.scene.Node node) {
        TranslateTransition tt = new TranslateTransition(Duration.millis(50), node);
        tt.setFromX(0);
        tt.setByX(6);
        tt.setCycleCount(6);
        tt.setAutoReverse(true);
        tt.setInterpolator(Interpolator.LINEAR);
        tt.setOnFinished(e -> node.setTranslateX(0));
        tt.play();
    }

    private static void pulseNode(javafx.scene.Node node) {
        ScaleTransition up = new ScaleTransition(Duration.millis(100), node);
        up.setToX(1.08);
        up.setToY(1.08);
        up.setInterpolator(Interpolator.EASE_OUT);

        ScaleTransition down = new ScaleTransition(Duration.millis(100), node);
        down.setToX(1.0);
        down.setToY(1.0);
        down.setInterpolator(Interpolator.EASE_IN);

        up.setOnFinished(e -> down.play());
        up.play();
    }
}
