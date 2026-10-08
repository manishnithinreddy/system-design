package editor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** A short editing session, then a (non-asserted) timing comparison of the three buffers. */
public final class Demo {
    public static void main(String[] args) {
        var clock = new ManualClock(Instant.parse("2026-10-08T09:00:00Z"));
        Editor e = new Editor(new GapBuffer(""), clock);

        System.out.println("--- Typing \"Hello world\" one key every 120 ms ---");
        for (char c : "Hello world".toCharArray()) {
            clock.advance(Duration.ofMillis(120));
            e.type(String.valueOf(c));
        }
        show(e);
        System.out.println("  undo history (newest first): " + e.undoHistory());

        System.out.println("--- 3 s pause, then \"!!\" ---");
        clock.advance(Duration.ofSeconds(3));
        e.type("!");
        clock.advance(Duration.ofMillis(100));
        e.type("!");
        System.out.println("  undo history: " + e.undoHistory());

        System.out.println("--- Ctrl+Z twice, then Ctrl+Shift+Z once ---");
        e.undo(); show(e);
        e.undo(); show(e);
        e.redo(); show(e);

        System.out.println("--- Select \"world\", cut, paste at the start ---");
        e.select(6, 11);
        e.cut(); show(e);
        e.moveTo(0);
        e.paste(); show(e);

        System.out.println("--- Replace all \"l\" -> \"L\" (one undo step) ---");
        e.replaceAll("l", "L"); show(e);
        System.out.println("  undo history: " + e.undoHistory());
        e.undo(); show(e);

        System.out.println("--- New edit after undo: redo stack is cleared ---");
        System.out.println("  redo stack before: " + e.redoSize());
        e.type("?");
        System.out.println("  redo stack after typing '?': " + e.redoSize());
        show(e);

        timing();
    }

    static void show(Editor e) {
        String t = e.text();
        System.out.printf("  text=\"%s|%s\"  (| = cursor at %d)  undo=%d redo=%d%n",
                t.substring(0, e.cursor()), t.substring(e.cursor()), e.cursor(), e.undoSize(), e.redoSize());
    }

    /** Type 100,000 chars one at a time into the MIDDLE of a 1,000,000-char document. Real numbers, not asserted. */
    static void timing() {
        int docSize = 1_000_000, typed = 100_000;
        String doc = "x".repeat(docSize);
        System.out.printf("--- Timing: typing %,d chars one by one into the middle of a %,d-char document ---%n",
                typed, docSize);
        List<Function<String, TextBuffer>> makers = List.of(StringBuilderBuffer::new, GapBuffer::new, PieceTable::new);
        for (var make : makers) typeIntoMiddle(make.apply(doc), 2_000);      // warm up the JIT (just-in-time compiler)
        for (var make : makers) {
            TextBuffer b = make.apply(doc);
            long start = System.nanoTime();
            typeIntoMiddle(b, typed);
            long ms = (System.nanoTime() - start) / 1_000_000;
            String extra = switch (b) {
                case GapBuffer g -> String.format("  (chars copied by gap moves: %,d)", g.charsMoved());
                case PieceTable p -> String.format("  (pieces: %d)", p.pieceCount());
                default -> "  (every insert shifts ~" + String.format("%,d", docSize / 2) + " chars)";
            };
            System.out.printf("  %-20s %6d ms%s%n", b.name(), ms, extra);
            if (b.length() != docSize + typed) throw new AssertionError("wrong length");
        }
    }

    static void typeIntoMiddle(TextBuffer b, int n) {
        int pos = b.length() / 2;
        for (int i = 0; i < n; i++) b.insert(pos + i, "a");
    }
}
