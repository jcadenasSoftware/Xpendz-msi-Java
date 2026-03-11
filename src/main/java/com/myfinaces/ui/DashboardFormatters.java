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
        DecimalFormat df = new DecimalFormat("#,##0.00", sym);
        df.setGroupingUsed(true);

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

    public static BigDecimal parseAmount(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isBlank()) {
            throw new IllegalArgumentException("amount");
        }

        s = s.replace(" ", "");
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                s = s.replace(".", "");
                s = s.replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (lastComma >= 0) {
            s = s.replace(',', '.');
        }

        return new BigDecimal(s);
    }
}
