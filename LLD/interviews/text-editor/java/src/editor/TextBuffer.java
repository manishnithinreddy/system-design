package editor;

import java.util.Objects;

/**
 * Where the characters live. The editor only talks to this interface, so the storage can be swapped
 * (StringBuilder, gap buffer, piece table) without touching any command or undo code.
 * Positions are 0-based character offsets; position == length() means "at the end".
 */
public interface TextBuffer {

    /** Inserts text so that its first character ends up at position pos (0 <= pos <= length()). */
    void insert(int pos, String text);

    /** Removes len characters starting at pos. */
    void delete(int pos, int len);

    char charAt(int index);

    int length();

    /** The whole document as one String (O(n): used for saving, searching and tests). */
    String text();

    /** A short name for printing ("GapBuffer"). */
    default String name() { return getClass().getSimpleName(); }

    /** Characters [start, end). Implementations may override with something faster. */
    default String substring(int start, int end) {
        Objects.checkFromToIndex(start, end, length());
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) sb.append(charAt(i));
        return sb.toString();
    }
}
