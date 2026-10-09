package chess;

/** The six kinds of piece. Behaviour lives in {@link Rules}, not here (data-driven design). */
public enum PieceType {
    KING('K'), QUEEN('Q'), ROOK('R'), BISHOP('B'), KNIGHT('N'), PAWN('P');

    public final char letter;

    PieceType(char letter) { this.letter = letter; }

    public static PieceType fromLetter(char c) {
        for (PieceType t : values()) if (t.letter == c) return t;
        throw new IllegalArgumentException("not a piece letter: " + c);
    }
}
