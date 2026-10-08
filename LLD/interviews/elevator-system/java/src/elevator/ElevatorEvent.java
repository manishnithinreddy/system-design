package elevator;

public record ElevatorEvent(long tick, int elevatorId, Type type, int floor) {
    public enum Type { MOVED, DOORS_OPENED, MAINTENANCE_ON, MAINTENANCE_OFF }
}
