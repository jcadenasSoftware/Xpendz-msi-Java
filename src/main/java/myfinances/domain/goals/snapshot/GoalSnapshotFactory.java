package myfinances.domain.goals.snapshot;

import myfinances.domain.goals.Goal;
import myfinances.domain.goals.GoalDate;
import myfinances.domain.goals.GoalDescription;
import myfinances.domain.goals.GoalIdentifier;
import myfinances.domain.goals.GoalStatus;
import myfinances.domain.goals.GoalTarget;
import myfinances.domain.goals.rehydration.GoalRehydrationFactory;

import java.util.Objects;

/**
 * Factory for creating snapshots of Goal aggregates.
 * 
 * Snapshots are compact representations of the Goal aggregate at a specific
 * version, used to accelerate rehydration. They are optimization artifacts,
 * NOT source of truth.
 * 
 * This factory prepares the domain for snapshot capability without
 * implementing persistence. The actual storage of snapshots is an
 * infrastructure concern.
 */
public final class GoalSnapshotFactory {

    private GoalSnapshotFactory() {
        // Utility class
    }

    /**
     * Creates a snapshot from a Goal aggregate.
     * 
     * The snapshot captures the complete business state required to
     * accelerate future rehydration. It does NOT contain:
     * - Domain events
     * - Event buffer
     * - Transaction history
     * - Projections
     * 
     * @param goal the goal aggregate, must not be null
     * @return a snapshot of the goal at its current version
     * @throws IllegalArgumentException if goal is null
     */
    public static GoalSnapshot createSnapshot(Goal goal) {
        Objects.requireNonNull(goal, "goal");

        return new GoalSnapshot(
            goal.getId(),
            goal.getStatus(),
            goal.getTarget(),
            goal.getTargetDate(),
            goal.getDescription(),
            goal.isArchived(),
            goal.getSavingsAccountId(),
            goal.getVersion()
        );
    }

    /**
     * Snapshot data structure for Goal aggregates.
     * 
     * This record represents a compact snapshot of a Goal aggregate
     * at a specific version. It contains only the business state
     * required for reconstruction, not historical events.
     * 
     * Snapshots are optimization artifacts, not source of truth.
     * If snapshot and history conflict, history wins.
     */
    public record GoalSnapshot(
        GoalIdentifier id,
        GoalStatus status,
        GoalTarget target,
        GoalDate targetDate,
        GoalDescription description,
        boolean archived,
        String savingsAccountId,
        long version
    ) {
        public GoalSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(targetDate, "targetDate");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(savingsAccountId, "savingsAccountId");
            if (version < 0) {
                throw new IllegalArgumentException("version");
            }
        }
    }
}
