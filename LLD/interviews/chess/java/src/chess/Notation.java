package chess;

import java.util.List;

/** Standard algebraic notation (SAN) subset: e4, Nf3, exd5, Rad1, O-O, e8=Q, with +/# suffixes. Plus coordinate "e2e4". */
public final class Notation {
    private Notation() { }

    public static String toSan(Position p, Move m) {
        StringBuilder sb = new StringBuilder();
        Piece pc = p.at(m.from());
        if (m.kind() == Move.Kind.CASTLE) sb.append(m.to() > m.from() ? "O-O" : "O-O-O");
        else {
            boolean capture = p.at(m.to()) != null || m.kind() == Move.Kind.EN_PASSANT;
            if (pc.type() == PieceType.PAWN) {
                if (capture) sb.append((char) ('a' + Sq.file(m.from())));
            } else {
                sb.append(pc.type().letter).append(disambiguation(p, m, pc));
            }
            if (capture) sb.append('x');
            sb.append(Sq.name(m.to()));
            if (m.promotion() != null) sb.append('=').append(m.promotion().letter);
        }
        Applied a = p.make(m);
        if (Rules.inCheck(p, p.turn())) sb.append(Rules.legal(p).isEmpty() ? '#' : '+');
        p.unmake(a);
        return sb.toString();
    }

    /** Only when two same-type pieces can reach the same square: add file, else rank, else both. */
    private static String disambiguation(Position p, Move m, Piece pc) {
        boolean clash = false, sameFile = false, sameRank = false;
        for (Move o : Rules.legal(p)) {
            if (o.to() != m.to() || o.from() == m.from() || p.at(o.from()).type() != pc.type()) continue;
            clash = true;
            if (Sq.file(o.from()) == Sq.file(m.from())) sameFile = true;
            if (Sq.rank(o.from()) == Sq.rank(m.from())) sameRank = true;
        }
        if (!clash) return "";
        String file = String.valueOf((char) ('a' + Sq.file(m.from())));
        String rank = String.valueOf((char) ('1' + Sq.rank(m.from())));
        if (!sameFile) return file;
        if (!sameRank) return rank;
        return file + rank;
    }

    /** Find the legal move this text means; reject anything else (including ambiguous short forms). */
    public static Move parse(Position p, String text) {
        String s = text.trim().replaceAll("[+#!?]+$", "").replace("0-0-0", "O-O-O").replace("0-0", "O-O");
        List<Move> legal = Rules.legal(p);
        if (s.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            for (Move m : legal) if (m.uci().equals(s)) return m;
            if (s.length() == 4)   // "e7e8" with no piece letter: queen by default
                for (Move m : legal) if (m.uci().equals(s + "q")) return m;
        } else {
            for (Move m : legal) if (toSan(p, m).replaceAll("[+#]+$", "").equals(s)) return m;
        }
        throw new IllegalArgumentException("no legal move matches '" + text + "'");
    }
}
