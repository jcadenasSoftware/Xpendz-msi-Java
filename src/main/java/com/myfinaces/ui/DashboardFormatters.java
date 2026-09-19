package com.myfinaces.ui;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class DashboardFormatters {

    private DashboardFormatters() {
    }

    public static String formatUserDecimal(long cents) {
        BigDecimal v = BigDecimal.valueOf(Math.abs(cents), 2);
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.forLanguageTag("es-CO"));
        DecimalFormat df = new DecimalFormat("#,##0.00", sym);
        df.setGroupingUsed(true);
        return df.format(v);
    }

    public static String formatSignedUserDecimal(long cents) {
        if (cents < 0) {
            return "-" + formatUserDecimal(cents);
        }
        return formatUserDecimal(cents);
    }

    public static String formatMoney(long cents) {
        return formatMoney(cents, "COP");
    }

    public static String formatMoney(long cents, String currencyCode) {
        boolean neg = cents < 0;
        BigDecimal v = BigDecimal.valueOf(Math.abs(cents), 2);

        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.forLanguageTag("es-CO"));
        DecimalFormat df = new DecimalFormat("#,##0.##", sym);
        df.setGroupingUsed(true);
        df.setMinimumFractionDigits(0);
        df.setMaximumFractionDigits(2);

        String symbol = currencySymbol(currencyCode);
        return (neg ? "-" : "") + symbol + df.format(v);
    }

    public static String currencySymbol(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return "$";
        }
        String c = currencyCode.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "COP" -> "$";
            case "USD" -> "US$";
            case "EUR" -> "€";
            case "GBP" -> "£";
            case "MXN" -> "MX$";
            case "ARS" -> "AR$";
            case "CLP" -> "CL$";
            case "PEN" -> "S/";
            default -> c + " ";
        };
    }

    public static String formatTransactionDisplayText(String kind, String note, String fallbackCategoryName) {
        String trimmedNote = note == null ? "" : note.trim();
        if (trimmedNote.isBlank()) {
            String fallback = fallbackCategoryName == null ? "" : fallbackCategoryName.trim();
            return fallback.isBlank() ? "Movimiento" : fallback;
        }

        String normalizedKind = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if ("LOAN_LENT_OUT".equals(normalizedKind)) {
            return translateLoanPrefix(trimmedNote, "LOAN_LENT_OUT", "Préstamo otorgado a:");
        }
        if ("LOAN_BORROWED_IN".equals(normalizedKind)) {
            return translateLoanPrefix(trimmedNote, "LOAN_BORROWED_IN", "Préstamo recibido de:");
        }

        return trimmedNote;
    }

    private static String translateLoanPrefix(String text, String rawPrefix, String translatedPrefix) {
        String normalized = text.trim();
        
        String normalizedSpanish = normalizeSpanishPrefix(normalized, translatedPrefix);
        if (normalizedSpanish != null) {
            return normalizedSpanish;
        }
        
        String[] candidates = {
            rawPrefix + ":",
            rawPrefix + " :",
            rawPrefix
        };

        for (String candidate : candidates) {
            if (normalized.regionMatches(true, 0, candidate, 0, candidate.length())) {
                String remainder = normalized.substring(candidate.length()).trim();
                return remainder.isBlank() ? translatedPrefix : translatedPrefix + " " + remainder;
            }
        }

        return translatedPrefix + " " + normalized;
    }

    private static String normalizeSpanishPrefix(String text, String preferredPrefix) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        String[][] spanishPrefixes = {
            {"préstamo otorgado a:", "Préstamo otorgado a:"},
            {"préstamo recibido de:", "Préstamo recibido de:"},
            {"dinero recibido de:", "Préstamo recibido de:"},
            {"dinero prestado a:", "Préstamo otorgado a:"},
            {"préstamo a:", "Préstamo otorgado a:"},
            {"préstamo de:", "Préstamo recibido de:"}
        };

        for (String[] prefixPair : spanishPrefixes) {
            String lowercasePrefix = prefixPair[0];
            String normalizedPrefix = prefixPair[1];
            if (normalized.startsWith(lowercasePrefix)) {
                String remainder = text.substring(lowercasePrefix.length()).trim();
                return remainder.isBlank() ? normalizedPrefix : normalizedPrefix + " " + remainder;
            }
        }
        return null;
    }

    public static BigDecimal parseAmount(String raw) {
        String normalized = MoneyInputFormatter.normalizeInput(raw, MoneyInputFormatter.DEFAULT_LOCALE);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("amount");
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("amount", ex);
        }
    }
}
