package myfinances.domain.goals.events;

import myfinances.domain.goals.GoalIdentifier;

public final class GoalCompleted extends GoalEvent {

    private static final String TYPE = "GoalCompleted";

    public GoalCompleted(GoalIdentifier goalId) {
        super(goalId, TYPE);
    }
}
