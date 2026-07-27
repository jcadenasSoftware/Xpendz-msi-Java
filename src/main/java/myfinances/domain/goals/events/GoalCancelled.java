package myfinances.domain.goals.events;

import myfinances.domain.goals.GoalIdentifier;

public final class GoalCancelled extends GoalEvent {

    private static final String TYPE = "GoalCancelled";

    public GoalCancelled(GoalIdentifier goalId) {
        super(goalId, TYPE);
    }
}
