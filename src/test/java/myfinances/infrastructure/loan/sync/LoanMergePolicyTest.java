package myfinances.infrastructure.loan.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.LoanRepository;
import myfinances.domain.loan.admin.LoanAdminState;
import org.junit.jupiter.api.Test;

class LoanMergePolicyTest {

    private static LoanRepository.Loan loan(
        long updatedAt,
        String updatedBy,
        long principal,
        String counterparty,
        String status,
        String notes,
        String accountId,
        long createdAt,
        String currency
    ) {
        return new LoanRepository.Loan(
            "loan-1", "owner-1", "LENT", counterparty, accountId, principal,
            currency, status, notes, 1_000L, createdAt, updatedAt, updatedBy,
            false, null
        );
    }

    private static LoanAdminState admin(
        long updatedAt,
        String updatedBy,
        boolean archived,
        Long archivedAt
    ) {
        return new LoanAdminState("loan-1", "owner-1", archived, archivedAt, updatedAt, updatedBy);
    }

    @Test
    void remoteNewerWins() {
        assertTrue(LoanMergePolicy.shouldAcceptRemote(loan(1_000L, "a", 100_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP"),
            loan(1_001L, "b", 120_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP")));
        assertTrue(LoanMergePolicy.shouldAcceptRemote(admin(1_000L, "a", false, null),
            admin(1_001L, "b", true, 1_001L)));
    }

    @Test
    void remoteOlderLoses() {
        assertFalse(LoanMergePolicy.shouldAcceptRemote(loan(1_001L, "b", 100_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP"),
            loan(1_000L, "a", 120_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP")));
        assertFalse(LoanMergePolicy.shouldAcceptRemote(admin(1_001L, "b", true, 1_001L),
            admin(1_000L, "a", false, null)));
    }

    @Test
    void equalTimestampSameContentIsNoOp() {
        assertFalse(LoanMergePolicy.shouldAcceptRemote(loan(1_000L, "device-a", 100_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP"),
            loan(1_000L, "device-b", 100_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP")));
        assertFalse(LoanMergePolicy.shouldAcceptRemote(admin(1_000L, "device-a", false, null),
            admin(1_000L, "device-b", false, null)));
    }

    @Test
    void equalTimestampDifferentContentUsesUpdatedByTieBreak() {
        assertTrue(LoanMergePolicy.shouldAcceptRemote(loan(1_000L, "a", 80_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP"),
            loan(1_000L, "b", 120_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP")));
        assertFalse(LoanMergePolicy.shouldAcceptRemote(loan(1_000L, "b", 120_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP"),
            loan(1_000L, "a", 80_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP")));

        assertTrue(LoanMergePolicy.shouldAcceptRemote(admin(1_000L, "a", false, null), admin(1_000L, "b", true, 1_000L)));
        assertFalse(LoanMergePolicy.shouldAcceptRemote(admin(1_000L, "b", true, 1_000L), admin(1_000L, "a", false, null)));
    }

    @Test
    void equalTimestampSameUpdatedByFallsBackToSignatureAndIsIdempotent() {
        LoanRepository.Loan local = loan(1_000L, "device-x", 80_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP");
        LoanRepository.Loan remote = loan(1_000L, "device-x", 120_000L, "Ana", "OPEN", null, "acc-1", 1_000L, "COP");
        boolean first = LoanMergePolicy.shouldAcceptRemote(local, remote);
        LoanRepository.Loan winner = first ? remote : local;
        assertFalse(LoanMergePolicy.shouldAcceptRemote(winner, winner));

        LoanAdminState localAdmin = admin(1_000L, "device-x", false, null);
        LoanAdminState remoteAdmin = admin(1_000L, "device-x", true, 1_000L);
        boolean firstAdmin = LoanMergePolicy.shouldAcceptRemote(localAdmin, remoteAdmin);
        LoanAdminState adminWinner = firstAdmin ? remoteAdmin : localAdmin;
        assertFalse(LoanMergePolicy.shouldAcceptRemote(adminWinner, adminWinner));
    }
}
