package myfinances.domain.loan.journal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import myfinances.domain.loan.diagnostics.LoanDiagnostic;
import myfinances.domain.loan.diagnostics.LoanDiagnosticCode;

public final class LoanJournalCanonicalizer {
    public CanonicalizationResult canonicalize(LoanJournalEntry entry) {
        if (entry == null) {
            return invalid(null);
        }
        if (entry.eventSchemaVersion() != 1) {
            return diagnostic(entry.eventId(), LoanDiagnosticCode.UNSUPPORTED_EVENT_VERSION);
        }

        EventMapping mapping = mapEventType(entry.rawEventType());
        if (mapping == null) {
            return diagnostic(entry.eventId(), LoanDiagnosticCode.UNSUPPORTED_EVENT_TYPE);
        }
        if (isBlank(entry.eventId()) || isBlank(entry.operationId()) || isBlank(entry.loanId()) || isBlank(entry.ownerId()) || entry.rawPayload() == null) {
            return invalid(entry.eventId());
        }

        try {
            validateAmountShape(entry.amountCents(), mapping.eventType());
            List<LoanDiagnostic> diagnostics = aliasDiagnostics(entry, mapping);
            LoanEventPayload payload = payloadFor(entry.rawPayload(), mapping);
            return new CanonicalizationResult(
                new LoanMovement(
                    entry.eventId(),
                    entry.operationId(),
                    entry.loanId(),
                    entry.ownerId(),
                    mapping.eventType(),
                    entry.eventSchemaVersion(),
                    entry.amountCents(),
                    entry.accountId(),
                    entry.transactionId(),
                    entry.note(),
                    entry.occurredAt(),
                    entry.recordedAt(),
                    entry.actorId(),
                    entry.originId(),
                    payload
                ),
                diagnostics
            );
        } catch (IllegalArgumentException ex) {
            return invalid(entry.eventId());
        }
    }

    private static void validateAmountShape(Long amountCents, LoanEventType eventType) {
        boolean requiresAmount = switch (eventType) {
            case CREATION, TOPUP, PAYMENT, ADJUSTMENT -> true;
            case METADATA_CHANGED, REVERSAL, CLOSE -> false;
        };
        if (requiresAmount != (amountCents != null)) {
            throw new IllegalArgumentException();
        }
    }

    private static List<LoanDiagnostic> aliasDiagnostics(LoanJournalEntry entry, EventMapping mapping) {
        if (mapping.legacyDirection() == null) {
            return List.of();
        }
        String rawDirection = optionalString(entry.rawPayload(), "legacyDirection");
        if (rawDirection == null) {
            return List.of();
        }
        LoanEventPayload.LegacyPaymentDirection payloadDirection;
        try {
            payloadDirection = LoanEventPayload.LegacyPaymentDirection.valueOf(rawDirection);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException();
        }
        if (payloadDirection == mapping.legacyDirection()) {
            return List.of();
        }
        return List.of(new LoanDiagnostic(
            LoanDiagnosticCode.LEGACY_DIRECTION_MISMATCH,
            entry.eventId(),
            null
        ));
    }

    private static LoanEventPayload payloadFor(Map<String, Object> raw, EventMapping mapping) {
        return switch (mapping.eventType()) {
            case CREATION -> creationPayload(raw);
            case TOPUP -> {
                requireKeys(raw, Set.of());
                yield new LoanEventPayload.EmptyPayload();
            }
            case PAYMENT -> paymentPayload(raw, mapping.legacyDirection());
            case ADJUSTMENT -> adjustmentPayload(raw);
            case METADATA_CHANGED -> metadataPayload(raw);
            case REVERSAL -> reversalPayload(raw);
            case CLOSE -> closePayload(raw);
        };
    }

    private static LoanEventPayload creationPayload(Map<String, Object> raw) {
        requireKeys(raw, Set.of("loanType", "counterpartyName", "currency", "defaultAccountId", "notes"));
        String rawLoanType = requiredString(raw, "loanType");
        LoanType loanType;
        try {
            loanType = LoanType.valueOf(rawLoanType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException();
        }
        return new LoanEventPayload.CreationPayload(
            loanType,
            requiredString(raw, "counterpartyName"),
            requiredString(raw, "currency"),
            optionalString(raw, "defaultAccountId"),
            optionalString(raw, "notes")
        );
    }

    private static LoanEventPayload paymentPayload(Map<String, Object> raw, LoanEventPayload.LegacyPaymentDirection aliasDirection) {
        requireKeys(raw, Set.of("legacyDirection", "legacySource"));
        LoanEventPayload.LegacyPaymentDirection direction = aliasDirection;
        String rawDirection = optionalString(raw, "legacyDirection");
        if (direction == null && rawDirection != null) {
            try {
                direction = LoanEventPayload.LegacyPaymentDirection.valueOf(rawDirection);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException();
            }
        }
        return new LoanEventPayload.PaymentPayload(direction, optionalString(raw, "legacySource"));
    }

    private static LoanEventPayload adjustmentPayload(Map<String, Object> raw) {
        requireKeys(raw, Set.of("reason", "legacySource"));
        return new LoanEventPayload.AdjustmentPayload(
            requiredString(raw, "reason"),
            optionalString(raw, "legacySource")
        );
    }

    private static LoanEventPayload metadataPayload(Map<String, Object> raw) {
        requireKeys(raw, Set.of("changes", "legacySource"));
        Object changesValue = raw.get("changes");
        if (!(changesValue instanceof Map<?, ?> untypedChanges)) {
            throw new IllegalArgumentException();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) untypedChanges;
        requireKeys(changes, Set.of("counterpartyName", "defaultAccountId", "notes"));
        if (changes.isEmpty()) {
            throw new IllegalArgumentException();
        }
        return new LoanEventPayload.MetadataChangedPayload(
            new LoanEventPayload.MetadataChanges(
                fieldValue(changes, "counterpartyName", false),
                fieldValue(changes, "defaultAccountId", true),
                fieldValue(changes, "notes", true)
            ),
            optionalString(raw, "legacySource")
        );
    }

    private static LoanEventPayload reversalPayload(Map<String, Object> raw) {
        requireKeys(raw, Set.of("targetEventId", "reason"));
        return new LoanEventPayload.ReversalPayload(
            requiredString(raw, "targetEventId"),
            requiredString(raw, "reason")
        );
    }

    private static LoanEventPayload closePayload(Map<String, Object> raw) {
        requireKeys(raw, Set.of("reason"));
        return new LoanEventPayload.ClosePayload(optionalString(raw, "reason"));
    }

    private static LoanEventPayload.FieldValue<String> fieldValue(
        Map<String, Object> values,
        String key,
        boolean nullable
    ) {
        if (!values.containsKey(key)) {
            return new LoanEventPayload.FieldValue<>(false, null);
        }
        Object value = values.get(key);
        if ((!nullable && value == null) || (value != null && !(value instanceof String))) {
            throw new IllegalArgumentException();
        }
        return new LoanEventPayload.FieldValue<>(true, (String) value);
    }

    private static String requiredString(Map<String, Object> values, String key) {
        String value = optionalString(values, key);
        if (isBlank(value)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static String optionalString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException();
        }
        return stringValue;
    }

    private static void requireKeys(Map<String, ?> values, Set<String> allowed) {
        if (!allowed.containsAll(values.keySet())) {
            throw new IllegalArgumentException();
        }
    }

    private static EventMapping mapEventType(String rawEventType) {
        if (rawEventType == null) {
            return null;
        }
        return switch (rawEventType) {
            case "CREATION" -> new EventMapping(LoanEventType.CREATION, null);
            case "TOPUP" -> new EventMapping(LoanEventType.TOPUP, null);
            case "PAYMENT" -> new EventMapping(LoanEventType.PAYMENT, null);
            case "PAYMENT_IN" -> new EventMapping(LoanEventType.PAYMENT, LoanEventPayload.LegacyPaymentDirection.IN);
            case "PAYMENT_OUT" -> new EventMapping(LoanEventType.PAYMENT, LoanEventPayload.LegacyPaymentDirection.OUT);
            case "ADJUSTMENT" -> new EventMapping(LoanEventType.ADJUSTMENT, null);
            case "METADATA_CHANGED" -> new EventMapping(LoanEventType.METADATA_CHANGED, null);
            case "REVERSAL" -> new EventMapping(LoanEventType.REVERSAL, null);
            case "CLOSE" -> new EventMapping(LoanEventType.CLOSE, null);
            default -> null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static CanonicalizationResult invalid(String eventId) {
        return diagnostic(eventId, LoanDiagnosticCode.INVALID_EVENT_PAYLOAD);
    }

    private static CanonicalizationResult diagnostic(String eventId, LoanDiagnosticCode code) {
        return new CanonicalizationResult(
            null,
            List.of(new LoanDiagnostic(code, eventId, null))
        );
    }

    private record EventMapping(
        LoanEventType eventType,
        LoanEventPayload.LegacyPaymentDirection legacyDirection
    ) {}

    public record CanonicalizationResult(
        LoanMovement event,
        List<LoanDiagnostic> diagnostics
    ) {}
}
