package com.myfinaces.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyInputFormatterTest {

    private static long parseToCents(String input) {
        Long cents = MoneyInputFormatter.parseToCents(input, MoneyInputFormatter.DEFAULT_LOCALE);
        return cents == null ? -1L : cents;
    }

    private static long parseAmountToCents(String input) {
        return DashboardFormatters.parseAmount(input)
            .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Test
    void parsesPlainIntegers() {
        assertEquals(100L, parseToCents("1"));
        assertEquals(10_000L, parseToCents("100"));
        assertEquals(100_000L, parseToCents("1000"));
    }

    @Test
    void parsesGroupedThousands() {
        assertEquals(100_000L, parseToCents("1.000"));
        assertEquals(2_500_000L, parseToCents("25.000"));
        assertEquals(25_000_000L, parseToCents("250.000"));
        assertEquals(1_000_000L, parseToCents("10.000"));
        assertEquals(10_000_000L, parseToCents("100.000"));
        assertEquals(100_000_000L, parseToCents("1.000.000"));
        assertEquals(250_000_000L, parseToCents("2.500.000"));
        assertEquals(1_000_000_000L, parseToCents("10.000.000"));
        assertEquals(10_000_000_000L, parseToCents("100.000.000"));
    }

    @Test
    void regressionMultipleGroupingSeparatorsAreNotDecimals() {
        assertEquals(200_000_000L, parseToCents("2.000.000"));
        assertEquals(100_000_000L, parseToCents("1.000.000"));
        assertEquals(17_500_000L, parseToCents("1.75000"));
        assertEquals(17_500_000L, parseToCents("1,75000"));
    }

    @Test
    void parsesDecimalFractions() {
        assertEquals(150L, parseToCents("1,5"));
        assertEquals(150L, parseToCents("1.5"));
        assertEquals(100_050L, parseToCents("1000,50"));
        assertEquals(100_050L, parseToCents("1.000,50"));
        assertEquals(100_050L, parseToCents("1,000.50"));
        assertEquals(99_999_999_999L, parseToCents("999.999.999,99"));
    }

    @Test
    void rejectsBlankAndNegative() {
        assertNull(MoneyInputFormatter.parseToCents("", MoneyInputFormatter.DEFAULT_LOCALE));
        assertNull(MoneyInputFormatter.parseToCents("   ", MoneyInputFormatter.DEFAULT_LOCALE));
        assertThrows(IllegalArgumentException.class, () -> DashboardFormatters.parseAmount(""));
        assertThrows(IllegalArgumentException.class, () -> DashboardFormatters.parseAmount(null));
    }

    @Test
    void parseAmountDelegatesToCanonicalNormalizer() {
        assertEquals(100_000_000L, parseAmountToCents("1.000.000"));
        assertEquals(200_000_000L, parseAmountToCents("2.000.000"));
        assertEquals(99_999_999_999L, parseAmountToCents("999.999.999,99"));
        assertEquals(150L, parseAmountToCents("1,5"));
        assertEquals(100_000L, parseAmountToCents("1.000"));
    }

    @Test
    void formatRoundTrip() {
        String display = MoneyInputFormatter.formatFromCents(100_000_000L, MoneyInputFormatter.DEFAULT_LOCALE);
        assertEquals(100_000_000L, parseToCents(display));
    }
}
