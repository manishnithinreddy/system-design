package editor;

import java.util.Objects;

/**
 * The obvious first buffer: one contiguous array. Inserting in the middle shifts every character after
 * the insert point by one slot, so a keystroke costs O(n - pos). Fine for small documents.
 */
public final class StringBuilderBuffer implements TextBuffer {
    private final StringBuilder sb;

    public StringBuilderBuffer(String initial) { this.sb = new StringBuilder(initial); }

    @Override public void insert(int pos, String text) {
        Objects.checkIndex(pos, sb.length() + 1);
        sb.insert(pos, text);                                // shifts the tail: O(n - pos)
    }

    @Override public void delete(int pos, int len) {
        Objects.checkFromIndexSize(pos, len, sb.length());
        sb.delete(pos, pos + len);                           // shifts the tail back
    }

    @Override public char charAt(int index) { return sb.charAt(index); }
    @Override public int length() { return sb.length(); }
    @Override public String text() { return sb.toString(); }
    @Override public String substring(int start, int end) { return sb.substring(start, end); }
}
