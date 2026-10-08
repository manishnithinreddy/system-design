package kvstore;

public final class NoTransactionException extends RuntimeException {
    public NoTransactionException() { super("NO TRANSACTION"); }
}
