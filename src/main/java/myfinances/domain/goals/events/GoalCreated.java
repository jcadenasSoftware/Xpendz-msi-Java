package myfinances.domain.goals.events;

import myfinances.domain.goals.GoalDate;
import myfinances.domain.goals.GoalDescription;
import myfinances.domain.goals.GoalIdentifier;
import myfinances.domain.goals.GoalTarget;

import java.util.Objects;

public final class GoalCreated extends GoalEvent {

    private static final String TYPE = "GoalCreated";

    private final GoalTarget target;
    private final GoalDate targetDate;
    private final GoalDescription description;
    private final String savingsAccountId;

    public GoalCreated(
        GoalIdentifier goalId,
        GoalTarget target,
        GoalDate targetDate,
        GoalDescription description,
        String savingsAccountId
    ) {
        super(goalId, TYPE);
        this.target = Objects.requireNonNull(target, "target");
        this.targetDate = Objects.requireNonNull(targetDate, "targetDate");
        this.description = Objects.requireNonNull(description, "description");
        this.savingsAccountId = normalizeSavingsAccountId(savingsAccountId);
    }

    public GoalTarget getTarget() {
        return target;
    }

    public GoalDate getTargetDate() {
        return targetDate;
    }

    public GoalDescription getDescription() {
        return description;
    }

    public String getSavingsAccountId() {
        return savingsAccountId;
    }

    private static String normalizeSavingsAccountId(String savingsAccountId) {
        Objects.requireNonNull(savingsAccountId, "savingsAccountId");
        String normalized = savingsAccountId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("savingsAccountId");
        }
        return normalized;
    }
}
