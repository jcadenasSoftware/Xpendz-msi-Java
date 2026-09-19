package myfinances.infrastructure.loan.jdbc;

public final class LoanPersistenceException extends RuntimeException {
    public LoanPersistenceException(Throwable cause) {
        super(cause);
    }

    public LoanPersistenceException(String message) {
        super(message);
    }
}
