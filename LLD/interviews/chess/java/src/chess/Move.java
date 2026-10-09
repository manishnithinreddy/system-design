package chess;

/** What a player intends: from, to, optional promotion piece, and a kind that tells make/unmake about special cases. */
public record Move(int from, int to, PieceType promotion, Kind kind) {
    public enum Kind { NORMAL, DOUBLE_PUSH, EN_PASSANT, CASTLE }

    /** Coordinate form such as "e2e4" or "e7e8q". */
    public String uci() {
        return Sq.name(from) + Sq.name(to) + (promotion == null ? "" : String.valueOf(Character.toLowerCase(promotion.letter)));
    }
}
