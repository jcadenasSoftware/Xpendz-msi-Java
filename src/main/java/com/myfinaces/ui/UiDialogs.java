package com.myfinaces.ui;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.myfinaces.AppVersion;

public final class UiDialogs {

    private UiDialogs() {
    }

    public static void attachAppIcon(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        attachAppIcon(dialog.getDialogPane());
    }

    public static void attachAppIcon(Alert alert) {
        if (alert == null) {
            return;
        }
        attachAppIcon(alert.getDialogPane());
    }

    public static void attachAppIcon(DialogPane pane) {
        if (pane == null) {
            return;
        }

        try {
            if (pane.getScene() != null) {
                pane.getScene().windowProperty().addListener((wObs, oldW, newW) -> trySetStageIcon(newW));
                trySetStageIcon(pane.getScene().getWindow());
            }
        } catch (Exception ignored) {
        }

        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            newScene.windowProperty().addListener((wObs, oldW, newW) -> trySetStageIcon(newW));
            trySetStageIcon(newScene.getWindow());
        });
    }

    public static void applyAppTheme(Dialog<?> dialog, boolean darkTheme) {
        if (dialog == null) {
            return;
        }
        hookDialogOnShown(dialog, darkTheme);
        applyAppTheme(dialog.getDialogPane(), darkTheme);
    }

    public static void applyAppTheme(Alert alert, boolean darkTheme) {
        if (alert == null) {
            return;
        }
        hookDialogOnShown(alert, darkTheme);
        applyAppTheme(alert.getDialogPane(), darkTheme);
    }

    public static void applyAppTheme(DialogPane pane, boolean darkTheme) {
        if (pane == null) {
            return;
        }

        attachAppIcon(pane);

        String cssPath = darkTheme ? "/styles/dark.css" : "/styles/light.css";
        var cssUrl = UiDialogs.class.getResource(cssPath);
        pane.getStylesheets().clear();
        if (cssUrl != null) {
            pane.getStylesheets().add(cssUrl.toExternalForm());
        }
        pane.getStyleClass().addAll("app-root", "categories-dialog");

        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            Platform.runLater(() -> styleDialogButtons(pane));
        });

        pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> Platform.runLater(() -> styleDialogButtons(pane)));
        Platform.runLater(() -> styleDialogButtons(pane));
    }

    private static void hookDialogOnShown(Dialog<?> dialog, boolean darkTheme) {
        EventHandler<javafx.scene.control.DialogEvent> existing = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existing != null) {
                existing.handle(ev);
            }

            DialogPane pane = dialog.getDialogPane();
            if (pane == null) {
                return;
            }

            Platform.runLater(() -> {
                trySetStageIcon(pane.getScene() == null ? null : pane.getScene().getWindow());
                trySetWindowTitle(pane.getScene() == null ? null : pane.getScene().getWindow(), dialog.getTitle());

                String cssPath = darkTheme ? "/styles/dark.css" : "/styles/light.css";
                var cssUrl = UiDialogs.class.getResource(cssPath);
                if (cssUrl != null && pane.getScene() != null) {
                    String css = cssUrl.toExternalForm();
                    if (!pane.getScene().getStylesheets().contains(css)) {
                        pane.getScene().getStylesheets().add(css);
                    }
                }
                styleDialogButtons(pane);
            });
        });
    }

    private static void trySetWindowTitle(Window w, String preferredTitle) {
        if (!(w instanceof Stage s)) {
            return;
        }
        String base = (preferredTitle == null || preferredTitle.isBlank()) ? s.getTitle() : preferredTitle;
        String withV = AppVersion.withVersion(base);
        if (withV == null || withV.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            try {
                s.setTitle(withV);
            } catch (Exception ignored) {
            }
        });
    }

 

    private static void styleDialogButtons(DialogPane pane) {
        if (pane == null) {
            return;
        }
        for (ButtonType bt : pane.getButtonTypes()) {
            var node = pane.lookupButton(bt);
            if (!(node instanceof Button b)) {
                continue;
            }

            if (bt.getButtonData() == ButtonBar.ButtonData.OK_DONE || bt == ButtonType.OK) {
                b.getStyleClass().add("btn-primary");
            } else {
                b.getStyleClass().add("btn-secondary");
            }
        }
    }

    private static void trySetStageIcon(Window w) {
        if (!(w instanceof Stage s)) {
            return;
        }
        try {
            var url = UiDialogs.class.getResource("/images/logo.png");
            if (url != null) {
                Image img = new Image(url.toExternalForm());
                Platform.runLater(() -> s.getIcons().setAll(img));
            }
        } catch (Exception ignored) {
        }
    }
}
