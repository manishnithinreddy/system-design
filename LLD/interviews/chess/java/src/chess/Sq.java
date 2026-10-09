package chess;

/** Squares are ints 0..63: index = rank * 8 + file, so a1 = 0, h1 = 7, a2 = 8, h8 = 63. */
public final class Sq {
    private Sq() { }

    public static int of(int file, int rank) { return rank * 8 + file; }
    public static int file(int s) { return s & 7; }
    public static int rank(int s) { return s >> 3; }
    public static boolean on(int file, int rank) { return file >= 0 && file < 8 && rank >= 0 && rank < 8; }

    public static String name(int s) { return "" + (char) ('a' + file(s)) + (char) ('1' + rank(s)); }

    public static int parse(String n) {
        if (n.length() != 2 || !on(n.charAt(0) - 'a', n.charAt(1) - '1'))
            throw new IllegalArgumentException("bad square: " + n);
        return of(n.charAt(0) - 'a', n.charAt(1) - '1');
    }
}
