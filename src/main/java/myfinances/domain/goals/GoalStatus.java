package myfinances.domain.goals;

public enum GoalStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
