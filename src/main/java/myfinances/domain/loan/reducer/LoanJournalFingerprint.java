package myfinances.domain.loan.reducer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import myfinances.domain.loan.journal.LoanEventPayload;
import myfinances.domain.loan.journal.LoanMovement;

public final class LoanJournalFingerprint {
    private static final Comparator<LoanMovement> CANONICAL_ORDER = Comparator
        .comparingLong(LoanMovement::occurredAt)
        .thenComparingLong(LoanMovement::recordedAt)
        .thenComparing(LoanMovement::eventId);

    private LoanJournalFingerprint() {}

    public static String compute(List<LoanMovement> events) {
        String canonicalJson = canonicalJson(events);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String canonicalJson(List<LoanMovement> events) {
        List<LoanMovement> ordered = events.stream().sorted(CANONICAL_ORDER).toList();
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendEvent(out, ordered.get(i));
        }
        return out.append(']').toString();
    }

    private static void appendEvent(StringBuilder out, LoanMovement event) {
        out.append('{');
        field(out, "account_id", event.accountId(), false);
        field(out, "actor_id", event.actorId(), true);
        field(out, "amount_cents", event.amountCents(), true);
        field(out, "event_id", event.eventId(), true);
        field(out, "event_schema_version", event.eventSchemaVersion(), true);
        field(out, "event_type", event.eventType().name(), true);
        field(out, "loan_id", event.loanId(), true);
        field(out, "note", event.note(), true);
        field(out, "occurred_at", event.occurredAt(), true);
        field(out, "operation_id", event.operationId(), true);
        field(out, "origin_id", event.originId(), true);
        field(out, "owner_id", event.ownerId(), true);
        appendKey(out, "payload", true);
        appendPayload(out, event.payload());
        field(out, "recorded_at", event.recordedAt(), true);
        field(out, "transaction_id", event.transactionId(), true);
        out.append('}');
    }

    private static void appendPayload(StringBuilder out, LoanEventPayload payload) {
        switch (payload) {
            case LoanEventPayload.EmptyPayload ignored -> out.append("{}");
            case LoanEventPayload.CreationPayload creation -> {
                out.append('{');
                field(out, "counterparty_name", creation.counterpartyName(), false);
                field(out, "currency", creation.currency(), true);
                field(out, "default_account_id", creation.defaultAccountId(), true);
                field(out, "loan_type", creation.loanType().name(), true);
                field(out, "notes", creation.notes(), true);
                out.append('}');
            }
            case LoanEventPayload.PaymentPayload payment -> {
                out.append('{');
                field(out, "legacy_direction", payment.legacyDirection() == null ? null : payment.legacyDirection().name(), false);
                field(out, "legacy_source", payment.legacySource(), true);
                out.append('}');
            }
            case LoanEventPayload.AdjustmentPayload adjustment -> {
                out.append('{');
                field(out, "legacy_source", adjustment.legacySource(), false);
                field(out, "reason", adjustment.reason(), true);
                out.append('}');
            }
            case LoanEventPayload.MetadataChangedPayload metadata -> {
                out.append('{');
                appendKey(out, "changes", false);
                appendMetadataChanges(out, metadata.changes());
                field(out, "legacy_source", metadata.legacySource(), true);
                out.append('}');
            }
            case LoanEventPayload.ReversalPayload reversal -> {
                out.append('{');
                field(out, "reason", reversal.reason(), false);
                field(out, "target_event_id", reversal.targetEventId(), true);
                out.append('}');
            }
            case LoanEventPayload.ClosePayload close -> {
                out.append('{');
                field(out, "reason", close.reason(), false);
                out.append('}');
            }
        }
    }

    private static void appendMetadataChanges(StringBuilder out, LoanEventPayload.MetadataChanges changes) {
        out.append('{');
        boolean comma = false;
        if (changes.counterpartyName().present()) {
            field(out, "counterparty_name", changes.counterpartyName().value(), comma);
            comma = true;
        }
        if (changes.defaultAccountId().present()) {
            field(out, "default_account_id", changes.defaultAccountId().value(), comma);
            comma = true;
        }
        if (changes.notes().present()) {
            field(out, "notes", changes.notes().value(), comma);
        }
        out.append('}');
    }

    private static void field(StringBuilder out, String key, Object value, boolean comma) {
        appendKey(out, key, comma);
        if (value == null) {
            out.append("null");
        } else if (value instanceof Number number) {
            appendString(out, Long.toString(number.longValue()));
        } else if (value instanceof String string) {
            appendString(out, string);
        } else {
            throw new IllegalArgumentException("Unsupported canonical JSON value");
        }
    }

    private static void appendKey(StringBuilder out, String key, boolean comma) {
        if (comma) {
            out.append(',');
        }
        appendString(out, key);
        out.append(':');
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("Invalid Unicode surrogate");
                }
                out.append(current).append(value.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Invalid Unicode surrogate");
            }
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (current <= 0x1f) {
                        out.append("\\u00");
                        out.append(Character.forDigit((current >> 4) & 0xf, 16));
                        out.append(Character.forDigit(current & 0xf, 16));
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        out.append('"');
    }
}
