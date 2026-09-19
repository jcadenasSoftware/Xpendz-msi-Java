package myfinances.domain.loan.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.snapshot.LoanSnapshot;

public final class FakeLoanRepository implements LoanRepository {
    private final Map<String, List<LoanMovement>> journals = new HashMap<>();
    private final Map<String, LoanMovement> operations = new HashMap<>();
    private final Map<String, LoanMovement> events = new HashMap<>();
    private final Map<String, LoanSnapshot> snapshots = new HashMap<>();
    private int appendCount;

    @Override
    public List<LoanMovement> getJournal(String ownerId, String loanId) {
        return List.copyOf(journals.getOrDefault(loanKey(ownerId, loanId), List.of()));
    }

    @Override
    public void appendEvent(LoanMovement event) {
        journals.computeIfAbsent(loanKey(event.ownerId(), event.loanId()), ignored -> new ArrayList<>()).add(event);
        operations.put(operationKey(event.ownerId(), event.operationId()), event);
        events.put(eventKey(event.ownerId(), event.eventId()), event);
        appendCount++;
    }

    @Override
    public Optional<LoanMovement> findByOperationId(String ownerId, String operationId) {
        return Optional.ofNullable(operations.get(operationKey(ownerId, operationId)));
    }

    @Override
    public Optional<LoanMovement> findByEventId(String ownerId, String eventId) {
        return Optional.ofNullable(events.get(eventKey(ownerId, eventId)));
    }

    @Override
    public Optional<LoanSnapshot> loadSnapshot(String ownerId, String loanId) {
        return Optional.ofNullable(snapshots.get(loanKey(ownerId, loanId)));
    }

    @Override
    public void replaceSnapshot(LoanSnapshot snapshot) {
        snapshots.put(loanKey(snapshot.ownerId(), snapshot.loanId()), snapshot);
    }

    public int appendCount() {
        return appendCount;
    }

    private static String loanKey(String ownerId, String loanId) {
        return ownerId + "\u0000" + loanId;
    }

    private static String operationKey(String ownerId, String operationId) {
        return ownerId + "\u0000" + operationId;
    }

    private static String eventKey(String ownerId, String eventId) {
        return ownerId + "\u0000" + eventId;
    }
}
