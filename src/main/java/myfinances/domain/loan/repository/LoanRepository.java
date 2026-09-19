package myfinances.domain.loan.repository;

import java.util.List;
import java.util.Optional;
import myfinances.domain.loan.journal.LoanMovement;
import myfinances.domain.loan.snapshot.LoanSnapshot;

public interface LoanRepository {
    List<LoanMovement> getJournal(String ownerId, String loanId);

    void appendEvent(LoanMovement event);

    Optional<LoanMovement> findByOperationId(String ownerId, String operationId);

    Optional<LoanMovement> findByEventId(String ownerId, String eventId);

    Optional<LoanSnapshot> loadSnapshot(String ownerId, String loanId);

    void replaceSnapshot(LoanSnapshot snapshot);
}
