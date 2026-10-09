package chess;

/**
 * "Performance test": count every legal move sequence of exactly N plies. The totals for well-known positions
 * are published, so one wrong castling/en-passant/pin rule anywhere changes a number.
 */
public final class Perft {
    private Perft() { }

    public static long count(Position p, int depth) {
        var moves = Rules.legal(p);
        if (depth == 1) return moves.size();   // bulk counting: leaves need no make/unmake
        long n = 0;
        for (Move m : moves) {
            Applied a = p.make(m);
            n += count(p, depth - 1);
            p.unmake(a);
        }
        return n;
    }
}
