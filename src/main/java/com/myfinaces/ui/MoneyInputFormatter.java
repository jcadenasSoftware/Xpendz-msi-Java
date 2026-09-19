package com.myfinaces.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Parser/formatter canónico de montos para todos los campos financieros.
 * Espejo de MoneyInputFormatter (Android): el último separador solo se
 * interpreta como decimal cuando el resto no encaja como agrupación de miles.
 */
final class MoneyInputFormatter {

    static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("es-CO");

    private MoneyInputFormatter() {
    }

    static String normalizeInput(String input, Locale locale) {
        String raw = input == null ? "" : input.trim();
        if (raw.isBlank()) {
            return "";
        }

        raw = raw.replace(" ", "");

        int separatorCount = 0;
        int lastSeparatorIndex = -1;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '.' || ch == ',') {
                separatorCount++;
                lastSeparatorIndex = i;
            }
        }

        if (lastSeparatorIndex < 0) {
            return normalizeIntegerDigits(raw.replaceAll("[^0-9]", ""));
        }

        char lastSeparatorChar = raw.charAt(lastSeparatorIndex);
        int digitsAfterLastSeparator = 0;
        for (int i = lastSeparatorIndex + 1; i < raw.length(); i++) {
            if (Character.isDigit(raw.charAt(i))) {
                digitsAfterLastSeparator++;
            }
        }
        char localeDecimalSeparator = DecimalFormatSymbols.getInstance(
            locale == null ? DEFAULT_LOCALE : locale).getDecimalSeparator();

        boolean treatAsDecimal = digitsAfterLastSeparator <= 2;
        if (separatorCount > 1 && digitsAfterLastSeparator == 3) {
            treatAsDecimal = false;
        } else if (separatorCount == 1 && lastSeparatorChar != localeDecimalSeparator && digitsAfterLastSeparator == 3) {
            treatAsDecimal = false;
        }

        if (!treatAsDecimal) {
            return normalizeIntegerDigits(raw.replaceAll("[^0-9]", ""));
        }

        String integerPart = normalizeIntegerDigits(
            raw.substring(0, lastSeparatorIndex).replaceAll("[^0-9]", ""));
        if (integerPart.isBlank()) {
            integerPart = "0";
        }
        String fractionPart = raw.substring(lastSeparatorIndex + 1).replaceAll("[^0-9]", "");
        if (fractionPart.isEmpty() && raw.endsWith(String.valueOf(lastSeparatorChar))) {
            return integerPart + ".";
        }
        if (fractionPart.isEmpty()) {
            return integerPart;
        }
        return integerPart + "." + fractionPart;
    }

    static String formatForDisplay(String input, Locale locale) {
        String normalized = normalizeInput(input, locale);
        if (normalized.isBlank()) {
            return "";
        }

        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(
            locale == null ? DEFAULT_LOCALE : locale);
        char grouping = symbols.getGroupingSeparator();
        char decimal = symbols.getDecimalSeparator();

        String[] parts = normalized.split("\\.", 2);
        String integerPart = normalizeIntegerDigits(parts[0]);
        String fractionPart = parts.length > 1 ? parts[1] : "";

        String groupedInteger = groupIntegerDigits(integerPart, grouping);
        if (normalized.endsWith(".")) {
            return groupedInteger + decimal;
        }
        if (fractionPart.isEmpty()) {
            return groupedInteger;
        }
        return groupedInteger + decimal + fractionPart;
    }

    static Long parseToCents(String input, Locale locale) {
        String normalized = normalizeInput(input, locale);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            String safeNormalized = normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
            if (safeNormalized.isBlank()) {
                return null;
            }
            BigDecimal value = new BigDecimal(safeNormalized);
            if (value.signum() < 0) {
                return null;
            }
            return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return null;
        }
    }

    static String formatFromCents(long cents, Locale locale) {
        Locale effectiveLocale = locale == null ? DEFAULT_LOCALE : locale;
        NumberFormat nf = NumberFormat.getNumberInstance(effectiveLocale);
        nf.setGroupingUsed(true);
        nf.setMinimumFractionDigits(0);
        nf.setMaximumFractionDigits(2);
        return nf.format(BigDecimal.valueOf(cents, 2));
    }

    static int mapCaret(String raw, int caret, Locale locale) {
        String safeRaw = raw == null ? "" : raw;
        int safeCaret = Math.max(0, Math.min(caret, safeRaw.length()));
        String prefix = safeRaw.substring(0, safeCaret);
        String formattedPrefix = formatForDisplay(prefix, locale);
        return formattedPrefix.length();
    }

    private static String normalizeIntegerDigits(String rawDigits) {
        if (rawDigits == null) {
            return "";
        }
        String cleaned = rawDigits.replaceAll("[^0-9]", "").replaceFirst("^0+(?!$)", "");
        if (cleaned.isBlank()) {
            return rawDigits.matches(".*[0-9].*") ? "0" : "";
        }
        return cleaned;
    }

    private static String groupIntegerDigits(String rawDigits, char separator) {
        String normalizedDigits = normalizeIntegerDigits(rawDigits);
        if (normalizedDigits.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        int len = normalizedDigits.length();
        int firstGroupSize = len % 3;
        if (firstGroupSize == 0) {
            firstGroupSize = 3;
        }
        builder.append(normalizedDigits, 0, firstGroupSize);
        for (int i = firstGroupSize; i < len; i += 3) {
            builder.append(separator);
            int end = Math.min(i + 3, len);
            builder.append(normalizedDigits, i, end);
        }
        return builder.toString();
    }
}
