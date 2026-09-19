package myfinances.domain.loan.journal;

public sealed interface LoanEventPayload permits
    LoanEventPayload.EmptyPayload,
    LoanEventPayload.CreationPayload,
    LoanEventPayload.PaymentPayload,
    LoanEventPayload.AdjustmentPayload,
    LoanEventPayload.MetadataChangedPayload,
    LoanEventPayload.ReversalPayload,
    LoanEventPayload.ClosePayload {

    record EmptyPayload() implements LoanEventPayload {}

    record CreationPayload(
        LoanType loanType,
        String counterpartyName,
        String currency,
        String defaultAccountId,
        String notes
    ) implements LoanEventPayload {}

    record PaymentPayload(
        LegacyPaymentDirection legacyDirection,
        String legacySource
    ) implements LoanEventPayload {}

    record AdjustmentPayload(String reason, String legacySource) implements LoanEventPayload {}

    record MetadataChangedPayload(MetadataChanges changes, String legacySource) implements LoanEventPayload {}

    record ReversalPayload(String targetEventId, String reason) implements LoanEventPayload {}

    record ClosePayload(String reason) implements LoanEventPayload {}

    record MetadataChanges(
        FieldValue<String> counterpartyName,
        FieldValue<String> defaultAccountId,
        FieldValue<String> notes
    ) {}

    record FieldValue<T>(boolean present, T value) {}

    enum LegacyPaymentDirection {
        IN,
        OUT
    }
}
