package chess;

import java.util.SplittableRandom;

/**
 * Zobrist hashing: give every (piece, square) a fixed random 64-bit number and XOR together the numbers of
 * everything on the board, plus numbers for side to move, castling rights and en-passant file.
 * Equal positions give equal hashes; different positions collide with probability about 2^-64.
 * Real engines update the hash incrementally (XOR out the old, XOR in the new); here we recompute for clarity.
 */
public final class Zobrist {
    private Zobrist() { }

    private static final long[][] PIECE = new long[12][64];
    private static final long[] CASTLE = new long[16];
    private static final long[] EP_FILE = new long[8];
    private static final long SIDE;

    static {
        SplittableRandom r = new SplittableRandom(20240607L);   // fixed seed: same hashes every run
        for (long[] row : PIECE) for (int i = 0; i < 64; i++) row[i] = r.nextLong();
        for (int i = 0; i < 16; i++) CASTLE[i] = r.nextLong();
        for (int i = 0; i < 8; i++) EP_FILE[i] = r.nextLong();
        SIDE = r.nextLong();
    }

    public static long hash(Position p) {
        long h = 0;
        for (int s = 0; s < 64; s++) {
            Piece x = p.at(s);
            if (x != null) h ^= PIECE[x.color().ordinal() * 6 + x.type().ordinal()][s];
        }
        if (p.turn() == Color.BLACK) h ^= SIDE;
        h ^= CASTLE[p.castling()];
        if (epCapturable(p)) h ^= EP_FILE[Sq.file(p.ep())];
        return h;
    }

    /** The repetition rule counts an en-passant square only if a pawn could really capture there. */
    private static boolean epCapturable(Position p) {
        if (p.ep() < 0) return false;
        int pawnRank = Sq.rank(p.ep()) - p.turn().pawnDir();
        for (int df = -1; df <= 1; df += 2) {
            int f = Sq.file(p.ep()) + df;
            if (Sq.on(f, pawnRank)) {
                Piece x = p.at(Sq.of(f, pawnRank));
                if (x != null && x.color() == p.turn() && x.type() == PieceType.PAWN) return true;
            }
        }
        return false;
    }
}
