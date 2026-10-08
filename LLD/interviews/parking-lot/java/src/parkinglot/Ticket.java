package parkinglot;

import java.time.Instant;

/** Issued at entry. Immutable: a value describing a fact ("this vehicle entered at this time"). */
public record Ticket(String id, Vehicle vehicle, ParkingSpot spot, Instant entryTime) {}
