package parkinglot;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-vehicle hourly rate, rounded UP to whole hours, with:
 * - a grace period (leave within e.g. 10 minutes: free)
 * - a daily cap (never more than X per 24 hours)
 *
 * Example, CAR at 40/h, cap 300/day, parked 26h 05m:
 *   27 billable hours = 1 full day (300) + 3 hours (min(3 x 40, 300) = 120) = 420
 */
public final class HourlyPricing implements PricingStrategy {
    private final Map<VehicleType, BigDecimal> hourlyRate;
    private final Map<VehicleType, BigDecimal> dailyCap;
    private final Duration gracePeriod;

    public HourlyPricing(Map<VehicleType, BigDecimal> hourlyRate,
                         Map<VehicleType, BigDecimal> dailyCap,
                         Duration gracePeriod) {
        this.hourlyRate = new EnumMap<>(hourlyRate);
        this.dailyCap = new EnumMap<>(dailyCap);
        this.gracePeriod = Objects.requireNonNull(gracePeriod);
        for (VehicleType t : VehicleType.values()) {
            if (!this.hourlyRate.containsKey(t) || !this.dailyCap.containsKey(t)) {
                throw new IllegalArgumentException("missing rate or cap for " + t);
            }
        }
    }

    @Override
    public BigDecimal feeFor(VehicleType type, Duration parkedFor) {
        if (parkedFor.isNegative()) throw new IllegalArgumentException("negative duration");
        if (parkedFor.compareTo(gracePeriod) <= 0) return BigDecimal.ZERO;

        long billableHours = ceilHours(parkedFor);
        long fullDays = billableHours / 24;
        long remainingHours = billableHours % 24;

        BigDecimal rate = hourlyRate.get(type);
        BigDecimal cap = dailyCap.get(type);
        BigDecimal remainder = rate.multiply(BigDecimal.valueOf(remainingHours)).min(cap);
        return cap.multiply(BigDecimal.valueOf(fullDays)).add(remainder);
    }

    /** 0:01 -> 1 hour, 1:00 -> 1 hour, 1:00:01 -> 2 hours. */
    static long ceilHours(Duration d) {
        long hours = d.toHours();
        return d.equals(Duration.ofHours(hours)) ? hours : hours + 1;
    }
}
