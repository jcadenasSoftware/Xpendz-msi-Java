package myfinances.infrastructure.obligation.sync;

import java.util.Objects;
import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;

public final class ObligationMergePolicy {

    private ObligationMergePolicy() {}

    public static boolean shouldAcceptRemote(
        ObligationRepository.Obligation local,
        ObligationRepository.Obligation remote
    ) {
        if (local == null) {
            return true;
        }
        return shouldAcceptRemote(
            local.updatedAtEpochSec(),
            remote.updatedAtEpochSec(),
            local.updatedBy(),
            remote.updatedBy(),
            obligationSignature(local),
            obligationSignature(remote)
        );
    }

    public static boolean shouldAcceptRemote(
        ObligationSettlementRepository.ObligationSettlement local,
        ObligationSettlementRepository.ObligationSettlement remote
    ) {
        if (local == null) {
            return true;
        }
        return shouldAcceptRemote(
            local.updatedAtEpochSec(),
            remote.updatedAtEpochSec(),
            local.updatedBy(),
            remote.updatedBy(),
            settlementSignature(local),
            settlementSignature(remote)
        );
    }

    private static boolean shouldAcceptRemote(
        long localUpdatedAt,
        long remoteUpdatedAt,
        String localUpdatedBy,
        String remoteUpdatedBy,
        String localSignature,
        String remoteSignature
    ) {
        if (remoteUpdatedAt > localUpdatedAt) {
            return true;
        }
        if (remoteUpdatedAt < localUpdatedAt) {
            return false;
        }
        if (Objects.equals(localSignature, remoteSignature)) {
            return false;
        }

        int authorCmp = normalize(remoteUpdatedBy).compareTo(normalize(localUpdatedBy));
        if (authorCmp != 0) {
            return authorCmp > 0;
        }
        return remoteSignature.compareTo(localSignature) > 0;
    }

    private static String obligationSignature(ObligationRepository.Obligation obligation) {
        return String.join("|",
            normalize(obligation.type()),
            normalize(obligation.title()),
            normalize(obligation.counterpartyName()),
            normalize(obligation.notes()),
            normalize(obligation.reference()),
            normalize(obligation.obligationCategoryId()),
            normalize(obligation.currency()),
            String.valueOf(obligation.originalAmountCents()),
            String.valueOf(obligation.issuedAtEpochSec()),
            String.valueOf(obligation.dueAtEpochSec() == null ? -1L : obligation.dueAtEpochSec()),
            String.valueOf(obligation.cancelledAtEpochSec() == null ? -1L : obligation.cancelledAtEpochSec()),
            String.valueOf(obligation.createdAtEpochSec()),
            normalize(obligation.userUid()),
            normalize(obligation.id())
        );
    }

    private static String settlementSignature(ObligationSettlementRepository.ObligationSettlement settlement) {
        return String.join("|",
            normalize(settlement.obligationId()),
            normalize(settlement.accountId()),
            String.valueOf(settlement.amountCents()),
            String.valueOf(settlement.occurredAtEpochSec()),
            normalize(settlement.linkedTransactionId()),
            normalize(settlement.note()),
            String.valueOf(settlement.createdAtEpochSec()),
            normalize(settlement.userUid()),
            normalize(settlement.id())
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
