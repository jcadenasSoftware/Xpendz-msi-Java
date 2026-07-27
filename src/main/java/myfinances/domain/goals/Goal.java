package myfinances.domain.goals;

import myfinances.domain.goals.events.GoalCancelled;
import myfinances.domain.goals.events.GoalCompleted;
import myfinances.domain.goals.events.GoalCreated;
import myfinances.domain.goals.events.GoalEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Goal {

    private final GoalIdentifier id;
    private GoalStatus status;
    private final GoalTarget target;
    private final GoalDate targetDate;
    private final GoalDescription description;
    private boolean archived;
    private final String savingsAccountId;
    private long version;
    private final List<GoalEvent> domainEvents = new ArrayList<>();

    private Goal(
        GoalIdentifier id,
        GoalStatus status,
        GoalTarget target,
        GoalDate targetDate,
        GoalDescription description,
        boolean archived,
        String savingsAccountId,
        long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.status = Objects.requireNonNull(status, "status");
        this.target = Objects.requireNonNull(target, "target");
        this.targetDate = Objects.requireNonNull(targetDate, "targetDate");
        this.description = Objects.requireNonNull(description, "description");
        this.archived = archived;
        this.savingsAccountId = normalizeSavingsAccountId(savingsAccountId);
        this.version = version;
    }

    public static Goal create(
        GoalIdentifier id,
        GoalTarget target,
        GoalDate targetDate,
        GoalDescription description,
        String savingsAccountId
    ) {
        Goal goal = new Goal(id, GoalStatus.ACTIVE, target, targetDate, description, false, savingsAccountId, 0L);
        goal.recordEvent(new GoalCreated(goal.id, target, targetDate, description, goal.savingsAccountId));
        return goal;
    }

    /**
     * Rehydrates a Goal from persisted data WITHOUT emitting domain events.
     * 
     * This method is exclusively for reconstructing existing Goal aggregates
     * from storage, backups, or synchronization sources. It MUST NOT be used
     * for creating new goals - use Goal.create() for that purpose.
     * 
     * Rehydration preserves identity, state, version, and archived status
     * without triggering any domain events.
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
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetDate, "targetDate");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(savingsAccountId, "savingsAccountId");

        if (version < 0) {
            throw new IllegalStateException("Version cannot be negative");
        }

        return new Goal(id, status, target, targetDate, description, archived, savingsAccountId, version);
    }

    public GoalIdentifier getId() {
        return id;
    }

    public GoalStatus getStatus() {
        return status;
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

    public boolean isArchived() {
        return archived;
    }

    public String getSavingsAccountId() {
        return savingsAccountId;
    }

    public long getVersion() {
        return version;
    }

    public List<GoalEvent> getDomainEvents() {
        return Collections.unmodifiableList(new ArrayList<>(domainEvents));
    }

    public void complete() {
        ensureActive("complete");
        status = GoalStatus.COMPLETED;
        version++;
        recordEvent(new GoalCompleted(id));
    }

    public void cancel() {
        ensureActive("cancel");
        status = GoalStatus.CANCELLED;
        version++;
        recordEvent(new GoalCancelled(id));
    }

    public void archive() {
        if (archived) {
            return;
        }
        archived = true;
        version++;
    }

    public void unarchive() {
        if (!archived) {
            return;
        }
        archived = false;
        version++;
    }

    public boolean isActive() {
        return status.isActive();
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    private void ensureActive(String operation) {
        if (!status.isActive()) {
            throw new IllegalStateException("Cannot " + operation + " a goal that is " + status);
        }
    }

    private void recordEvent(GoalEvent event) {
        domainEvents.add(Objects.requireNonNull(event, "event"));
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    private static String normalizeSavingsAccountId(String savingsAccountId) {
        Objects.requireNonNull(savingsAccountId, "savingsAccountId");
        String normalized = savingsAccountId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("savingsAccountId");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Goal goal)) {
            return false;
        }
        return id.equals(goal.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Goal{" +
            "id=" + id +
            ", status=" + status +
            ", archived=" + archived +
            ", version=" + version +
            ", target=" + target +
            ", targetDate=" + targetDate +
            ", description=" + description +
            ", savingsAccountId='" + savingsAccountId + '\'' +
            '}';
    }
}
