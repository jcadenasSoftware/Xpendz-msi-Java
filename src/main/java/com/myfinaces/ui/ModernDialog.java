package com.myfinaces.ui;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Supplier;

public final class ModernDialog {

    private ModernDialog() {
    }

    public enum DialogType {
        INFO,
        SUCCESS,
        WARNING,
        DANGER,
        CONFIRM
    }

    public static class Builder {
        private DialogType type = DialogType.INFO;
        private String title = "";
        private String description = "";
        private String iconLiteral = null;
        private Node customContent = null;
        private String primaryButtonText = "Continuar";
        private String secondaryButtonText = "Cancelar";
        private boolean showSecondaryButton = true;
        private boolean showCloseButton = true;
        private boolean showTypeLabel = true;
        private Supplier<Boolean> darkThemeSupplier = () -> false;
        private Runnable onPrimaryAction = null;
        private Runnable onSecondaryAction = null;
        private Runnable onCloseAction = null;
        private boolean closeOnOutsideClick = true;
        private String iconImagePath = null;

        public Builder type(DialogType type) {
            this.type = type;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder icon(String iconLiteral) {
            this.iconLiteral = iconLiteral;
            return this;
        }

        public Builder content(Node content) {
            this.customContent = content;
            return this;
        }

        public Builder primaryButton(String text) {
            this.primaryButtonText = text;
            return this;
        }

        public Builder secondaryButton(String text) {
            this.secondaryButtonText = text;
            return this;
        }

        public Builder showSecondaryButton(boolean show) {
            this.showSecondaryButton = show;
            return this;
        }

        public Builder showCloseButton(boolean show) {
            this.showCloseButton = show;
            return this;
        }

        public Builder showTypeLabel(boolean show) {
            this.showTypeLabel = show;
            return this;
        }

        public Builder iconImage(String resourcePath) {
            this.iconImagePath = resourcePath;
            return this;
        }

        public Builder darkTheme(Supplier<Boolean> supplier) {
            this.darkThemeSupplier = supplier;
            return this;
        }

        public Builder onPrimary(Runnable action) {
            this.onPrimaryAction = action;
            return this;
        }

        public Builder onSecondary(Runnable action) {
            this.onSecondaryAction = action;
            return this;
        }

        public Builder onClose(Runnable action) {
            this.onCloseAction = action;
            return this;
        }

        public Builder closeOnOutsideClick(boolean close) {
            this.closeOnOutsideClick = close;
            return this;
        }

        public Dialog<ButtonType> build() {
            Dialog<ButtonType> dialog = new Dialog<>();
            DialogPane pane = new DialogPane();
            dialog.setDialogPane(pane);

            // Set dialog properties
            dialog.setTitle(title);
            pane.setHeaderText(null);

            // Build custom content
            VBox content = buildContent();
            pane.setContent(content);

            // Setup buttons
            setupButtons(dialog, pane);

            // Apply theme
            boolean isDark = darkThemeSupplier.get();
            applyTheme(dialog, pane, isDark);

            // Setup outside click behavior
            if (closeOnOutsideClick && type != DialogType.DANGER) {
                setupOutsideClick(dialog, pane);
            }

            // Set dialog size
            dialog.setResizable(false);
            dialog.getDialogPane().setPrefWidth(440);
            dialog.getDialogPane().setMinWidth(400);
            dialog.getDialogPane().setMaxWidth(470);
            dialog.getDialogPane().setPrefHeight(260);
            dialog.getDialogPane().setMinHeight(240);
            dialog.getDialogPane().setMaxHeight(310);

            return dialog;
        }

        private VBox buildContent() {
            VBox root = new VBox(8);
            root.setPadding(new Insets(14, 18, 10, 18));
            root.setFillWidth(true);
            root.getStyleClass().add("modern-dialog-root");

            // Header section
            VBox header = buildHeader();
            root.getChildren().add(header);

            // Description
            if (description != null && !description.isBlank()) {
                root.getChildren().add(buildMessageCard(description));
            }

            // Custom content
            if (customContent != null) {
                VBox customCard = new VBox(customContent);
                customCard.getStyleClass().add("modern-dialog-custom-card");
                customCard.setFillWidth(true);
                customCard.setMinWidth(0);
                root.getChildren().add(customCard);
            }

            return root;
        }

        private VBox buildHeader() {
            VBox header = new VBox(10);
            header.getStyleClass().add("modern-dialog-header");

            Region accent = new Region();
            accent.getStyleClass().add("modern-dialog-accent-strip");
            accent.setMaxWidth(Double.MAX_VALUE);
            accent.setMinHeight(4);

            HBox topRow = new HBox(14);
            topRow.setAlignment(Pos.CENTER_LEFT);
            topRow.setMinWidth(0);

            Node iconNode = buildIcon();
            topRow.getChildren().add(iconNode);

            VBox titleBlock = new VBox(4);
            titleBlock.setMinWidth(0);
            titleBlock.getStyleClass().add("modern-dialog-title-block");
            HBox.setHgrow(titleBlock, Priority.ALWAYS);

            Label eyebrow = new Label(getTypeLabel());
            eyebrow.getStyleClass().add("modern-dialog-eyebrow");

            Label titleLabel = new Label(title);
            titleLabel.getStyleClass().add("modern-dialog-title");
            titleLabel.setWrapText(true);
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            if (showTypeLabel) {
                titleBlock.getChildren().add(eyebrow);
            }
            titleBlock.getChildren().add(titleLabel);

            topRow.getChildren().add(titleBlock);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            topRow.getChildren().add(spacer);

            // Close button
            if (showCloseButton) {
                Button closeButton = new Button();
                FontIcon closeIcon = new FontIcon("fas-times");
                closeIcon.setIconSize(14);
                closeButton.setGraphic(closeIcon);
                closeButton.getStyleClass().add("modern-dialog-close-btn");
                closeButton.setOnAction(e -> {
                    if (onCloseAction != null) {
                        onCloseAction.run();
                    }
                });
                topRow.getChildren().add(closeButton);
            }

            header.getChildren().addAll(accent, topRow);

            return header;
        }

        private Node buildIcon() {
            if (iconImagePath != null && !iconImagePath.isBlank()) {
                var url = ModernDialog.class.getResource(iconImagePath);
                if (url != null) {
                    ImageView imageView = new ImageView(new Image(url.toExternalForm()));
                    imageView.setFitWidth(24);
                    imageView.setFitHeight(24);
                    imageView.setPreserveRatio(true);

                    Circle circle = new Circle(22);
                    circle.getStyleClass().add("modern-dialog-icon-circle");

                    StackPane iconContainer = new StackPane(circle, imageView);
                    iconContainer.getStyleClass().add("modern-dialog-icon-container");
                    iconContainer.getStyleClass().add("modern-dialog-app-icon");
                    return iconContainer;
                }
            }

            String icon = iconLiteral != null ? iconLiteral : getDefaultIconForType();
            FontIcon fontIcon;
            try {
                fontIcon = new FontIcon(icon);
            } catch (IllegalArgumentException ex) {
                fontIcon = new FontIcon(getDefaultIconForType());
            }
            fontIcon.setIconSize(22);
            fontIcon.getStyleClass().add("modern-dialog-icon");

            Circle circle = new Circle(22);
            circle.getStyleClass().add("modern-dialog-icon-circle");

            StackPane iconContainer = new StackPane(circle, fontIcon);
            iconContainer.getStyleClass().add("modern-dialog-icon-container");

            return iconContainer;
        }

        private VBox buildMessageCard(String text) {
            VBox card = new VBox(0);
            card.getStyleClass().add("modern-dialog-message-card");
            card.setMinWidth(0);
            card.setFillWidth(true);

            Label descLabel = new Label(text);
            descLabel.getStyleClass().add("modern-dialog-description");
            descLabel.setWrapText(true);
            descLabel.setMaxWidth(Double.MAX_VALUE);

            card.getChildren().add(descLabel);
            return card;
        }

        private String getTypeLabel() {
            return switch (type) {
                case INFO -> "Información";
                case SUCCESS -> "Éxito";
                case WARNING -> "Advertencia";
                case DANGER -> "Peligro";
                case CONFIRM -> "Confirmación";
            };
        }

        private String getDefaultIconForType() {
            return switch (type) {
                case INFO -> "fas-info-circle";
                case SUCCESS -> "fas-check-circle";
                case WARNING -> "fas-exclamation-circle";
                case DANGER -> "fas-exclamation-triangle";
                case CONFIRM -> "fas-question-circle";
            };
        }

        private void setupButtons(Dialog<ButtonType> dialog, DialogPane pane) {
            pane.getButtonTypes().clear();

            ButtonType primaryType = new ButtonType(primaryButtonText, ButtonBar.ButtonData.OK_DONE);
            pane.getButtonTypes().add(primaryType);

            if (showSecondaryButton) {
                ButtonType secondaryType = new ButtonType(secondaryButtonText, ButtonBar.ButtonData.CANCEL_CLOSE);
                pane.getButtonTypes().add(secondaryType);
            }

            // Style buttons immediately and on changes
            Runnable styleButtons = () -> {
                Platform.runLater(() -> {
                    for (ButtonType bt : pane.getButtonTypes()) {
                        var node = pane.lookupButton(bt);
                        if (node instanceof Button b) {
                            b.getStyleClass().removeAll(
                                "btn-primary",
                                "btn-secondary",
                                "btn-danger",
                                "modern-dialog-primary-btn",
                                "modern-dialog-secondary-btn"
                            );
                            if (bt.getButtonData() == ButtonBar.ButtonData.OK_DONE || bt == ButtonType.OK) {
                                b.getStyleClass().add("modern-dialog-primary-btn");
                                b.setDefaultButton(true);
                                b.setCancelButton(false);
                                b.setMinWidth(168);
                                b.setPrefWidth(168);
                            } else {
                                b.getStyleClass().add("modern-dialog-secondary-btn");
                                b.setDefaultButton(false);
                                b.setCancelButton(true);
                                b.setMinWidth(132);
                                b.setPrefWidth(132);
                            }
                        }
                    }
                });
            };

            pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> styleButtons.run());
            styleButtons.run();

            // Handle button actions
            dialog.setResultConverter(buttonType -> {
                if (buttonType == primaryType && onPrimaryAction != null) {
                    onPrimaryAction.run();
                } else if (buttonType != primaryType && onSecondaryAction != null) {
                    onSecondaryAction.run();
                }
                return buttonType;
            });
        }

        private void setupOutsideClick(Dialog<ButtonType> dialog, DialogPane pane) {
            pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    newScene.getWindow().addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                        if (event.getTarget() == newScene.getWindow()) {
                            if (onCloseAction != null) {
                                onCloseAction.run();
                            }
                            dialog.close();
                        }
                    });
                }
            });
        }

        private void applyTheme(Dialog<ButtonType> dialog, DialogPane pane, boolean isDark) {
            String cssPath = isDark ? "/styles/dark.css" : "/styles/light.css";
            var cssUrl = ModernDialog.class.getResource(cssPath);

            pane.getStyleClass().addAll("app-root", "modern-dialog", "modern-dialog-" + type.name().toLowerCase());
            if (isDark) {
                pane.getStyleClass().add("dark");
            } else {
                pane.getStyleClass().remove("dark");
            }

            // Set background color
            if (isDark) {
                pane.setStyle("-fx-background-color: #0F172A;");
                if (pane.getContent() != null) {
                    pane.getContent().setStyle("-fx-background-color: transparent;");
                }
                try {
                    var buttonBar = pane.lookup(".button-bar");
                    if (buttonBar != null) buttonBar.setStyle("-fx-background-color: #0F172A;");
                    var container = pane.lookup(".button-bar > .container");
                    if (container != null) container.setStyle("-fx-background-color: #0F172A;");
                } catch (Exception ignored) {}
            } else {
                pane.setStyle("-fx-background-color: #FFFFFF;");
                if (pane.getContent() != null) {
                    pane.getContent().setStyle("-fx-background-color: transparent;");
                }
                try {
                    var buttonBar = pane.lookup(".button-bar");
                    if (buttonBar != null) buttonBar.setStyle("");
                    var container = pane.lookup(".button-bar > .container");
                    if (container != null) container.setStyle("");
                } catch (Exception ignored) {}
            }

            // Attach app icon
            attachAppIcon(dialog);

            // Hook for theme application on show
            dialog.setOnShown(ev -> {
                Platform.runLater(() -> {
                    // Load CSS on scene
                    if (pane.getScene() != null && cssUrl != null) {
                        pane.getScene().getStylesheets().setAll(cssUrl.toExternalForm());
                    }
                    if (isDark) {
                        pane.setStyle("-fx-background-color: #0F172A;");
                        if (pane.getContent() != null) {
                            pane.getContent().setStyle("-fx-background-color: transparent;");
                        }
                        try {
                            var buttonBar = pane.lookup(".button-bar");
                            if (buttonBar != null) buttonBar.setStyle("-fx-background-color: #0F172A;");
                            var container = pane.lookup(".button-bar > .container");
                            if (container != null) container.setStyle("-fx-background-color: #0F172A;");
                        } catch (Exception ignored) {}
                    } else {
                        pane.setStyle("-fx-background-color: #FFFFFF;");
                        if (pane.getContent() != null) {
                            pane.getContent().setStyle("-fx-background-color: transparent;");
                        }
                        try {
                            var buttonBar = pane.lookup(".button-bar");
                            if (buttonBar != null) buttonBar.setStyle("");
                            var container = pane.lookup(".button-bar > .container");
                            if (container != null) container.setStyle("");
                        } catch (Exception ignored) {}
                    }
                    // Force icon to be set on show
                    if (pane.getScene() != null) {
                        trySetStageIcon(pane.getScene().getWindow());
                    }
                });
            });
        }

        private void attachAppIcon(Dialog<ButtonType> dialog) {
            dialog.getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    newScene.windowProperty().addListener((wObs, oldW, newW) -> trySetStageIcon(newW));
                    trySetStageIcon(newScene.getWindow());
                }
            });
            dialog.setOnShown(e -> {
                if (dialog.getDialogPane().getScene() != null) {
                    trySetStageIcon(dialog.getDialogPane().getScene().getWindow());
                }
            });
        }

        private void trySetStageIcon(Window w) {
            if (!(w instanceof Stage s)) {
                return;
            }
            try {
                var url = ModernDialog.class.getResource("/images/xpendz.png");
                if (url == null) {
                    url = ModernDialog.class.getResource("/images/logo.png");
                }
                if (url != null) {
                    Image img = new Image(url.toExternalForm());
                    Platform.runLater(() -> s.getIcons().setAll(img));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
