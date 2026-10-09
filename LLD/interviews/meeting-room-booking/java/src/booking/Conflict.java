package booking;

/** One requested slot that could not be booked, and the existing booking in the way. */
public record Conflict(TimeSlot requested, Booking existing) {}
