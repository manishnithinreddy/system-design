package scheduler;

/** The work itself. May throw: a failure triggers the task's retry policy. */
@FunctionalInterface
public interface Task {
    void run() throws Exception;
}
