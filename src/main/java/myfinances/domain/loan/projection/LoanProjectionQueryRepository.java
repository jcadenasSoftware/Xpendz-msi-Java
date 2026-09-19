package myfinances.domain.loan.projection;

import java.util.List;

public interface LoanProjectionQueryRepository {
    List<LoanPaymentProjection> getPaymentProjections(String ownerId, String loanId);

    LoanSummaryProjection getSummaryProjection(String ownerId, String loanId);

    List<LoanSummaryProjection> listSummaries(String ownerId, LoanSummaryFilter filter);

    List<LoanSummaryProjection> listActiveSummaries(String ownerId, LoanSummaryFilter filter);
}
