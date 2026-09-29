package myfinances.infrastructure.obligation.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import org.junit.jupiter.api.Test;

class ObligationMergePolicyTest {

    @Test
    void acceptsWhenNoLocalExists() {
        assertTrue(ObligationMergePolicy.shouldAcceptRemote(null, obligation("o1", 100L, "a")));
        assertTrue(ObligationMergePolicy.shouldAcceptRemote(null, settlement("s1", 100L, "a")));
    }

    @Test
    void newerRemoteWins() {
        assertTrue(ObligationMergePolicy.shouldAcceptRemote(
            obligation("o1", 100L, "a"), obligation("o1", 200L, "a")));
        assertTrue(ObligationMergePolicy.shouldAcceptRemote(
            settlement("s1", 100L, "a"), settlement("s1", 200L, "a")));
    }

    @Test
    void olderRemoteLoses() {
        assertFalse(ObligationMergePolicy.shouldAcceptRemote(
            obligation("o1", 200L, "a"), obligation("o1", 100L, "a")));
        assertFalse(ObligationMergePolicy.shouldAcceptRemote(
            settlement("s1", 200L, "a"), settlement("s1", 100L, "a")));
    }

    @Test
    void equalTimestampIdenticalContentIsNoop() {
        assertFalse(ObligationMergePolicy.shouldAcceptRemote(
            obligation("o1", 100L, "a"), obligation("o1", 100L, "a")));
        assertFalse(ObligationMergePolicy.shouldAcceptRemote(
            settlement("s1", 100L, "a"), settlement("s1", 100L, "a")));
    }

    @Test
    void equalTimestampTieBreaksByUpdatedBy() {
        ObligationRepository.Obligation localA = obligation("o1", 100L, "aaa");
        ObligationRepository.Obligation remoteZ = obligationWithTitle("o1", 100L, "zzz", "Título Z");
        assertTrue(ObligationMergePolicy.shouldAcceptRemote(localA, remoteZ));
        assertFalse(ObligationMergePolicy.shouldAcceptRemote(remoteZ, localA));
    }

    @Test
    void equalTimestampAndAuthorTieBreaksBySignature() {
        ObligationRepository.Obligation a = obligationWithTitle("o1", 100L, "same", "A");
        ObligationRepository.Obligation b = obligationWithTitle("o1", 100L, "same", "B");
        // La comparación de firma es determinista: exactamente uno gana.
        assertTrue(ObligationMergePolicy.shouldAcceptRemote(a, b)
            != ObligationMergePolicy.shouldAcceptRemote(b, a));
    }

    private static ObligationRepository.Obligation obligation(String id, long updatedAt, String updatedBy) {
        return obligationWithTitle(id, updatedAt, updatedBy, "Título");
    }

    private static ObligationRepository.Obligation obligationWithTitle(String id, long updatedAt, String updatedBy, String title) {
        return new ObligationRepository.Obligation(
            id, "u", ObligationRepository.TYPE_RECEIVABLE, title, "Contraparte",
            null, null, null, "COP", 1_000L, 10L, null, null, 10L, updatedAt, updatedBy);
    }

    private static ObligationSettlementRepository.ObligationSettlement settlement(String id, long updatedAt, String updatedBy) {
        return new ObligationSettlementRepository.ObligationSettlement(
            id, "obl-1", "u", "acc-1", 500L, 10L, "tx-1", null, 10L, updatedAt, updatedBy);
    }
}
