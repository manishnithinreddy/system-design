package chess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One game: two players, a position, undo/redo history and the draw bookkeeping. */
public final class Game {
    private final Player white, black;
    private final Position pos;
    private final List<Applied> history = new ArrayList<>();
    private final List<String> sans = new ArrayList<>();
    private final List<Move> redoStack = new ArrayList<>();
    private final Map<Long, Integer> seen = new HashMap<>();   // Zobrist hash -> times this position has occurred
    private final Color startColor;
    private final int startFullmove;

    public Game(Player white, Player black) { this(white, black, Fen.START); }

    public Game(Player white, Player black, String fen) {
        this.white = white; this.black = black;
        this.pos = Fen.parse(fen);
        this.startColor = pos.turn();
        this.startFullmove = pos.fullmove();
        seen.put(Zobrist.hash(pos), 1);
    }

    public Position position() { return pos; }
    public String fen() { return Fen.format(pos); }
    public long hash() { return Zobrist.hash(pos); }
    public Player toMove() { return pos.turn() == Color.WHITE ? white : black; }
    public List<Move> legalMoves() { return Rules.legal(pos); }
    public boolean inCheck() { return Rules.inCheck(pos, pos.turn()); }

    public Status status() {
        if (Rules.legal(pos).isEmpty()) return inCheck() ? Status.CHECKMATE : Status.STALEMATE;
        if (Rules.insufficientMaterial(pos)) return Status.DRAW_INSUFFICIENT;
        if (seen.getOrDefault(hash(), 0) >= 3) return Status.DRAW_THREEFOLD;
        if (pos.halfmove() >= 100) return Status.DRAW_FIFTY_MOVE;   // 100 plies = 50 moves each
        return Status.ONGOING;
    }

    /** UI-style move. Returns the validation result; the board only changes when it is ok. */
    public Rules.Validation move(int from, int to, PieceType promo) {
        requireOngoing();
        Rules.Validation v = Rules.validate(pos, from, to, promo);
        if (v.ok()) { apply(v.move()); redoStack.clear(); }
        return v;
    }

    /** Text move in SAN or coordinate form. Throws IllegalArgumentException if not legal. */
    public Move play(String text) {
        requireOngoing();
        Move m = Notation.parse(pos, text);
        apply(m);
        redoStack.clear();
        return m;
    }

    private void requireOngoing() {
        Status s = status();
        if (s.isOver()) throw new IllegalStateException("game is over: " + s);
    }

    private void apply(Move m) {
        sans.add(Notation.toSan(pos, m));      // SAN is computed BEFORE the move, from the old position
        history.add(pos.make(m));
        seen.merge(hash(), 1, Integer::sum);
    }

    public boolean undo() {
        if (history.isEmpty()) return false;
        seen.merge(hash(), -1, Integer::sum);
        Applied a = history.remove(history.size() - 1);
        sans.remove(sans.size() - 1);
        pos.unmake(a);
        redoStack.add(a.move());
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        apply(redoStack.remove(redoStack.size() - 1));
        return true;
    }

    /** Movetext as in a PGN file: "1. e4 e5 2. Nf3". */
    public String movetext() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sans.size(); i++) {
            boolean whiteMove = ((startColor == Color.WHITE ? 0 : 1) + i) % 2 == 0;
            int number = startFullmove + ((startColor == Color.WHITE ? 0 : 1) + i) / 2;
            if (whiteMove) sb.append(number).append(". ");
            else if (i == 0) sb.append(number).append("... ");
            sb.append(sans.get(i)).append(' ');
        }
        return sb.toString().trim();
    }
}
