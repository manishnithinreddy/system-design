package vending;

/**
 * Indian coins and notes the machine accepts. Values are in paise (₹1 = 100 paise) so every money
 * calculation is exact integer maths: no double, no rounding surprises.
 * Only coins can be paid out as change: notes go into a one-way stacker.
 */
public enum Denomination {
    COIN_1(100, true), COIN_2(200, true), COIN_5(500, true), COIN_10(1_000, true), COIN_20(2_000, true),
    NOTE_10(1_000, false), NOTE_20(2_000, false), NOTE_50(5_000, false), NOTE_100(10_000, false);

    private final long paise;
    private final boolean coin;

    Denomination(long paise, boolean coin) {
        this.paise = paise;
        this.coin = coin;
    }

    public long paise() { return paise; }

    public boolean isCoin() { return coin; }

    /** "₹10 coin", "₹100 note". */
    public String label() { return rupees(paise) + (coin ? " coin" : " note"); }

    /** 1500 -> "₹15", 1450 -> "₹14.50". */
    public static String rupees(long paise) {
        return paise % 100 == 0 ? "₹" + paise / 100 : String.format("₹%d.%02d", paise / 100, paise % 100);
    }
}
