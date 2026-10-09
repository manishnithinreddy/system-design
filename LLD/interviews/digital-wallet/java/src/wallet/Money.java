package wallet;

/**
 * Money is a long count of paise (1 rupee = 100 paise). Never double: 0.1 + 0.2 != 0.3 in binary floating point.
 * This class only converts and formats; all arithmetic stays in whole paise.
 */
final class Money {
    private Money() {}

    static long rupees(long r) { return Math.multiplyExact(r, 100L); }   // throws on overflow instead of wrapping

    /** 123456789 paise -> "₹12,34,567.89" (Indian grouping: last 3 digits, then pairs). */
    static String format(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return sign + "₹" + group(abs / 100) + "." + String.format("%02d", abs % 100);
    }

    private static String group(long rupees) {
        String s = Long.toString(rupees);
        if (s.length() <= 3) return s;
        String rest = s.substring(0, s.length() - 3);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            if (i > 0 && (rest.length() - i) % 2 == 0) b.append(',');
            b.append(rest.charAt(i));
        }
        return b + "," + s.substring(s.length() - 3);
    }
}
