package moviebooking;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One screening: a movie in a screen at a time, with prices per seat type (paise). */
public record Show(String id, String movie, Instant startsAt, List<Seat> seats, Map<SeatType, Long> pricePaise) {
    public Show {
        seats = List.copyOf(seats);
        pricePaise = Map.copyOf(new EnumMap<>(pricePaise));
        for (Seat s : seats) {
            if (!pricePaise.containsKey(s.type())) throw new IllegalArgumentException("no price for " + s.type());
        }
    }
}
