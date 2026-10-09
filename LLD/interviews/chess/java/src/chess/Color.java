package chess;

public enum Color {
    WHITE, BLACK;

    public Color opposite() { return this == WHITE ? BLACK : WHITE; }

    /** Rank direction in which this colour's pawns advance. */
    public int pawnDir() { return this == WHITE ? 1 : -1; }
}
