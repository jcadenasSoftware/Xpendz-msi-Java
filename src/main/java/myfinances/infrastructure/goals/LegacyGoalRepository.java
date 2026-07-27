package myfinances.infrastructure.goals;

import myfinances.domain.goals.Goal;
import myfinances.domain.goals.GoalIdentifier;
import myfinances.domain.goals.adapter.GoalLegacyMapper;
import myfinances.domain.goals.repository.GoalRepository;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Infrastructure implementation of GoalRepository that bridges the DDD domain
 * with the legacy persistence model.
 * 
 * This repository acts as an adapter between:
 * 
 * Domain Goal → GoalLegacyMapper → Legacy GoalRepository.Goal
 * 
 * It uses the legacy GoalRepository for actual persistence and GoalLegacyMapper
 * for all conversions between domain and legacy models.
 * 
 * === TEMPORARY IMPLEMENTATION NOTES ===
 * 
 * 1. **Versioning**: The legacy model does not support aggregate versioning.
 *    getVersion() returns 0 as a temporary implementation. Future phases should
 *    add version column to legacy schema or migrate to event-sourced storage.
 * 
 * 2. **Concurrency**: Optimistic locking is not implemented in this phase.
 *    The save() method delegates to legacy update without version checks.
 *    Future phases should implement version-based concurrency control.
 * 
 * 3. **Event Store**: No event store is implemented in this phase.
 *    Domain events are not persisted. Future phases should implement
 *    event store for full event sourcing capability.
 * 
 * 4. **Snapshot Store**: No snapshot store is implemented in this phase.
 *    Future phases should implement snapshot store for performance optimization.
 * 
 * === ARCHITECTURAL BOUNDARIES ===
 * 
 * - This class depends on legacy GoalRepository and GoalLegacyMapper
 * - The domain (myfinances.domain.goals) does NOT depend on this class
 * - All conversions go through GoalLegacyMapper (no manual conversion)
 * - No direct SQLite access from domain
 */
public final class LegacyGoalRepository implements GoalRepository {

    private final com.myfinaces.db.GoalRepository legacyGoalRepo;
    private final String userUid;

    /**
     * Creates a new legacy goal repository.
     * 
     * @param legacyGoalRepo the legacy goal repository, must not be null
     * @param userUid the user UID for scoping operations, must not be null
     */
    public LegacyGoalRepository(com.myfinaces.db.GoalRepository legacyGoalRepo, String userUid) {
        this.legacyGoalRepo = java.util.Objects.requireNonNull(legacyGoalRepo, "legacyGoalRepo");
        this.userUid = java.util.Objects.requireNonNull(userUid, "userUid");
    }

    @Override
    public Optional<Goal> load(GoalIdentifier id) {
        java.util.Objects.requireNonNull(id, "id");

        try {
            com.myfinaces.db.GoalRepository.Goal legacy = legacyGoalRepo.getByIdOrNull(userUid, id.value());
            if (legacy == null) {
                return Optional.empty();
            }
            
            // Convert using GoalLegacyMapper (uses Goal.rehydrate internally)
            Goal domain = GoalLegacyMapper.toDomain(legacy);
            return Optional.of(domain);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load goal: " + id, e);
        }
    }

    @Override
    public void save(Goal goal) {
        java.util.Objects.requireNonNull(goal, "goal");

        GoalIdentifier id = goal.getId();
        com.myfinaces.db.GoalRepository.Goal existingLegacy = null;
        
        // Check if goal exists in legacy
        try {
            existingLegacy = legacyGoalRepo.getByIdOrNull(userUid, id.value());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check goal existence: " + id, e);
        }

        // Convert domain to legacy using GoalLegacyMapper
        com.myfinaces.db.GoalRepository.Goal legacy = GoalLegacyMapper.toLegacy(goal, userUid);

        try {
            if (existingLegacy == null) {
                // Create new goal in legacy
                legacyGoalRepo.create(
                    userUid,
                    legacy.name(),
                    legacy.currency(),
                    legacy.targetCents(),
                    legacy.targetDateEpochSec(),
                    legacy.accountId()
                );
            } else {
                // Update existing goal in legacy
                legacyGoalRepo.update(
                    userUid,
                    legacy.id(),
                    legacy.name(),
                    legacy.currency(),
                    legacy.targetCents(),
                    legacy.targetDateEpochSec(),
                    legacy.accountId(),
                    legacy.status()
                );
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save goal: " + id, e);
        }

        // TODO: Future phase - implement version-based optimistic locking
        // TODO: Future phase - persist domain events to event store
        // TODO: Future phase - create snapshot if threshold reached
    }

    @Override
    public boolean exists(GoalIdentifier id) {
        java.util.Objects.requireNonNull(id, "id");

        try {
            com.myfinaces.db.GoalRepository.Goal legacy = legacyGoalRepo.getByIdOrNull(userUid, id.value());
            return legacy != null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check goal existence: " + id, e);
        }
    }

    @Override
    public Optional<Long> getVersion(GoalIdentifier id) {
        java.util.Objects.requireNonNull(id, "id");

        // TEMPORARY IMPLEMENTATION: Legacy model does not support versioning
        // Returns 0 as a placeholder. Future phases should:
        // 1. Add version column to legacy schema, OR
        // 2. Migrate to event-sourced storage with version tracking
        
        try {
            com.myfinaces.db.GoalRepository.Goal legacy = legacyGoalRepo.getByIdOrNull(userUid, id.value());
            if (legacy == null) {
                return Optional.empty();
            }
            return Optional.of(0L); // Legacy has no version field
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get goal version: " + id, e);
        }
    }

    @Override
    public void delete(GoalIdentifier id) {
        java.util.Objects.requireNonNull(id, "id");

        try {
            legacyGoalRepo.delete(userUid, id.value());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete goal: " + id, e);
        }
    }
}
