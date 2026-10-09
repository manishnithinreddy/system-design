package chess;

/**
 * A move that has been executed, plus everything needed to reverse it exactly (a memento).
 * Captured piece and the old castling/en-passant/clock fields cannot be recomputed from the board afterwards.
 */
public record Applied(Move move, Piece moved, Piece captured, int castling, int ep, int halfmove, int fullmove) { }
