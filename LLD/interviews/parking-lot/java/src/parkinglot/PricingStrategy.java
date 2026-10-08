package parkinglot;

import java.math.BigDecimal;
import java.time.Duration;

/** Strategy: how much to charge. Money is BigDecimal, never double. */
public interface PricingStrategy {
    BigDecimal feeFor(VehicleType type, Duration parkedFor);
}
