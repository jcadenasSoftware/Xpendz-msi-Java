package myfinances.domain.goals;

import java.util.Objects;

public final class GoalTarget implements Comparable<GoalTarget> {

    private final Money amount;

    private GoalTarget(Money amount) {
        this.amount = amount;
    }

    public static GoalTarget of(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Goal target must be positive");
        }
        return new GoalTarget(amount);
    }

    public static GoalTarget of(long amountInCents, String currency) {
        return of(Money.of(amountInCents, currency));
    }

    public Money amount() {
        return amount;
    }

    public long amountInCents() {
        return amount.amountInCents();
    }

    public String currency() {
        return amount.currency();
    }

    @Override
    public int compareTo(GoalTarget other) {
        Objects.requireNonNull(other, "other");
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalTarget that)) {
            return false;
        }
        return amount.equals(that.amount);
    }

    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    @Override
    public String toString() {
        return amount.toString();
    }
}
