package chess;

/**
 * Everything that defines a chess position: pieces, side to move, castling rights, en-passant square and the
 * two move counters. Mutable for speed; make/unmake are exact inverses.
 */
public final class Position {
    public static final int WK = 1, WQ = 2, BK = 4, BQ = 8; // castling-rights bits

    private final Piece[] board = new Piece[64];
    private Color turn = Color.WHITE;
    private int castling;
    private int ep = -1;      // square "behind" a pawn that just double-pushed, or -1
    private int halfmove;     // plies since last capture or pawn move (50-move rule)
    private int fullmove = 1;

    public Piece at(int s) { return board[s]; }
    public Color turn() { return turn; }
    public int castling() { return castling; }
    public int ep() { return ep; }
    public int halfmove() { return halfmove; }
    public int fullmove() { return fullmove; }

    void set(int s, Piece p) { board[s] = p; }
    void setState(Color turn, int castling, int ep, int halfmove, int fullmove) {
        this.turn = turn; this.castling = castling; this.ep = ep; this.halfmove = halfmove; this.fullmove = fullmove;
    }

    public int kingSquare(Color c) {
        for (int s = 0; s < 64; s++) if (board[s] != null && board[s].color() == c && board[s].type() == PieceType.KING) return s;
        return -1;
    }

    /** Moving from or to one of these squares destroys the listed castling rights (king moved, rook moved or captured). */
    private static int rightsLostAt(int s) {
        return switch (s) {
            case 0 -> WQ; case 7 -> WK; case 4 -> WK | WQ;
            case 56 -> BQ; case 63 -> BK; case 60 -> BK | BQ;
            default -> 0;
        };
    }

    public Applied make(Move m) {
        Piece p = board[m.from()];
        Piece captured = board[m.to()];
        if (m.kind() == Move.Kind.EN_PASSANT) {
            int capSq = m.to() - 8 * p.color().pawnDir();   // the victim stands beside us, not on the target square
            captured = board[capSq];
            board[capSq] = null;
        }
        Applied a = new Applied(m, p, captured, castling, ep, halfmove, fullmove);
        board[m.to()] = m.promotion() != null ? new Piece(p.color(), m.promotion()) : p;
        board[m.from()] = null;
        if (m.kind() == Move.Kind.CASTLE) {
            boolean kingside = m.to() > m.from();
            int rookFrom = kingside ? m.to() + 1 : m.to() - 2;
            int rookTo = kingside ? m.to() - 1 : m.to() + 1;
            board[rookTo] = board[rookFrom];
            board[rookFrom] = null;
        }
        castling &= ~rightsLostAt(m.from()) & ~rightsLostAt(m.to());
        ep = m.kind() == Move.Kind.DOUBLE_PUSH ? (m.from() + m.to()) / 2 : -1;
        halfmove = (p.type() == PieceType.PAWN || captured != null) ? 0 : halfmove + 1;
        if (turn == Color.BLACK) fullmove++;
        turn = turn.opposite();
        return a;
    }

    public void unmake(Applied a) {
        Move m = a.move();
        turn = turn.opposite();
        board[m.from()] = a.moved();
        board[m.to()] = null;
        if (m.kind() == Move.Kind.EN_PASSANT) board[m.to() - 8 * turn.pawnDir()] = a.captured();
        else board[m.to()] = a.captured();
        if (m.kind() == Move.Kind.CASTLE) {
            boolean kingside = m.to() > m.from();
            int rookFrom = kingside ? m.to() + 1 : m.to() - 2;
            int rookTo = kingside ? m.to() - 1 : m.to() + 1;
            board[rookFrom] = board[rookTo];
            board[rookTo] = null;
        }
        castling = a.castling(); ep = a.ep(); halfmove = a.halfmove(); fullmove = a.fullmove();
    }
}
