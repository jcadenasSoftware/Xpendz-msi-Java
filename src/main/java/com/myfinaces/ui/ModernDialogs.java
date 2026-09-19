package com.myfinaces.ui;

import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Dialog;
import javafx.scene.Node;
import javafx.stage.Window;

import java.util.function.Supplier;

public final class ModernDialogs {

    private ModernDialogs() {
    }

    /**
     * Shows an information dialog.
     */
    public static void info(String title, String description, Supplier<Boolean> darkTheme) {
        show(title, description, ModernDialog.DialogType.INFO, darkTheme);
    }

    /**
     * Shows a success dialog.
     */
    public static void success(String title, String description, Supplier<Boolean> darkTheme) {
        show(title, description, ModernDialog.DialogType.SUCCESS, darkTheme);
    }

    /**
     * Shows a warning dialog.
     */
    public static void warning(String title, String description, Supplier<Boolean> darkTheme) {
        show(title, description, ModernDialog.DialogType.WARNING, darkTheme);
    }

    /**
     * Shows a danger/destructive dialog.
     */
    public static void danger(String title, String description, Supplier<Boolean> darkTheme) {
        show(title, description, ModernDialog.DialogType.DANGER, darkTheme);
    }

    /**
     * Shows a confirmation dialog.
     */
    public static boolean confirm(String title, String description, Supplier<Boolean> darkTheme) {
        return confirm(title, description, "Confirmar", "Cancelar", ModernDialog.DialogType.CONFIRM, darkTheme);
    }

    /**
     * Shows a delete confirmation dialog.
     */
    public static boolean confirmDelete(String itemName, Supplier<Boolean> darkTheme) {
        return confirmDestructive(
            "Eliminar " + itemName,
            "Esta acción no se puede deshacer. ¿Estás seguro de que deseas eliminar este elemento?",
            darkTheme
        );
    }

    /**
     * Shows a custom dialog with specific button text.
     */
    public static boolean confirm(String title, String description, String primaryText, String secondaryText, Supplier<Boolean> darkTheme) {
        return confirm(title, description, primaryText, secondaryText, ModernDialog.DialogType.CONFIRM, darkTheme);
    }

    /**
     * Shows a confirmation dialog with an explicit visual type.
     */
    public static boolean confirm(String title, String description, String primaryText, String secondaryText, ModernDialog.DialogType type, Supplier<Boolean> darkTheme) {
        Dialog<ButtonType> dialog = ModernDialog.builder()
            .type(type)
            .title(title)
            .description(description)
            .primaryButton(primaryText)
            .secondaryButton(secondaryText)
            .darkTheme(darkTheme)
            .build();

        dialog.showAndWait();
        ButtonType result = dialog.getResult();
        return result != null && result.getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    /**
     * Shows a destructive confirmation dialog.
     */
    public static boolean confirmDestructive(String title, String description, Supplier<Boolean> darkTheme) {
        return confirm(title, description, "Eliminar", "Cancelar", ModernDialog.DialogType.DANGER, darkTheme);
    }

    /**
     * Shows a dialog with custom content.
     */
    public static void custom(String title, Node content, ModernDialog.DialogType type, Supplier<Boolean> darkTheme) {
        Dialog<ButtonType> dialog = ModernDialog.builder()
            .type(type)
            .title(title)
            .content(content)
            .primaryButton("Cerrar")
            .showSecondaryButton(false)
            .darkTheme(darkTheme)
            .build();

        dialog.showAndWait();
    }

    /**
     * Shows a dialog with custom content and custom button text.
     */
    public static ButtonType custom(String title, Node content, String primaryText, ModernDialog.DialogType type, Supplier<Boolean> darkTheme) {
        Dialog<ButtonType> dialog = ModernDialog.builder()
            .type(type)
            .title(title)
            .content(content)
            .primaryButton(primaryText)
            .showSecondaryButton(false)
            .darkTheme(darkTheme)
            .build();

        dialog.showAndWait();
        return dialog.getResult();
    }

    /**
     * Shows a dialog with primary action callback.
     */
    public static void action(String title, String description, String primaryText, Runnable onPrimary, ModernDialog.DialogType type, Supplier<Boolean> darkTheme) {
        Dialog<ButtonType> dialog = ModernDialog.builder()
            .type(type)
            .title(title)
            .description(description)
            .primaryButton(primaryText)
            .secondaryButton("Cancelar")
            .onPrimary(onPrimary)
            .showTypeLabel(false)
            .showCloseButton(false)
            .darkTheme(darkTheme)
            .build();

        dialog.showAndWait();
    }

    /**
     * Shows a dialog centered on a specific window.
     */
    public static void showCentered(String title, String description, ModernDialog.DialogType type, Window owner, Supplier<Boolean> darkTheme) {
        Dialog<ButtonType> dialog = ModernDialog.builder()
            .type(type)
            .title(title)
            .description(description)
            .darkTheme(darkTheme)
            .build();

        if (owner != null) {
            dialog.initOwner(owner);
        }

        dialog.showAndWait();
    }

    /**
     * Internal method to show a simple dialog.
     */
    private static void show(String title, String description, ModernDialog.DialogType type, Supplier<Boolean> darkTheme) {
        Dialog<ButtonType> dialog = ModernDialog.builder()
            .type(type)
            .title(title)
            .description(description)
            .primaryButton("Entendido")
            .showSecondaryButton(false)
            .darkTheme(darkTheme)
            .build();

        dialog.showAndWait();
    }
}
