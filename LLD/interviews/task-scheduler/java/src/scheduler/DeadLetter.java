package scheduler;

/** A run that failed every allowed attempt. Kept for a human (or an alert) to look at. */
public record DeadLetter(String taskName, int attempts, String lastError, long failedAtMillis) {}
