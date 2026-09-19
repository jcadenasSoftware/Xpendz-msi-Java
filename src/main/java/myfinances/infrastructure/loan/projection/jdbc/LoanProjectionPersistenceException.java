package myfinances.infrastructure.loan.projection.jdbc;

public final class LoanProjectionPersistenceException extends RuntimeException {
    public LoanProjectionPersistenceException(Throwable cause) {
        super(cause);
    }

    public LoanProjectionPersistenceException(String message) {
        super(message);
    }
}
