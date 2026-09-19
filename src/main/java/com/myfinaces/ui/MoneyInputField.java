package com.myfinaces.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.TextField;

import java.util.Locale;

public class MoneyInputField extends TextField {

    public static final Locale DEFAULT_LOCALE = MoneyInputFormatter.DEFAULT_LOCALE;

    private final Locale locale;
    private boolean updating;

    public MoneyInputField() {
        this(DEFAULT_LOCALE);
    }

    public MoneyInputField(Locale locale) {
        this(locale, null);
    }

    public MoneyInputField(Locale locale, Long initialCents) {
        this.locale = locale == null ? DEFAULT_LOCALE : locale;
        getStyleClass().add("money-input-field");
        installNormalizer();
        if (initialCents != null) {
            setAmountCents(initialCents);
        }
    }

    public static void install(TextField field) {
        install(field, DEFAULT_LOCALE);
    }

    public static void install(TextField field, Locale locale) {
        if (field == null) {
            return;
        }
        Locale effectiveLocale = locale == null ? DEFAULT_LOCALE : locale;
        if (field instanceof MoneyInputField mif) {
            mif.normalizeAndFormat();
            return;
        }

        final boolean[] updating = { false };
        ChangeListener<String> listener = (obs, oldText, newText) -> {
            if (updating[0]) {
                return;
            }
            String current = newText == null ? "" : newText;
            int caret = field.getCaretPosition();
            int anchor = field.getAnchor();
            String display = MoneyInputFormatter.formatForDisplay(current, effectiveLocale);
            if (current.equals(display)) {
                return;
            }
            boolean wasFocused = field.isFocused();
            updating[0] = true;
            try {
                field.setText(display);
                int mappedCaret = MoneyInputFormatter.mapCaret(current, caret, effectiveLocale);
                int mappedAnchor = MoneyInputFormatter.mapCaret(current, anchor, effectiveLocale);
                field.positionCaret(Math.min(mappedCaret, display.length()));
                if (mappedAnchor != mappedCaret) {
                    field.selectRange(Math.min(mappedAnchor, display.length()), Math.min(mappedCaret, display.length()));
                }
            } finally {
                updating[0] = false;
                restoreFocusIfNeeded(field, wasFocused);
            }
        };
        field.textProperty().addListener(listener);
        Platform.runLater(() -> {
            String text = field.getText();
            if (text != null && !text.isBlank()) {
                String normalized = MoneyInputFormatter.formatForDisplay(text, effectiveLocale);
                if (!text.equals(normalized)) {
                    field.setText(normalized);
                }
            }
        });
    }

    public String getNormalizedMoneyText() {
        return MoneyInputFormatter.normalizeInput(getText(), locale);
    }

    public Long getAmountCents() {
        return MoneyInputFormatter.parseToCents(getText(), locale);
    }

    public long getAmountCentsOrZero() {
        Long value = getAmountCents();
        return value == null ? 0L : value;
    }

    public void setAmountCents(long cents) {
        setText(MoneyInputFormatter.formatFromCents(cents, locale));
    }

    public void setMoneyText(String text) {
        setText(MoneyInputFormatter.formatForDisplay(text, locale));
    }

    private void installNormalizer() {
        textProperty().addListener((obs, oldText, newText) -> {
            if (updating) {
                return;
            }
            normalizeAndFormat();
        });
    }

    private void normalizeAndFormat() {
        if (updating) {
            return;
        }

        String current = getText();
        if (current == null) {
            current = "";
        }

        String normalized = MoneyInputFormatter.formatForDisplay(current, locale);
        if (current.equals(normalized)) {
            return;
        }

        int caret = getCaretPosition();
        int anchor = getAnchor();
        boolean wasFocused = isFocused();
        updating = true;
        try {
            setText(normalized);
            int mappedCaret = MoneyInputFormatter.mapCaret(current, caret, locale);
            int mappedAnchor = MoneyInputFormatter.mapCaret(current, anchor, locale);
            int safeCaret = Math.min(mappedCaret, normalized.length());
            int safeAnchor = Math.min(mappedAnchor, normalized.length());
            positionCaret(safeCaret);
            if (safeAnchor != safeCaret) {
                selectRange(safeAnchor, safeCaret);
            }
        } finally {
            updating = false;
            restoreFocusIfNeeded(this, wasFocused);
        }
    }

    private static void restoreFocusIfNeeded(TextField field, boolean wasFocused) {
        if (!wasFocused || field == null) {
            return;
        }
        if (field.isFocused()) {
            return;
        }
        Platform.runLater(() -> {
            if (field.getScene() != null && !field.isFocused()) {
                field.requestFocus();
            }
        });
    }
}
