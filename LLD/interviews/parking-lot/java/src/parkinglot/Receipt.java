package parkinglot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record Receipt(Ticket ticket, Instant exitTime, Duration parkedFor, BigDecimal fee) {}
