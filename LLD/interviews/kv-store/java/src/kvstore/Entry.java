package kvstore;

/** A stored value plus when it expires (epoch millis; Long.MAX_VALUE = never). Immutable. */
public record Entry(String value, long expiresAtMillis) {
    public static final long NEVER = Long.MAX_VALUE;

    public boolean expiredAt(long nowMillis) {
        return expiresAtMillis != NEVER && nowMillis >= expiresAtMillis;
    }
}
