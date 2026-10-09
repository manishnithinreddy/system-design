package chess;

/** FEN = Forsyth-Edwards Notation: a one-line text snapshot of a position. */
public final class Fen {
    private Fen() { }

    public static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public static Position parse(String fen) {
        String[] t = fen.trim().split("\\s+");
        if (t.length < 4) throw new IllegalArgumentException("FEN needs at least 4 fields: " + fen);
        String[] rows = t[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("FEN needs 8 ranks: " + fen);
        Position p = new Position();
        for (int i = 0; i < 8; i++) {
            int rank = 7 - i, file = 0;
            for (char c : rows[i].toCharArray()) {
                if (Character.isDigit(c)) file += c - '0';
                else p.set(Sq.of(file++, rank), Piece.fromFen(c));
            }
            if (file != 8) throw new IllegalArgumentException("bad rank in FEN: " + rows[i]);
        }
        int castling = 0;
        for (char c : t[2].toCharArray())
            castling |= switch (c) { case 'K' -> Position.WK; case 'Q' -> Position.WQ; case 'k' -> Position.BK; case 'q' -> Position.BQ; default -> 0; };
        p.setState("b".equals(t[1]) ? Color.BLACK : Color.WHITE, castling,
                "-".equals(t[3]) ? -1 : Sq.parse(t[3]),
                t.length > 4 ? Integer.parseInt(t[4]) : 0, t.length > 5 ? Integer.parseInt(t[5]) : 1);
        return p;
    }

    public static String format(Position p) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                Piece x = p.at(Sq.of(file, rank));
                if (x == null) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                sb.append(x.fen());
            }
            if (empty > 0) sb.append(empty);
            if (rank > 0) sb.append('/');
        }
        sb.append(p.turn() == Color.WHITE ? " w " : " b ");
        int c = p.castling();
        if (c == 0) sb.append('-');
        else {
            if ((c & Position.WK) != 0) sb.append('K');
            if ((c & Position.WQ) != 0) sb.append('Q');
            if ((c & Position.BK) != 0) sb.append('k');
            if ((c & Position.BQ) != 0) sb.append('q');
        }
        sb.append(' ').append(p.ep() < 0 ? "-" : Sq.name(p.ep()));
        return sb.append(' ').append(p.halfmove()).append(' ').append(p.fullmove()).toString();
    }
}
