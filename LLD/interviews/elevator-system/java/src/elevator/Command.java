package elevator;

/**
 * Command pattern: every button press becomes a small immutable object that is queued and
 * applied later, on the simulation thread. "sealed" = only these three kinds exist.
 */
public sealed interface Command permits Command.HallCall, Command.CarCall, Command.SetMaintenance {

    /** Someone on a floor pressed UP or DOWN (outside the elevator). */
    record HallCall(int floor, Direction direction) implements Command {
        public HallCall {
            if (direction == Direction.IDLE) throw new IllegalArgumentException("hall call must be UP or DOWN");
        }
    }

    /** Someone inside elevator {@code elevatorId} pressed a floor button. */
    record CarCall(int elevatorId, int floor) implements Command {}

    record SetMaintenance(int elevatorId, boolean on) implements Command {}
}
