package myfinances.domain.goals.rehydration;

import myfinances.domain.goals.Goal;
import myfinances.domain.goals.GoalDate;
import myfinances.domain.goals.GoalDescription;
import myfinances.domain.goals.GoalIdentifier;
import myfinances.domain.goals.GoalStatus;
import myfinances.domain.goals.GoalTarget;
import myfinances.domain.goals.snapshot.GoalSnapshotFactory;

import java.util.Objects;

/**
 * Factory for rehydrating Goal aggregates from persisted data.
 * 
 * This factory reconstructs existing Goal instances from their persisted
 * representation WITHOUT emitting GoalCreated events. This is fundamentally
 * different from creating a new Goal via Goal.create().
 * 
 * Rehydration is used when loading goals from storage, backups, or
 * synchronization sources. The factory ensures that:
 * - Identity is preserved exactly
 * - State is restored exactly
 * - Version is restored exactly
 * - No domain events are emitted during reconstruction
 * - All invariants are maintained
 */
public final class GoalRehydrationFactory {

    private GoalRehydrationFactory() {
        // Utility class
    }

    /**
     * Rehydrates a Goal from its persisted representation.
     * 
     * This method reconstructs a Goal aggregate without emitting any
     * domain events. It delegates to Goal.rehydrate() which is the
     * exclusive mechanism for restoring goals from storage.
     * 
     * @param id the goal identifier, must not be null
     * @param status the goal status, must not be null
     * @param target the goal target, must not be null
     * @param targetDate the goal target date, must not be null
     * @param description the goal description, must not be null
     * @param archived whether the goal is archived
     * @param savingsAccountId the associated savings account ID, must not be null
     * @param version the aggregate version, must be >= 0
     * @return the rehydrated Goal aggregate
     * @throws IllegalArgumentException if any required parameter is null or invalid
     * @throws IllegalStateException if version is negative
     */
    public static Goal rehydrate(
        GoalIdentifier id,
        GoalStatus status,
        GoalTarget target,
        GoalDate targetDate,
        GoalDescription description,
        boolean archived,
        String savingsAccountId,
        long version
    ) {
        return Goal.rehydrate(id, status, target, targetDate, description, archived, savingsAccountId, version);
    }

    /**
     * Rehydrates a Goal from a snapshot representation.
     * 
     * This is a convenience method for snapshot-based reconstruction.
     * Snapshots are optimization artifacts, not source of truth.
     * 
     * @param snapshot the snapshot data, must not be null
     * @return the rehydrated Goal aggregate
     * @throws IllegalArgumentException if snapshot is null
     */
    public static Goal rehydrateFromSnapshot(GoalSnapshotFactory.GoalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        return rehydrate(
            snapshot.id(),
            snapshot.status(),
            snapshot.target(),
            snapshot.targetDate(),
            snapshot.description(),
            snapshot.archived(),
            snapshot.savingsAccountId(),
            snapshot.version()
        );
    }
}
