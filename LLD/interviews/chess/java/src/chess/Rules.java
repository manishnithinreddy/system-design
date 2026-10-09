package chess;

import java.util.ArrayList;
import java.util.List;

/** The rules engine: pseudo-legal generation, attack detection, legality, validation with reasons. Stateless. */
public final class Rules {
    private Rules() { }

    // First four directions are straight (rook), last four diagonal (bishop). Queen uses all eight.
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] KNIGHT = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
    private static final PieceType[] PROMOS = {PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT};

    public record Validation(Move move, String error) {
        public boolean ok() { return move != null; }
    }

    /** True if any piece of colour {@code by} could capture on square {@code s} (ignores pins: a pinned piece still gives check). */
    public static boolean attacked(Position p, int s, Color by) {
        int f = Sq.file(s), r = Sq.rank(s);
        int pawnRank = r - by.pawnDir();               // an attacking pawn stands one rank "behind" the target
        for (int df = -1; df <= 1; df += 2)
            if (Sq.on(f + df, pawnRank) && is(p.at(Sq.of(f + df, pawnRank)), by, PieceType.PAWN)) return true;
        for (int[] o : KNIGHT)
            if (Sq.on(f + o[0], r + o[1]) && is(p.at(Sq.of(f + o[0], r + o[1])), by, PieceType.KNIGHT)) return true;
        for (int[] d : DIRS)
            if (Sq.on(f + d[0], r + d[1]) && is(p.at(Sq.of(f + d[0], r + d[1])), by, PieceType.KING)) return true;
        for (int i = 0; i < 8; i++) {
            int cf = f + DIRS[i][0], cr = r + DIRS[i][1];
            while (Sq.on(cf, cr)) {
                Piece x = p.at(Sq.of(cf, cr));
                if (x != null) {
                    if (x.color() == by && (x.type() == PieceType.QUEEN || x.type() == (i < 4 ? PieceType.ROOK : PieceType.BISHOP))) return true;
                    break;                                  // first piece on the ray blocks everything behind it
                }
                cf += DIRS[i][0]; cr += DIRS[i][1];
            }
        }
        return false;
    }

    private static boolean is(Piece x, Color c, PieceType t) { return x != null && x.color() == c && x.type() == t; }

    public static boolean inCheck(Position p, Color c) {
        int k = p.kingSquare(c);
        return k >= 0 && attacked(p, k, c.opposite());
    }

    /** Moves that follow each piece's movement rules, but may leave the mover's own king in check. */
    public static List<Move> pseudoLegal(Position p) {
        List<Move> out = new ArrayList<>(48);
        Color me = p.turn();
        for (int s = 0; s < 64; s++) {
            Piece pc = p.at(s);
            if (pc == null || pc.color() != me) continue;
            switch (pc.type()) {
                case KNIGHT -> steps(p, s, KNIGHT, out);
                case KING -> { steps(p, s, DIRS, out); castles(p, s, out); }
                case ROOK -> slide(p, s, 0, 4, out);
                case BISHOP -> slide(p, s, 4, 8, out);
                case QUEEN -> slide(p, s, 0, 8, out);
                case PAWN -> pawn(p, s, out);
            }
        }
        return out;
    }

    private static void steps(Position p, int s, int[][] offsets, List<Move> out) {
        for (int[] o : offsets) {
            int f = Sq.file(s) + o[0], r = Sq.rank(s) + o[1];
            if (!Sq.on(f, r)) continue;
            Piece t = p.at(Sq.of(f, r));
            if (t == null || t.color() != p.turn()) out.add(new Move(s, Sq.of(f, r), null, Move.Kind.NORMAL));
        }
    }

    private static void slide(Position p, int s, int from, int to, List<Move> out) {
        for (int i = from; i < to; i++) {
            int f = Sq.file(s) + DIRS[i][0], r = Sq.rank(s) + DIRS[i][1];
            while (Sq.on(f, r)) {
                Piece t = p.at(Sq.of(f, r));
                if (t == null) out.add(new Move(s, Sq.of(f, r), null, Move.Kind.NORMAL));
                else {
                    if (t.color() != p.turn()) out.add(new Move(s, Sq.of(f, r), null, Move.Kind.NORMAL));
                    break;                                  // blocked: own piece stops before, enemy piece is capturable
                }
                f += DIRS[i][0]; r += DIRS[i][1];
            }
        }
    }

    private static void pawn(Position p, int s, List<Move> out) {
        Color me = p.turn();
        int dir = me.pawnDir(), f = Sq.file(s), r = Sq.rank(s);
        int startRank = me == Color.WHITE ? 1 : 6, lastRank = me == Color.WHITE ? 7 : 0;
        int r1 = r + dir;
        if (p.at(Sq.of(f, r1)) == null) {
            addPawn(out, s, Sq.of(f, r1), r1 == lastRank);
            if (r == startRank && p.at(Sq.of(f, r + 2 * dir)) == null)
                out.add(new Move(s, Sq.of(f, r + 2 * dir), null, Move.Kind.DOUBLE_PUSH));
        }
        for (int df = -1; df <= 1; df += 2) {
            if (!Sq.on(f + df, r1)) continue;
            int t = Sq.of(f + df, r1);
            Piece x = p.at(t);
            if (x != null && x.color() != me) addPawn(out, s, t, r1 == lastRank);
            else if (x == null && t == p.ep()) out.add(new Move(s, t, null, Move.Kind.EN_PASSANT));
        }
    }

    private static void addPawn(List<Move> out, int from, int to, boolean promotes) {
        if (promotes) for (PieceType t : PROMOS) out.add(new Move(from, to, t, Move.Kind.NORMAL));
        else out.add(new Move(from, to, null, Move.Kind.NORMAL));
    }

    /** Castling needs: right still held, rook there, squares between empty, king not in check, and not crossing/landing on attacked squares. */
    private static void castles(Position p, int s, List<Move> out) {
        Color me = p.turn(), foe = me.opposite();
        int home = me == Color.WHITE ? 4 : 60;
        int kRight = me == Color.WHITE ? Position.WK : Position.BK, qRight = me == Color.WHITE ? Position.WQ : Position.BQ;
        if (s != home || (p.castling() & (kRight | qRight)) == 0 || attacked(p, home, foe)) return;
        if ((p.castling() & kRight) != 0 && is(p.at(home + 3), me, PieceType.ROOK)
                && p.at(home + 1) == null && p.at(home + 2) == null
                && !attacked(p, home + 1, foe) && !attacked(p, home + 2, foe))
            out.add(new Move(home, home + 2, null, Move.Kind.CASTLE));
        // Queenside: b-file square must be empty but MAY be attacked; the king never crosses it.
        if ((p.castling() & qRight) != 0 && is(p.at(home - 4), me, PieceType.ROOK)
                && p.at(home - 1) == null && p.at(home - 2) == null && p.at(home - 3) == null
                && !attacked(p, home - 1, foe) && !attacked(p, home - 2, foe))
            out.add(new Move(home, home - 2, null, Move.Kind.CASTLE));
    }

    /** Try the move, look at our own king, take it back. */
    public static boolean leavesKingSafe(Position p, Move m) {
        Color me = p.turn();
        Applied a = p.make(m);
        boolean safe = !inCheck(p, me);
        p.unmake(a);
        return safe;
    }

    public static List<Move> legal(Position p) {
        List<Move> out = new ArrayList<>(40);
        for (Move m : pseudoLegal(p)) if (leavesKingSafe(p, m)) out.add(m);
        return out;
    }

    /** Validation pipeline for a UI-style request (from, to, optional promotion), failing with a human reason. */
    public static Validation validate(Position p, int from, int to, PieceType promo) {
        Piece pc = p.at(from);
        if (pc == null) return new Validation(null, "there is no piece on " + Sq.name(from));
        if (pc.color() != p.turn()) return new Validation(null, "that piece belongs to the other player");
        PieceType want = promo;
        List<Move> candidates = new ArrayList<>();
        for (Move m : pseudoLegal(p)) if (m.from() == from && m.to() == to) candidates.add(m);
        if (candidates.isEmpty()) return new Validation(null, pc.type() + " cannot move from " + Sq.name(from) + " to " + Sq.name(to));
        if (want == null && candidates.get(0).promotion() != null) want = PieceType.QUEEN;   // default promotion
        for (Move m : candidates)
            if (m.promotion() == want) {
                if (!leavesKingSafe(p, m)) return new Validation(null, "move would leave your king in check");
                return new Validation(m, null);
            }
        return new Validation(null, "invalid promotion choice");
    }

    public static boolean insufficientMaterial(Position p) {
        int minors = 0;
        for (int s = 0; s < 64; s++) {
            Piece x = p.at(s);
            if (x == null || x.type() == PieceType.KING) continue;
            if (x.type() == PieceType.BISHOP || x.type() == PieceType.KNIGHT) minors++;
            else return false;   // any pawn, rook or queen can still force mate
        }
        return minors <= 1;      // K vs K, or K+minor vs K. (Same-colour bishops etc. are left out for brevity.)
    }
}
