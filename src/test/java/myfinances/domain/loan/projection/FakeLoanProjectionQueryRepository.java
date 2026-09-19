package myfinances.domain.loan.projection;

import java.util.List;

public final class FakeLoanProjectionQueryRepository implements LoanProjectionQueryRepository {
    private final FakeLoanProjector projector;

    public FakeLoanProjectionQueryRepository(FakeLoanProjector projector) {
        this.projector = projector;
    }

    @Override
    public List<LoanPaymentProjection> getPaymentProjections(String ownerId, String loanId) {
        return projector.getPaymentProjections(ownerId, loanId);
    }

    @Override
    public LoanSummaryProjection getSummaryProjection(String ownerId, String loanId) {
        return projector.getSummaryProjection(ownerId, loanId);
    }

    @Override
    public List<LoanSummaryProjection> listSummaries(String ownerId, LoanSummaryFilter filter) {
        return projector.listSummaries(ownerId, filter);
    }

    @Override
    public List<LoanSummaryProjection> listActiveSummaries(String ownerId, LoanSummaryFilter filter) {
        return projector.listSummaries(ownerId, filter).stream()
            .filter(summary -> summary.pendingCents() > 0)
            .toList();
    }
}
