package com.myfinaces.ui;

public final class SummaryTableHeader {

    private SummaryTableHeader() {
    }

    public static String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
