package myfinances.domain.goals.events;

import myfinances.domain.goals.GoalIdentifier;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public abstract class GoalEvent {

    private final String eventId;
    private final GoalIdentifier goalId;
    private final Instant timestamp;
    private final String eventType;

    protected GoalEvent(GoalIdentifier goalId, String eventType) {
        this(UUID.randomUUID().toString(), goalId, Instant.now(), eventType);
    }

    protected GoalEvent(String eventId, GoalIdentifier goalId, Instant timestamp, String eventType) {
        this.eventId = normalizeEventId(eventId);
        this.goalId = Objects.requireNonNull(goalId, "goalId");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.eventType = normalizeEventType(eventType);
    }

    public String getEventId() {
        return eventId;
    }

    public GoalIdentifier getGoalId() {
        return goalId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    private static String normalizeEventId(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        String normalized = eventId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("eventId");
        }
        try {
            UUID.fromString(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("eventId must be a valid UUID", ex);
        }
        return normalized;
    }

    private static String normalizeEventType(String eventType) {
        Objects.requireNonNull(eventType, "eventType");
        String normalized = eventType.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("eventType");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GoalEvent goalEvent = (GoalEvent) o;
        return eventId.equals(goalEvent.eventId);
    }

    @Override
    public int hashCode() {
        return eventId.hashCode();
    }
}
