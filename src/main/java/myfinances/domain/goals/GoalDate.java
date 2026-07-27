package myfinances.domain.goals;

import java.time.LocalDate;
import java.util.Objects;

public final class GoalDate implements Comparable<GoalDate> {

    private final LocalDate value;

    private GoalDate(LocalDate value) {
        this.value = value;
    }

    public static GoalDate of(LocalDate value) {
        Objects.requireNonNull(value, "value");
        return new GoalDate(value);
    }

    public LocalDate value() {
        return value;
    }

    @Override
    public int compareTo(GoalDate other) {
        Objects.requireNonNull(other, "other");
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalDate goalDate)) {
            return false;
        }
        return value.equals(goalDate.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
