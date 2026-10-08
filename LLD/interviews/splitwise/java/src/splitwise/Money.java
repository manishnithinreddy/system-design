package splitwise;

import java.math.BigDecimal;

/**
 * Money as a whole number of paise (1 rupee = 100 paise). Integers are exact, fast and simple;
 * there's no fractional paise to round. (The parking lot uses BigDecimal instead: both are valid.)
 */
public final class Money {
    private Money() {}

    /** "420.50" -> 42050. Rejects more than 2 decimal places instead of silently rounding. */
    public static long parse(String rupees) {
        return new BigDecimal(rupees).movePointRight(2).longValueExact();
    }

    /** 42050 -> "₹420.50", -1234 -> "-₹12.34" */
    public static String format(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return String.format("%s₹%d.%02d", sign, abs / 100, abs % 100);
    }
}
