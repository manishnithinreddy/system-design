package vending;

/** Where the machine gets "now" from. Injected so tests move time by hand instead of sleeping 60 s. */
@FunctionalInterface
public interface TimeSource {
    long nowMillis();

    static TimeSource system() { return System::currentTimeMillis; }
}
