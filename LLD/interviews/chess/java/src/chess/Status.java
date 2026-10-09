package chess;

public enum Status {
    ONGOING, CHECKMATE, STALEMATE, DRAW_FIFTY_MOVE, DRAW_THREEFOLD, DRAW_INSUFFICIENT;

    public boolean isOver() { return this != ONGOING; }
}
