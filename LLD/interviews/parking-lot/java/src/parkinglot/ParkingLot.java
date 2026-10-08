package parkinglot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facade: the entry/exit gates only talk to this class: park() and unpark().
 * Thread-safe: many gates call it concurrently.
 */
public final class ParkingLot {
    private final List<ParkingFloor> floors;
    private final SpotAllocationStrategy allocation;
    private final PricingStrategy pricing;
    private final Clock clock;

    private final Map<String, Ticket> activeTicketsById = new ConcurrentHashMap<>();
    private final Map<String, Ticket> activeTicketsByPlate = new ConcurrentHashMap<>();
    // Read on every change, written rarely (boards are registered at startup) -> copy-on-write fits.
    private final List<AvailabilityListener> listeners = new CopyOnWriteArrayList<>();

    public ParkingLot(List<ParkingFloor> floors, SpotAllocationStrategy allocation,
                      PricingStrategy pricing, Clock clock) {
        if (floors.isEmpty()) throw new IllegalArgumentException("at least one floor");
        this.floors = List.copyOf(floors);
        this.allocation = allocation;
        this.pricing = pricing;
        this.clock = clock;
    }

    public void addListener(AvailabilityListener listener) {
        listeners.add(listener);
    }

    public Ticket park(Vehicle vehicle) {
        // Reserve the plate first so the same car can't enter twice (e.g. a cloned number plate).
        Ticket placeholder = new Ticket("pending", vehicle, null, null);
        if (activeTicketsByPlate.putIfAbsent(vehicle.licensePlate(), placeholder) != null) {
            throw new IllegalStateException(vehicle.licensePlate() + " is already parked");
        }

        Optional<ParkingSpot> claimed = allocation.allocate(floors, vehicle.type());
        if (claimed.isEmpty()) {
            activeTicketsByPlate.remove(vehicle.licensePlate(), placeholder);
            throw new ParkingFullException(vehicle.type());
        }
        ParkingSpot spot = claimed.get();
        if (!spot.occupy(vehicle)) {
            // Can't happen if strategies only hand out spots they claimed from a floor; fail loudly if it does.
            throw new IllegalStateException("spot " + spot + " handed out twice");
        }

        Ticket ticket = new Ticket(UUID.randomUUID().toString(), vehicle, spot, clock.instant());
        activeTicketsById.put(ticket.id(), ticket);
        activeTicketsByPlate.put(vehicle.licensePlate(), ticket);
        notifyListeners(spot);
        return ticket;
    }

    public Receipt unpark(String ticketId) {
        // remove() is atomic: if two exit gates scan the same ticket, only one gets it.
        Ticket ticket = activeTicketsById.remove(ticketId);
        if (ticket == null) throw new InvalidTicketException("unknown or already used ticket " + ticketId);

        Instant exit = clock.instant();
        Duration parkedFor = Duration.between(ticket.entryTime(), exit);
        BigDecimal fee = pricing.feeFor(ticket.vehicle().type(), parkedFor);

        ParkingSpot spot = ticket.spot();
        spot.release();
        floorOf(spot).returnSpot(spot);
        activeTicketsByPlate.remove(ticket.vehicle().licensePlate(), ticket);
        notifyListeners(spot);
        return new Receipt(ticket, exit, parkedFor, fee);
    }

    public int freeSpots(SpotSize size) {
        return floors.stream().mapToInt(f -> f.freeCount(size)).sum();
    }

    public int activeTickets() {
        return activeTicketsById.size();
    }

    private ParkingFloor floorOf(ParkingSpot spot) {
        return floors.stream().filter(f -> f.number() == spot.floor()).findFirst().orElseThrow();
    }

    private void notifyListeners(ParkingSpot spot) {
        int free = floorOf(spot).freeCount(spot.size());
        for (AvailabilityListener l : listeners) {
            try {
                l.onAvailabilityChanged(spot.floor(), spot.size(), free);
            } catch (RuntimeException e) {
                // A broken display board must never stop a car from entering or leaving.
                System.err.println("display listener failed: " + e);
            }
        }
    }
}
