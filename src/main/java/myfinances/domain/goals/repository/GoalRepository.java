package myfinances.domain.goals.repository;

import myfinances.domain.goals.Goal;
import myfinances.domain.goals.GoalIdentifier;

import java.util.Optional;

/**
 * Domain repository contract for Goal aggregate.
 * 
 * This interface defines the conceptual contract between the Goals domain
 * and its infrastructure. It is intentionally platform-neutral and contains
 * no persistence-specific dependencies.
 * 
 * Implementations MAY use SQLite, Room, Firebase, or any other storage
 * mechanism, but this contract remains pure domain semantics.
 */
public interface GoalRepository {

    /**
     * Loads a Goal aggregate by its identifier.
     * 
     * @param id the goal identifier, must not be null
     * @return Optional containing the Goal if found, empty otherwise
     * @throws IllegalArgumentException if id is null
     */
    Optional<Goal> load(GoalIdentifier id);

    /**
     * Saves a Goal aggregate atomically with its version.
     * 
     * This operation MUST enforce optimistic concurrency. If the persisted
     * version is newer than the aggregate's version, the save MUST fail.
     * 
     * @param goal the goal aggregate to save, must not be null
     * @throws IllegalArgumentException if goal is null
     * @throws IllegalStateException if version conflict is detected
     */
    void save(Goal goal);

    /**
     * Checks if a Goal aggregate exists for the given identifier.
     * 
     * @param id the goal identifier, must not be null
     * @return true if a goal exists, false otherwise
     * @throws IllegalArgumentException if id is null
     */
    boolean exists(GoalIdentifier id);

    /**
     * Retrieves the current version of a Goal aggregate.
     * 
     * @param id the goal identifier, must not be null
     * @return Optional containing the version if found, empty otherwise
     * @throws IllegalArgumentException if id is null
     */
    Optional<Long> getVersion(GoalIdentifier id);

    /**
     * Deletes a Goal aggregate by its identifier.
     * 
     * This operation is terminal and cannot be undone. Implementations
     * SHOULD enforce business rules that prevent deletion of goals with
     * associated financial activity unless explicitly allowed.
     * 
     * @param id the goal identifier, must not be null
     * @throws IllegalArgumentException if id is null
     * @throws IllegalStateException if deletion is not permitted by business rules
     */
    void delete(GoalIdentifier id);
}
