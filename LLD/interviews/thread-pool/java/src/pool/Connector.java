package pool;

/**
 * How the pool makes, checks and destroys one resource. For JDBC this would be DriverManager.getConnection,
 * Connection.isValid(timeout) and Connection.close(). Tests plug in a fake, so no database is needed.
 */
public interface Connector<C> {
    /** Open a new connection (slow: TCP + TLS + auth, often 5-50 ms against a real database). */
    C create() throws Exception;

    /** Cheap liveness check, e.g. JDBC isValid() or "SELECT 1". false = broken, the pool replaces it. */
    boolean isValid(C connection);

    /** Close it for real. Must not throw. */
    void close(C connection);
}
