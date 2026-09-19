package myfinances.infrastructure.loan.jdbc;

import myfinances.domain.loan.aggregate.LoanCommandResult;
import myfinances.domain.loan.commands.LoanCommand;
import myfinances.domain.loan.service.LoanAggregateService;

public final class JdbcLoanAggregateExecutor implements LoanAggregateService {
    private final JdbcLoanRepositoryAdapter repository;
    private final LoanAggregateService delegate;

    public JdbcLoanAggregateExecutor(
        JdbcLoanRepositoryAdapter repository,
        LoanAggregateService delegate
    ) {
        this.repository = repository;
        this.delegate = delegate;
    }

    @Override
    public LoanCommandResult process(LoanCommand command) {
        return repository.inTransaction(() -> delegate.process(command));
    }
}
