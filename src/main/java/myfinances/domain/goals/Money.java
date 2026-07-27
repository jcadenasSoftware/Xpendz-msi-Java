package myfinances.domain.goals;

import java.util.Locale;
import java.util.Objects;

public final class Money implements Comparable<Money> {

    private final long amountInCents;
    private final String currency;

    private Money(long amountInCents, String currency) {
        this.amountInCents = amountInCents;
        this.currency = currency;
    }

    public static Money of(long amountInCents, String currency) {
        String normalizedCurrency = normalizeCurrency(currency);
        return new Money(amountInCents, normalizedCurrency);
    }

    public static Money zero(String currency) {
        return of(0L, currency);
    }

    public long amountInCents() {
        return amountInCents;
    }

    public String currency() {
        return currency;
    }

    public boolean isZero() {
        return amountInCents == 0L;
    }

    public boolean isPositive() {
        return amountInCents > 0L;
    }

    public boolean isNegative() {
        return amountInCents < 0L;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountInCents, other.amountInCents), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountInCents, other.amountInCents), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountInCents), currency);
    }

    public Money abs() {
        return amountInCents >= 0L ? this : new Money(Math.abs(amountInCents), currency);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountInCents, other.amountInCents);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch");
        }
    }

    private static String normalizeCurrency(String currency) {
        Objects.requireNonNull(currency, "currency");
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("currency");
        }
        if (normalized.length() < 3 || normalized.length() > 4) {
            throw new IllegalArgumentException("currency");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return amountInCents == money.amountInCents && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountInCents, currency);
    }

    @Override
    public String toString() {
        return amountInCents + " " + currency;
    }
}
