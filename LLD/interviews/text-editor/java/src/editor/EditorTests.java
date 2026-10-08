package editor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Plain-Java tests (no JUnit) so the code runs with just javac + java.
 * Buffer tests loop over all three implementations: one contract, three data structures.
 * Time comes from a ManualClock, so "typed 2 seconds later" is exact and nothing sleeps.
 */
public final class EditorTests {
    private static int passed = 0;
    static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    /** Every buffer implementation, as a factory "initial text -> buffer". */
    static final List<Function<String, TextBuffer>> BUFFERS = List.of(
            StringBuilderBuffer::new,
            s -> new GapBuffer(s, 4),            // tiny gap: forces growth and many gap moves
            PieceTable::new);

    public static void main(String[] args) {
        // buffers
        bufferContractInsertDeleteCharAt();
        bufferContractEdgeCasesAndBounds();
        bufferContractJumpingAroundTheDocument();
        pieceTableNeverTouchesOriginalAndMergesTyping();
        // editor
        undoRedoBasics();
        redoIsClearedByANewEdit();
        typingAWordIsOneUndoStep();
        eachWordIsItsOwnUndoStep();
        coalescingBreaksAfterATimeGap();
        coalescingBreaksOnCursorJump();
        backspaceAndDeleteAreUndoneWithCursor();
        cutCopyPasteWithSelection();
        typingOverASelectionIsOneStep();
        replaceAllIsOneUndoStep();
        boundedHistoryDropsOldest();
        cursorRestoredOnUndoAndRedo();
        // properties
        randomEditsAllBuffersAgreeWithStringBuilder();
        randomEditorSessionUndoesBackToOriginal();
        System.out.println("All " + passed + " tests passed.");
    }

    // =============================================================== buffers

    static void bufferContractInsertDeleteCharAt() {
        for (var make : BUFFERS) {
            TextBuffer b = make.apply("Hello world");
            String n = b.name() + ": ";
            b.insert(5, ",");
            assertEquals("Hello, world", b.text(), n + "insert in the middle");
            b.insert(0, ">> ");
            b.insert(b.length(), "!");
            assertEquals(">> Hello, world!", b.text(), n + "insert at start and end");
            assertEquals('H', b.charAt(3), n + "charAt");
            assertEquals(16, b.length(), n + "length");
            b.delete(0, 3);
            b.delete(5, 1);
            assertEquals("Hello world!", b.text(), n + "delete at start and middle");
            assertEquals("lo wo", b.substring(3, 8), n + "substring");
        }
        pass("bufferContractInsertDeleteCharAt");
    }

    static void bufferContractEdgeCasesAndBounds() {
        for (var make : BUFFERS) {
            TextBuffer b = make.apply("");
            String n = b.name() + ": ";
            assertEquals(0, b.length(), n + "empty");
            b.insert(0, "");
            b.insert(0, "abc");
            assertEquals("abc", b.text(), n + "insert into empty document");
            b.delete(1, 0);
            assertEquals("abc", b.text(), n + "delete 0 chars is a no-op");
            b.delete(0, 3);
            assertEquals("", b.text(), n + "delete everything");
            assertThrows(IndexOutOfBoundsException.class, () -> b.insert(1, "x"), n + "insert past the end");
            assertThrows(IndexOutOfBoundsException.class, () -> b.delete(0, 1), n + "delete past the end");
            assertThrows(IndexOutOfBoundsException.class, () -> b.charAt(0), n + "charAt on empty");
        }
        pass("bufferContractEdgeCasesAndBounds");
    }

    static void bufferContractJumpingAroundTheDocument() {
        for (var make : BUFFERS) {
            TextBuffer b = make.apply("0123456789");
            StringBuilder expected = new StringBuilder("0123456789");
            int[] positions = {10, 0, 5, 2, 9, 1, 13, 7};       // forces the gap to move left and right
            for (int p : positions) {
                b.insert(p, "ab");
                expected.insert(p, "ab");
                b.delete(p / 2, 1);
                expected.delete(p / 2, p / 2 + 1);
                assertEquals(expected.toString(), b.text(), b.name() + ": after edit at " + p);
            }
        }
        pass("bufferContractJumpingAroundTheDocument");
    }

    static void pieceTableNeverTouchesOriginalAndMergesTyping() {
        PieceTable t = new PieceTable("Hello world");
        for (char c : "big ".toCharArray()) t.insert(6 + t.length() - 11, String.valueOf(c));   // type at 6,7,8,9
        assertEquals("Hello big world", t.text(), "typed in the middle");
        assertEquals(3, t.pieceCount(), "4 keystrokes in a row extend ONE add piece: [Hello ][big ][world]");
        t.delete(0, 6);
        assertEquals("big world", t.text(), "delete");
        assertEquals("Hello world", t.original(), "the original buffer is never modified");
        pass("pieceTableNeverTouchesOriginalAndMergesTyping");
    }

    // =============================================================== editor

    static void undoRedoBasics() {
        Editor e = editor("");
        e.type("Hello");
        e.moveTo(5);
        e.type(" world");
        assertEquals("Hello world", e.text(), "typed");
        assertTrue(e.undo(), "undo returns true");
        assertEquals("Hello", e.text(), "undo removes the last edit");
        assertTrue(e.undo(), "second undo");
        assertEquals("", e.text(), "back to empty");
        assertTrue(!e.undo(), "undo with an empty history is a no-op returning false");
        assertTrue(e.redo() && e.redo(), "redo twice");
        assertEquals("Hello world", e.text(), "redo re-applies both");
        assertTrue(!e.redo(), "nothing left to redo");
        pass("undoRedoBasics");
    }

    static void redoIsClearedByANewEdit() {
        Editor e = editor("");
        e.type("abc");
        e.moveTo(3);
        e.type("def");
        e.undo();
        assertEquals(1, e.redoSize(), "one step can be redone");
        e.type("X");
        assertEquals(0, e.redoSize(), "a new edit clears the redo stack");
        assertTrue(!e.redo(), "redo does nothing now");
        assertEquals("abcX", e.text(), "text unchanged by the failed redo");
        pass("redoIsClearedByANewEdit");
    }

    static void typingAWordIsOneUndoStep() {
        var clock = new ManualClock(T0);
        Editor e = new Editor(new GapBuffer(""), clock);
        typeKeys(e, clock, "hello", 100);
        assertEquals(1, e.undoSize(), "5 keystrokes 100 ms apart = 1 undo step");
        e.undo();
        assertEquals("", e.text(), "one Ctrl+Z removes the whole word");
        pass("typingAWordIsOneUndoStep");
    }

    static void eachWordIsItsOwnUndoStep() {
        var clock = new ManualClock(T0);
        Editor e = new Editor(new GapBuffer(""), clock);
        typeKeys(e, clock, "hello world", 100);
        assertEquals(List.of("Typing \"world\"", "Typing \"hello \""), e.undoHistory(), "split at the word boundary");
        e.undo();
        assertEquals("hello ", e.text(), "first undo removes 'world'");
        pass("eachWordIsItsOwnUndoStep");
    }

    static void coalescingBreaksAfterATimeGap() {
        var clock = new ManualClock(T0);
        Editor e = new Editor(new GapBuffer(""), clock);
        typeKeys(e, clock, "abc", 100);
        clock.advance(Duration.ofSeconds(2));                 // the user paused to think
        typeKeys(e, clock, "def", 100);
        assertEquals(2, e.undoSize(), "a pause longer than the 1 s window starts a new step");
        e.undo();
        assertEquals("abc", e.text(), "only the part after the pause is undone");
        pass("coalescingBreaksAfterATimeGap");
    }

    static void coalescingBreaksOnCursorJump() {
        var clock = new ManualClock(T0);
        Editor e = new Editor(new GapBuffer("0123456789"), clock);
        e.moveTo(10);
        typeKeys(e, clock, "ab", 10);
        e.moveTo(0);                                          // click somewhere else
        typeKeys(e, clock, "cd", 10);
        assertEquals("cd0123456789ab", e.text(), "typed in two places");
        assertEquals(2, e.undoSize(), "a cursor jump starts a new undo step");
        // and even moving back to the exact end of the last insert still breaks the group
        e.moveTo(2);
        typeKeys(e, clock, "e", 10);
        assertEquals(3, e.undoSize(), "any explicit cursor move breaks the group");
        pass("coalescingBreaksOnCursorJump");
    }

    static void backspaceAndDeleteAreUndoneWithCursor() {
        Editor e = editor("abcdef");
        e.moveTo(3);
        e.backspace();                                       // removes 'c'
        assertEquals("abdef", e.text(), "backspace");
        assertEquals(2, e.cursor(), "cursor moved left");
        e.deleteForward();                                   // removes 'd'
        assertEquals("abef", e.text(), "delete key");
        e.undo();
        assertEquals("abdef", e.text(), "undo delete key");
        assertEquals(2, e.cursor(), "cursor where the delete happened");
        e.undo();
        assertEquals("abcdef", e.text(), "undo backspace");
        assertEquals(3, e.cursor(), "cursor back after the restored 'c'");
        e.moveTo(0);
        e.backspace();                                       // nothing before the cursor
        assertEquals(0, e.undoSize(), "backspace at position 0 records nothing");
        pass("backspaceAndDeleteAreUndoneWithCursor");
    }

    static void cutCopyPasteWithSelection() {
        Editor e = editor("one two three");
        e.select(4, 8);                                      // "two "
        e.copy();
        assertEquals("two ", e.clipboard(), "copy");
        assertEquals(0, e.undoSize(), "copy is not an edit");
        e.cut();
        assertEquals("one three", e.text(), "cut removes the selection");
        e.moveTo(9);
        e.type(" ");
        e.paste();
        assertEquals("one three two ", e.text(), "paste at the cursor");
        e.select(0, 3);                                      // "one"
        e.paste();
        assertEquals("two  three two ", e.text(), "paste over a selection replaces it");
        e.undo();
        assertEquals("one three two ", e.text(), "undo the replacing paste in one step");
        pass("cutCopyPasteWithSelection");
    }

    static void typingOverASelectionIsOneStep() {
        Editor e = editor("Hello world");
        e.select(6, 11);
        e.type("there");
        assertEquals("Hello there", e.text(), "selection replaced");
        assertEquals(1, e.undoSize(), "delete + insert recorded as one step");
        e.undo();
        assertEquals("Hello world", e.text(), "one undo brings the old word back");
        pass("typingOverASelectionIsOneStep");
    }

    static void replaceAllIsOneUndoStep() {
        Editor e = editor("cat sat on the cat mat; cat!");
        int n = e.replaceAll("cat", "dog");
        assertEquals(3, n, "three replacements");
        assertEquals("dog sat on the dog mat; dog!", e.text(), "all replaced");
        assertEquals(1, e.undoSize(), "one entry in the history");
        e.undo();
        assertEquals("cat sat on the cat mat; cat!", e.text(), "one undo restores all three");
        e.redo();
        assertEquals("dog sat on the dog mat; dog!", e.text(), "redo replaces all again");
        assertEquals(0, e.replaceAll("zebra", "x"), "no match: nothing recorded");
        assertEquals(1, e.undoSize(), "still one entry");
        e.replaceAll("dog", "a");                            // replacement of a different length
        assertEquals("a sat on the a mat; a!", e.text(), "shorter replacement");
        e.undo();
        assertEquals("dog sat on the dog mat; dog!", e.text(), "undone");
        pass("replaceAllIsOneUndoStep");
    }

    static void boundedHistoryDropsOldest() {
        Editor e = new Editor(new GapBuffer(""), new ManualClock(T0), 3, Duration.ofSeconds(1));
        for (String word : List.of("a", "b", "c", "d", "e")) {
            e.moveTo(e.text().length());                     // cursor move = separate undo steps
            e.type(word);
        }
        assertEquals(3, e.undoSize(), "only the last 3 steps are kept");
        while (e.undo()) { }
        assertEquals("ab", e.text(), "the 2 oldest edits can no longer be undone");
        pass("boundedHistoryDropsOldest");
    }

    static void cursorRestoredOnUndoAndRedo() {
        Editor e = editor("The quick fox");
        e.moveTo(4);
        e.type("very ");
        e.moveTo(0);                                         // user scrolls away to the top
        e.undo();
        assertEquals(4, e.cursor(), "undo puts the cursor where the edit was, not where the user scrolled");
        e.moveTo(13);
        e.redo();
        assertEquals(9, e.cursor(), "redo puts the cursor after the re-inserted text");
        e.replaceAll("quick", "slow");
        e.undo();
        assertEquals(9, e.cursor(), "macro undo restores the cursor too");
        pass("cursorRestoredOnUndoAndRedo");
    }

    // =============================================================== properties

    /** 2,000 random inserts/deletes applied to every buffer: they must always agree with StringBuilder. */
    static void randomEditsAllBuffersAgreeWithStringBuilder() {
        Random rnd = new Random(42);
        String start = "The quick brown fox jumps over the lazy dog.";
        StringBuilder reference = new StringBuilder(start);
        List<TextBuffer> buffers = BUFFERS.stream().map(f -> f.apply(start)).toList();
        for (int step = 0; step < 2_000; step++) {
            boolean insert = reference.length() == 0 || rnd.nextInt(10) < 6;
            if (insert) {
                int pos = rnd.nextInt(reference.length() + 1);
                String s = randomText(rnd, 1 + rnd.nextInt(5));
                reference.insert(pos, s);
                for (TextBuffer b : buffers) b.insert(pos, s);
            } else {
                int pos = rnd.nextInt(reference.length());
                int len = 1 + rnd.nextInt(Math.min(8, reference.length() - pos));
                reference.delete(pos, pos + len);
                for (TextBuffer b : buffers) b.delete(pos, len);
            }
            for (TextBuffer b : buffers) {
                assertEquals(reference.length(), b.length(), b.name() + " length at step " + step);
                if (step % 50 == 0 || step == 1_999)
                    assertEquals(reference.toString(), b.text(), b.name() + " text at step " + step);
            }
        }
        int i = rnd.nextInt(reference.length());
        for (TextBuffer b : buffers) assertEquals(reference.charAt(i), b.charAt(i), b.name() + " charAt");
        pass("randomEditsAllBuffersAgreeWithStringBuilder");
    }

    /**
     * A random session (typing, backspace, delete, selections, cut/paste, replace-all, undo, redo, pauses)
     * run on three editors, one per buffer. Afterwards: undo everything = the original text, redo
     * everything = the final text. If ANY command's undo is slightly wrong, this finds it.
     */
    static void randomEditorSessionUndoesBackToOriginal() {
        String original = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.";
        var clock = new ManualClock(T0);
        List<Editor> editors = BUFFERS.stream()
                .map(f -> new Editor(f.apply(original), clock, 100_000, Duration.ofSeconds(1))).toList();
        Random rnd = new Random(7);
        for (int step = 0; step < 2_000; step++) {
            int op = rnd.nextInt(100);
            int len = editors.get(0).text().length();
            int a = rnd.nextInt(len + 1), b = rnd.nextInt(len + 1);
            String s = randomText(rnd, 1 + rnd.nextInt(3));
            String key = String.valueOf(" abcde".charAt(rnd.nextInt(6)));
            for (Editor e : editors) {
                if (op < 35) e.type(key);
                else if (op < 42) e.type(s);
                else if (op < 50) e.backspace();
                else if (op < 55) e.deleteForward();
                else if (op < 63) e.moveTo(a);
                else if (op < 68) e.select(a, b);
                else if (op < 71) e.cut();
                else if (op < 73) e.copy();
                else if (op < 77) e.paste();
                else if (op < 79) e.replaceAll(key, s);
                else if (op < 90) e.undo();
                else e.redo();
            }
            if (rnd.nextInt(5) == 0) clock.advance(Duration.ofMillis(rnd.nextInt(2_000)));
            String expected = editors.get(0).text();
            for (Editor e : editors) {
                assertEquals(expected, e.text(), e.buffer().name() + " text at step " + step);
                assertEquals(editors.get(0).cursor(), e.cursor(), e.buffer().name() + " cursor at step " + step);
            }
        }
        for (Editor e : editors) {
            String finalText = e.text();
            int steps = 0;
            while (e.undo()) steps++;
            assertEquals(original, e.text(), e.buffer().name() + ": undo all " + steps + " steps = original");
            while (e.redo()) { }
            assertEquals(finalText, e.text(), e.buffer().name() + ": redo all = final text");
        }
        pass("randomEditorSessionUndoesBackToOriginal");
    }

    // =============================================================== helpers

    static Editor editor(String initial) {
        return new Editor(new GapBuffer(initial), new ManualClock(T0));
    }

    /** Types one key at a time, `gapMillis` apart, like a person typing. */
    static void typeKeys(Editor e, ManualClock clock, String keys, long gapMillis) {
        for (char c : keys.toCharArray()) {
            clock.advance(Duration.ofMillis(gapMillis));
            e.type(String.valueOf(c));
        }
    }

    static String randomText(Random rnd, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("abcxyz XYZ\n".charAt(rnd.nextInt(11)));
        return sb.toString();
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable r, String what) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
