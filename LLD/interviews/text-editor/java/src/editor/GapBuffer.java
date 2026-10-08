package editor;

import java.util.Objects;

/**
 * One char array with a hole (the "gap") at the cursor, as in Emacs.
 *
 *   "Hello| world"  is stored as  [H e l l o _ _ _ _ _ _ · w o r l d]    (_ = gap, · = space)
 *                                            ^gapStart   ^gapEnd
 *
 * Typing at the gap just fills the next free slot: O(1). Moving the edit point elsewhere first moves the
 * gap there, copying only the characters between the old and new position: O(distance).
 * When the gap is used up, the array doubles (amortised O(1) per character, like ArrayList).
 */
public final class GapBuffer implements TextBuffer {
    private char[] buf;
    private int gapStart;          // first free slot
    private int gapEnd;            // first used slot after the gap (exclusive end of the gap)
    private long charsMoved;       // how many chars gap moves have copied, for the demo

    public GapBuffer(String initial) { this(initial, 64); }

    public GapBuffer(String initial, int initialGap) {
        buf = new char[initial.length() + initialGap];
        initial.getChars(0, initial.length(), buf, 0);
        gapStart = initial.length();                       // the gap starts at the end of the text
        gapEnd = buf.length;
    }

    @Override public void insert(int pos, String text) {
        Objects.checkIndex(pos, length() + 1);
        moveGap(pos);
        ensureGap(text.length());
        text.getChars(0, text.length(), buf, gapStart);   // write into the gap
        gapStart += text.length();
    }

    @Override public void delete(int pos, int len) {
        Objects.checkFromIndexSize(pos, len, length());
        moveGap(pos);
        gapEnd += len;                                      // the deleted chars simply join the gap
    }

    @Override public char charAt(int index) {
        Objects.checkIndex(index, length());
        return index < gapStart ? buf[index] : buf[index + gapLength()];
    }

    @Override public int length() { return buf.length - gapLength(); }

    @Override public String text() {
        return new StringBuilder(length())
                .append(buf, 0, gapStart)
                .append(buf, gapEnd, buf.length - gapEnd)
                .toString();
    }

    public long charsMoved() { return charsMoved; }

    private int gapLength() { return gapEnd - gapStart; }

    /** Slides the gap so it starts at pos. Only the chars between the old and new position are copied. */
    private void moveGap(int pos) {
        if (pos < gapStart) {                               // gap moves left: chars [pos, gapStart) go to the right side
            int n = gapStart - pos;
            System.arraycopy(buf, pos, buf, gapEnd - n, n);
            gapStart = pos;
            gapEnd -= n;
            charsMoved += n;
        } else if (pos > gapStart) {                        // gap moves right: chars after the gap come to the left side
            int n = pos - gapStart;
            System.arraycopy(buf, gapEnd, buf, gapStart, n);
            gapStart = pos;
            gapEnd += n;
            charsMoved += n;
        }
    }

    /** Grows the array when the gap is too small: copy the text before and after the gap into a bigger array. */
    private void ensureGap(int needed) {
        if (gapLength() >= needed) return;
        int newCapacity = Math.max(buf.length * 2, length() + needed + 64);
        char[] bigger = new char[newCapacity];
        System.arraycopy(buf, 0, bigger, 0, gapStart);
        int tail = buf.length - gapEnd;
        System.arraycopy(buf, gapEnd, bigger, newCapacity - tail, tail);
        gapEnd = newCapacity - tail;
        buf = bigger;
    }
}
