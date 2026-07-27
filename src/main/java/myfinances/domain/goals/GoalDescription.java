package myfinances.domain.goals;

import java.util.Objects;

public final class GoalDescription {

    private final String value;

    private GoalDescription(String value) {
        this.value = value;
    }

    public static GoalDescription of(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value");
        }
        return new GoalDescription(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalDescription that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
