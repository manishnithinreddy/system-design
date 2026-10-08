package editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Two buffers that are never edited in place, plus a list of "pieces" saying which slices to read in order.
 *
 *   original = "Hello world"          (the file as loaded: read-only)
 *   add      = "big "                 (everything ever typed: append-only)
 *   pieces   = [ORIGINAL 0..6 "Hello "] [ADD 0..4 "big "] [ORIGINAL 6..11 "world"]   -> "Hello big world"
 *
 * Insert = append to `add` and splice one piece into the list. Delete = shrink/split pieces.
 * No text is ever copied or shifted, so the cost depends on the number of pieces, not the file size.
 * This version keeps pieces in an ArrayList (linear search); real editors use a balanced tree
 * so finding the piece for a position is O(log pieces).
 */
public final class PieceTable implements TextBuffer {
    private enum Source { ORIGINAL, ADD }

    private record Piece(Source source, int start, int length) {}

    private final String original;
    private final StringBuilder add = new StringBuilder();
    private List<Piece> pieces = new ArrayList<>();
    private int length;

    public PieceTable(String original) {
        this.original = original;
        if (!original.isEmpty()) pieces.add(new Piece(Source.ORIGINAL, 0, original.length()));
        length = original.length();
    }

    @Override public void insert(int pos, String text) {
        Objects.checkIndex(pos, length + 1);
        if (text.isEmpty()) return;
        int addStart = add.length();
        add.append(text);
        Piece fresh = new Piece(Source.ADD, addStart, text.length());
        length += text.length();

        // find the piece that contains pos (offset = position inside that piece)
        int i = 0, offset = pos;
        while (i < pieces.size() && offset > pieces.get(i).length()) {
            offset -= pieces.get(i).length();
            i++;
        }
        if (i == pieces.size()) {                          // empty document
            pieces.add(fresh);
            return;
        }
        Piece p = pieces.get(i);
        if (offset == p.length()) {
            // typing right after the previous insert: grow that piece instead of adding one per keystroke
            if (p.source() == Source.ADD && p.start() + p.length() == addStart) {
                pieces.set(i, new Piece(Source.ADD, p.start(), p.length() + text.length()));
            } else {
                pieces.add(i + 1, fresh);
            }
        } else if (offset == 0) {
            pieces.add(i, fresh);
        } else {                                            // split p into left + fresh + right
            pieces.set(i, new Piece(p.source(), p.start(), offset));
            pieces.add(i + 1, fresh);
            pieces.add(i + 2, new Piece(p.source(), p.start() + offset, p.length() - offset));
        }
    }

    @Override public void delete(int pos, int len) {
        Objects.checkFromIndexSize(pos, len, length);
        if (len == 0) return;
        int end = pos + len;
        List<Piece> kept = new ArrayList<>(pieces.size() + 1);
        int pieceStart = 0;
        for (Piece p : pieces) {
            int pieceEnd = pieceStart + p.length();
            if (pieceEnd <= pos || pieceStart >= end) {
                kept.add(p);                                // untouched
            } else {
                if (pieceStart < pos)                       // keep the part before the deleted range
                    kept.add(new Piece(p.source(), p.start(), pos - pieceStart));
                if (pieceEnd > end)                         // keep the part after the deleted range
                    kept.add(new Piece(p.source(), p.start() + (end - pieceStart), pieceEnd - end));
            }
            pieceStart = pieceEnd;
        }
        pieces = kept;
        length -= len;
    }

    @Override public char charAt(int index) {
        Objects.checkIndex(index, length);
        for (Piece p : pieces) {
            if (index < p.length()) return source(p).charAt(p.start() + index);
            index -= p.length();
        }
        throw new IllegalStateException("unreachable");
    }

    @Override public int length() { return length; }

    @Override public String text() {
        StringBuilder sb = new StringBuilder(length);
        for (Piece p : pieces) sb.append(source(p), p.start(), p.start() + p.length());
        return sb.toString();
    }

    public int pieceCount() { return pieces.size(); }

    /** The file as it was loaded. Never modified, which is what makes "revert" and crash recovery cheap. */
    public String original() { return original; }

    private CharSequence source(Piece p) { return p.source() == Source.ORIGINAL ? original : add; }
}
