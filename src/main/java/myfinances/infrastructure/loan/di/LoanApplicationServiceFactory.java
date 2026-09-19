package myfinances.infrastructure.loan.di;

import com.myfinaces.db.SqliteDatabase;
import myfinances.application.loan.LoanApplicationService;
import myfinances.domain.loan.projection.LoanProjectionQueryRepository;
import myfinances.domain.loan.reducer.DefaultLoanReducer;
import myfinances.domain.loan.service.DefaultLoanAggregateService;
import myfinances.infrastructure.loan.jdbc.JdbcLoanAggregateExecutor;
import myfinances.infrastructure.loan.jdbc.JdbcLoanRepositoryAdapter;
import myfinances.infrastructure.loan.projection.jdbc.DefaultLoanProjector;
import myfinances.infrastructure.loan.projection.jdbc.JdbcLoanProjectionQueryRepository;

public final class LoanApplicationServiceFactory {
    private final SqliteDatabase database;

    public LoanApplicationServiceFactory(SqliteDatabase database) {
        this.database = database;
    }

    public LoanApplicationService loanApplicationService() {
        JdbcLoanRepositoryAdapter repository = new JdbcLoanRepositoryAdapter(database);
        DefaultLoanAggregateService aggregate = new DefaultLoanAggregateService(
            repository,
            new DefaultLoanReducer()
        );
        JdbcLoanAggregateExecutor executor = new JdbcLoanAggregateExecutor(repository, aggregate);
        DefaultLoanProjector projector = new DefaultLoanProjector(database);
        projector.rebuildAll(repository, new DefaultLoanReducer());
        return new LoanApplicationService(executor, projector);
    }

    public LoanProjectionQueryRepository projectionQueryRepository() {
        return new JdbcLoanProjectionQueryRepository(database);
    }
}
