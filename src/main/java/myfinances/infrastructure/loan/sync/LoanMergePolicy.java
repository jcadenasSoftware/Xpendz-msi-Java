package myfinances.infrastructure.loan.sync;

import java.util.Objects;
import com.myfinaces.db.LoanRepository;
import myfinances.domain.loan.admin.LoanAdminState;

public final class LoanMergePolicy {

    private LoanMergePolicy() {}

    public static boolean shouldAcceptRemote(LoanRepository.Loan local, LoanRepository.Loan remote) {
        if (local == null) {
            return true;
        }
        return shouldAcceptRemote(
            local.updatedAtEpochSec(),
            remote.updatedAtEpochSec(),
            local.updatedBy(),
            remote.updatedBy(),
            loanSignature(local),
            loanSignature(remote)
        );
    }

    public static boolean shouldAcceptRemote(LoanAdminState local, LoanAdminState remote) {
        if (local == null) {
            return true;
        }
        return shouldAcceptRemote(
            local.updatedAtEpochSec(),
            remote.updatedAtEpochSec(),
            local.updatedBy(),
            remote.updatedBy(),
            adminSignature(local),
            adminSignature(remote)
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

    private static String loanSignature(LoanRepository.Loan loan) {
        return String.join("|",
            normalize(loan.type()),
            normalize(loan.counterpartyName()),
            normalize(loan.accountId()),
            String.valueOf(loan.principalCents()),
            normalize(loan.currency()),
            normalize(loan.status()),
            normalize(loan.notes()),
            String.valueOf(loan.createdAtEpochSec()),
            normalize(loan.userUid()),
            normalize(loan.id())
        );
    }

    private static String adminSignature(LoanAdminState state) {
        return String.join("|",
            String.valueOf(state.archived()),
            String.valueOf(state.archivedAtEpochSec() == null ? -1L : state.archivedAtEpochSec()),
            normalize(state.ownerId()),
            normalize(state.loanId())
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
