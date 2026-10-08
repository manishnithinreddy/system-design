package splitwise;

/** A suggested payment: {@code from} should pay {@code to} this much. */
public record Transfer(String from, String to, long amountPaise) {
    @Override
    public String toString() {
        return from + " pays " + to + " " + Money.format(amountPaise);
    }
}
