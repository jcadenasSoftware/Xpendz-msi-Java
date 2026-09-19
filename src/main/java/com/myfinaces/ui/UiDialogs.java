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
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.myfinaces.AppVersion;

public final class UiDialogs {

    private UiDialogs() {
    }

    public static void restrictToDecimalAmount(TextField field) {
        if (field == null) {
            return;
        }
        MoneyInputField.install(field);
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
        if (cssUrl != null) {
            System.out.println("[UiDialogs] DialogPane theme css: " + cssUrl);
        } else {
            System.out.println("[UiDialogs] DialogPane theme css NOT FOUND: " + cssPath);
        }
        pane.getStylesheets().clear();
        if (cssUrl != null) {
            pane.getStylesheets().add(cssUrl.toExternalForm());
        }
        pane.getStyleClass().addAll("app-root", "categories-dialog");

        final String cssExternal = cssUrl != null ? cssUrl.toExternalForm() : null;
        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            if (cssExternal != null) {
                newScene.getStylesheets().setAll(cssExternal);
            }
            if (darkTheme) {
                pane.setStyle("-fx-background-color: #0F172A;");
            } else {
                pane.setStyle("");
            }
            Platform.runLater(() -> {
                styleDialogButtons(pane);
                try {
                    var buttonBar = pane.lookup(".button-bar");
                    if (buttonBar != null) buttonBar.setStyle(darkTheme ? "-fx-background-color: #0F172A;" : "");
                    var container = pane.lookup(".button-bar > .container");
                    if (container != null) container.setStyle(darkTheme ? "-fx-background-color: #0F172A;" : "");
                } catch (Exception ignored) {}
            });
        });

        pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> Platform.runLater(() -> styleDialogButtons(pane)));
        Platform.runLater(() -> styleDialogButtons(pane));
    }

    private static void applyThemeToScene(DialogPane pane, boolean darkTheme, String dialogTitle) {
        if (pane == null) return;
        String cssPath = darkTheme ? "/styles/dark.css" : "/styles/light.css";
        var cssUrl = UiDialogs.class.getResource(cssPath);
        System.out.println("[UiDialogs] applyThemeToScene dark=" + darkTheme + " url=" + cssUrl);
        if (cssUrl != null && pane.getScene() != null) {
            String css = cssUrl.toExternalForm();
            pane.getScene().getStylesheets().setAll(css);
        }
        if (darkTheme) {
            pane.setStyle("-fx-background-color: #0F172A;");
            if (pane.getContent() != null) {
                pane.getContent().setStyle("-fx-background-color: transparent;");
            }
            try {
                var buttonBar = pane.lookup(".button-bar");
                if (buttonBar != null) buttonBar.setStyle("-fx-background-color: #0F172A;");
                var container = pane.lookup(".button-bar > .container");
                if (container != null) container.setStyle("-fx-background-color: #0F172A;");
                var headerPanel = pane.lookup(".header-panel");
                if (headerPanel != null) headerPanel.setStyle("-fx-background-color: #0F172A;");
            } catch (Exception ignored) {}
        } else {
            pane.setStyle("");
            if (pane.getContent() != null) {
                pane.getContent().setStyle("");
            }
            try {
                var buttonBar = pane.lookup(".button-bar");
                if (buttonBar != null) buttonBar.setStyle("");
                var container = pane.lookup(".button-bar > .container");
                if (container != null) container.setStyle("");
            } catch (Exception ignored) {}
        }
        if (dialogTitle != null) {
            trySetWindowTitle(pane.getScene() == null ? null : pane.getScene().getWindow(), dialogTitle);
        }
        trySetStageIcon(pane.getScene() == null ? null : pane.getScene().getWindow());
        styleDialogButtons(pane);
    }

    private static void hookDialogOnShown(Dialog<?> dialog, boolean darkTheme) {
        DialogPane pane = dialog.getDialogPane();
        if (pane != null) {
            pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    Platform.runLater(() -> applyThemeToScene(pane, darkTheme, dialog.getTitle()));
                }
            });
        }

        EventHandler<javafx.scene.control.DialogEvent> existing = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existing != null) {
                existing.handle(ev);
            }
            DialogPane dp = dialog.getDialogPane();
            if (dp == null) return;
            Platform.runLater(() -> applyThemeToScene(dp, darkTheme, dialog.getTitle()));
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
            var url = UiDialogs.class.getResource("/images/xpendz.png");
            if (url == null) {
                url = UiDialogs.class.getResource("/images/logo.png");
            }
            if (url != null) {
                Image img = new Image(url.toExternalForm());
                Platform.runLater(() -> s.getIcons().setAll(img));
            }
        } catch (Exception ignored) {
        }
    }
}
