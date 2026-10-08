package moviebooking;

/** A physical seat in a screen, e.g. "C7". Same for every show in that screen. */
public record Seat(String id, SeatType type) {}
