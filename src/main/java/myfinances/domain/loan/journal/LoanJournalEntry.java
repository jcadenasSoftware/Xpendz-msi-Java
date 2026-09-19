package myfinances.domain.loan.journal;

import java.util.Map;

public record LoanJournalEntry(
    String eventId,
    String operationId,
    String loanId,
    String ownerId,
    String rawEventType,
    int eventSchemaVersion,
    Long amountCents,
    String accountId,
    String transactionId,
    String note,
    long occurredAt,
    long recordedAt,
    String actorId,
    String originId,
    Map<String, Object> rawPayload
) {}
