package pool;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What borrow() hands out: a thin wrapper whose close() RETURNS the connection to the pool instead of
 * closing it. Works with try-with-resources, so the "return" can't be forgotten on an exception path:
 *
 *   try (PooledConnection<Db> pc = pool.borrow(Duration.ofSeconds(1))) { pc.get().query(...); }
 *
 * HikariCP does the same with a proxy around java.sql.Connection.
 */
public final class PooledConnection<C> implements AutoCloseable {
    private final ConnectionPool<C> pool;
    private final ConnectionPool.Entry<C> entry;
    private final AtomicBoolean returned = new AtomicBoolean();

    PooledConnection(ConnectionPool<C> pool, ConnectionPool.Entry<C> entry) {
        this.pool = pool;
        this.entry = entry;
    }

    /** The real connection. Throws after close(): using a connection someone else now holds is a nasty bug. */
    public C get() {
        if (returned.get()) throw new IllegalStateException("connection already returned to the pool");
        return entry.connection;
    }

    public int id() { return entry.id; }

    /** Return to the pool. A second close() does nothing (compareAndSet lets exactly one caller through). */
    @Override public void close() {
        if (returned.compareAndSet(false, true)) pool.release(entry);
    }
}
