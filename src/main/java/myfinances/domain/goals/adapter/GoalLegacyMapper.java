package myfinances.domain.goals.adapter;

import com.myfinaces.db.GoalRepository;
import myfinances.domain.goals.Goal;
import myfinances.domain.goals.GoalDate;
import myfinances.domain.goals.GoalDescription;
import myfinances.domain.goals.GoalIdentifier;
import myfinances.domain.goals.GoalStatus;
import myfinances.domain.goals.GoalTarget;
import myfinances.domain.goals.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Anti-Corruption Layer: Mapper between legacy Goal model and new Domain Goal.
 * 
 * This adapter provides bidirectional conversion between the legacy persistence
 * model (GoalRepository.Goal) and the new domain model (Goal). It is the exclusive
 * location for conversion logic - no conversion logic exists in aggregates,
 * repositories, or services.
 * 
 * === LEGACY MODEL LIMITATIONS ===
 * 
 * The legacy model has the following limitations compared to the domain:
 * 
 * 1. **Status Ambiguity**: Legacy has only OPEN/CLOSED, while domain has
 *    ACTIVE/COMPLETED/CANCELLED. Legacy CLOSED cannot distinguish between
 *    COMPLETED and CANCELLED. We map CLOSED → COMPLETED as a conservative
 *    default, but this is a documented limitation.
 * 
 * 2. **No Archived Flag**: Legacy has no concept of archived goals. We default
 *    to false during legacy→domain conversion.
 * 
 * 3. **No Versioning**: Legacy has no aggregate version. We default to 0 during
 *    legacy→domain conversion.
 * 
 * 4. **Field Naming**: Legacy uses "name" and "targetCents", while domain uses
 *    "description" and "target" with Money value object.
 * 
 * === CONVERSION RULES ===
 * 
 * Legacy → Domain:
 * - OPEN → ACTIVE
 * - CLOSED → COMPLETED (documented limitation: loses CANCELLED distinction)
 * - archived → false (legacy has no equivalent)
 * - version → 0 (legacy has no equivalent)
 * - Uses Goal.rehydrate() (NO events emitted)
 * 
 * Domain → Legacy:
 * - ACTIVE → OPEN
 * - COMPLETED → CLOSED
 * - CANCELLED → CLOSED (loses CANCELLED distinction)
 * - archived → ignored (legacy has no equivalent)
 * - version → ignored (legacy has no equivalent)
 */
public final class GoalLegacyMapper {

    private GoalLegacyMapper() {
        // Utility class
    }

    /**
     * Converts a legacy Goal to a Domain Goal.
     * 
     * This method uses Goal.rehydrate() to reconstruct the aggregate WITHOUT
     * emitting domain events. It respects the documented limitations of the
     * legacy model.
     * 
     * @param legacy the legacy goal, must not be null
     * @return the domain goal
     * @throws IllegalArgumentException if legacy is null
     */
    public static Goal toDomain(GoalRepository.Goal legacy) {
        Objects.requireNonNull(legacy, "legacy");

        GoalIdentifier id = GoalIdentifier.of(legacy.id());
        GoalStatus status = mapStatusToDomain(legacy.status());
        GoalTarget target = mapTargetToDomain(legacy.targetCents(), legacy.currency());
        GoalDate targetDate = mapDateToDomain(legacy.targetDateEpochSec());
        GoalDescription description = GoalDescription.of(legacy.name());
        boolean archived = false; // Legacy has no archived flag
        String savingsAccountId = legacy.accountId();
        long version = 0; // Legacy has no version

        // Use rehydrate to avoid emitting GoalCreated event
        return Goal.rehydrate(id, status, target, targetDate, description, archived, savingsAccountId, version);
    }

    /**
     * Converts a Domain Goal to a legacy Goal.
     * 
     * This method maps domain concepts to legacy representation, respecting
     * the documented limitations of the legacy model.
     * 
     * @param domain the domain goal, must not be null
     * @param userUid the user UID, must not be null
     * @return the legacy goal
     * @throws IllegalArgumentException if domain or userUid is null
     */
    public static GoalRepository.Goal toLegacy(Goal domain, String userUid) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(userUid, "userUid");

        String id = domain.getId().value();
        String name = domain.getDescription().value();
        String currency = domain.getTarget().currency();
        long targetCents = domain.getTarget().amountInCents();
        long targetDateEpochSec = mapDateToLegacy(domain.getTargetDate());
        String accountId = domain.getSavingsAccountId();
        String status = mapStatusToLegacy(domain.getStatus());
        long createdAtEpochSec = Instant.now().getEpochSecond(); // Legacy has no creation timestamp in domain
        long updatedAtEpochSec = Instant.now().getEpochSecond();

        return new GoalRepository.Goal(
            id,
            userUid,
            name,
            currency,
            targetCents,
            targetDateEpochSec,
            accountId,
            status,
            createdAtEpochSec,
            updatedAtEpochSec
        );
    }

    /**
     * Maps legacy status to domain status.
     * 
     * OPEN → ACTIVE
     * CLOSED → COMPLETED (documented limitation: loses CANCELLED distinction)
     * 
     * @param legacyStatus the legacy status, must not be null
     * @return the domain status
     * @throws IllegalArgumentException if legacyStatus is null or invalid
     */
    private static GoalStatus mapStatusToDomain(String legacyStatus) {
        Objects.requireNonNull(legacyStatus, "legacyStatus");

        if (GoalRepository.STATUS_OPEN.equals(legacyStatus)) {
            return GoalStatus.ACTIVE;
        } else if (GoalRepository.STATUS_CLOSED.equals(legacyStatus)) {
            return GoalStatus.COMPLETED; // Limitation: cannot distinguish COMPLETED from CANCELLED
        } else {
            throw new IllegalArgumentException("Invalid legacy status: " + legacyStatus);
        }
    }

    /**
     * Maps domain status to legacy status.
     * 
     * ACTIVE → OPEN
     * COMPLETED → CLOSED
     * CANCELLED → CLOSED (loses CANCELLED distinction)
     * 
     * @param domainStatus the domain status, must not be null
     * @return the legacy status
     * @throws IllegalArgumentException if domainStatus is null
     */
    private static String mapStatusToLegacy(GoalStatus domainStatus) {
        Objects.requireNonNull(domainStatus, "domainStatus");

        switch (domainStatus) {
            case ACTIVE:
                return GoalRepository.STATUS_OPEN;
            case COMPLETED:
            case CANCELLED:
                return GoalRepository.STATUS_CLOSED; // Limitation: loses CANCELLED distinction
            default:
                throw new IllegalArgumentException("Unsupported domain status: " + domainStatus);
        }
    }

    /**
     * Maps legacy target to domain target.
     * 
     * @param targetCents the target amount in cents
     * @param currency the currency code
     * @return the domain goal target
     */
    private static GoalTarget mapTargetToDomain(long targetCents, String currency) {
        Money money = Money.of(targetCents, currency);
        return GoalTarget.of(money);
    }

    /**
     * Maps legacy epoch seconds to domain date.
     * 
     * @param epochSec the epoch seconds
     * @return the domain goal date
     */
    private static GoalDate mapDateToDomain(long epochSec) {
        LocalDate date = Instant.ofEpochSecond(epochSec)
            .atZone(ZoneId.systemDefault())
            .toLocalDate();
        return GoalDate.of(date);
    }

    /**
     * Maps domain date to legacy epoch seconds.
     * 
     * @param goalDate the domain goal date
     * @return the epoch seconds
     */
    private static long mapDateToLegacy(GoalDate goalDate) {
        return goalDate.value()
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond();
    }
}
