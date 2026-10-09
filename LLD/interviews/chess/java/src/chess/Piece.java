package chess;

/** An immutable (colour, type) pair. Whether it has moved is NOT stored here: castling rights live in Position. */
public record Piece(Color color, PieceType type) {
    /** FEN letter: upper case for white, lower case for black. */
    public char fen() {
        return color == Color.WHITE ? type.letter : Character.toLowerCase(type.letter);
    }

    public static Piece fromFen(char c) {
        Color col = Character.isUpperCase(c) ? Color.WHITE : Color.BLACK;
        return new Piece(col, PieceType.fromLetter(Character.toUpperCase(c)));
    }
}
