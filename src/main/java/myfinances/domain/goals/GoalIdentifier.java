package myfinances.domain.goals;

import java.util.Objects;
import java.util.UUID;

public final class GoalIdentifier {

    private final String value;

    private GoalIdentifier(String value) {
        this.value = value;
    }

    public static GoalIdentifier of(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value");
        }
        try {
            UUID.fromString(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("value must be a valid UUID", ex);
        }
        return new GoalIdentifier(normalized);
    }

    public static GoalIdentifier random() {
        return new GoalIdentifier(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalIdentifier that)) {
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
