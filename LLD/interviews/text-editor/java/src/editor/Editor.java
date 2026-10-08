package editor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The editor: a buffer, a cursor, an optional selection, a clipboard, and two stacks of commands.
 *
 *  - Every edit becomes a Command, is executed, and pushed on the undo stack.
 *  - undo() pops it, calls command.undo(), and pushes it on the redo stack.
 *  - A NEW edit clears the redo stack: the "future" it held no longer follows from the current text.
 *  - Consecutive single-character typing is coalesced into one command (one undo step per word).
 *  - The undo stack is bounded: past maxHistory, the oldest command is dropped.
 *
 * Not thread-safe: like real editors, all edits happen on one UI thread.
 */
public final class Editor {
    private final TextBuffer buffer;
    private final Clock clock;
    private final int maxHistory;
    private final Duration coalesceWindow;

    private final Deque<Command> undoStack = new ArrayDeque<>();   // head = most recent
    private final Deque<Command> redoStack = new ArrayDeque<>();

    private int cursor;
    private int anchor = -1;            // selection = [min(anchor, cursor), max(anchor, cursor)); -1 = no selection
    private String clipboard = "";
    private long lastTypedAtMillis;
    private boolean mayCoalesce;        // true only right after type(); any other action breaks the group

    public Editor(TextBuffer buffer, Clock clock, int maxHistory, Duration coalesceWindow) {
        if (maxHistory < 1) throw new IllegalArgumentException("maxHistory must be >= 1");
        this.buffer = buffer;
        this.clock = clock;
        this.maxHistory = maxHistory;
        this.coalesceWindow = coalesceWindow;
    }

    public Editor(TextBuffer buffer, Clock clock) { this(buffer, clock, 1_000, Duration.ofSeconds(1)); }

    // ------------------------------------------------------------------ cursor and selection

    public void moveTo(int pos) {
        cursor = clamp(pos);
        anchor = -1;
        mayCoalesce = false;            // jumping elsewhere starts a new undo group
    }

    /** Selects [from, to); the cursor ends at `to`, like a mouse drag from `from` to `to`. */
    public void select(int from, int to) {
        anchor = clamp(from);
        cursor = clamp(to);
        mayCoalesce = false;
    }

    public boolean hasSelection() { return anchor >= 0 && anchor != cursor; }

    public String selectedText() { return hasSelection() ? buffer.substring(selStart(), selEnd()) : ""; }

    // ------------------------------------------------------------------ editing

    /** Typing at the cursor. Replaces the selection if there is one. */
    public void type(String s) {
        if (s.isEmpty()) return;
        if (hasSelection()) {
            int start = selStart();
            run(new MacroCommand("Type over selection",
                    List.of(deleteSelectionCommand(), new InsertCommand(start, s, start)),
                    cursor, start + s.length()));
            return;
        }
        long now = clock.millis();
        InsertCommand cmd = new InsertCommand(cursor, s, cursor);
        cmd.execute(buffer);
        cursor = cmd.cursorAfter();
        if (canCoalesce(cmd, now)) {
            InsertCommand last = (InsertCommand) undoStack.pop();
            undoStack.push(last.append(s));                 // same undo step, now one char longer
            redoStack.clear();
        } else {
            push(cmd);
        }
        lastTypedAtMillis = now;
        mayCoalesce = true;
    }

    /** Backspace: delete the selection, or the character before the cursor. */
    public void backspace() {
        if (hasSelection()) { run(deleteSelectionCommand()); return; }
        if (cursor == 0) return;
        run(new DeleteCommand(cursor - 1, buffer.substring(cursor - 1, cursor), cursor));
    }

    /** Delete key: delete the selection, or the character after the cursor. */
    public void deleteForward() {
        if (hasSelection()) { run(deleteSelectionCommand()); return; }
        if (cursor == buffer.length()) return;
        run(new DeleteCommand(cursor, buffer.substring(cursor, cursor + 1), cursor));
    }

    /** Copy is not an edit: nothing goes on the undo stack. */
    public void copy() {
        if (hasSelection()) clipboard = selectedText();
    }

    public void cut() {
        if (!hasSelection()) return;
        copy();
        run(deleteSelectionCommand());
    }

    public void paste() {
        if (clipboard.isEmpty()) return;
        List<Command> parts = new ArrayList<>();
        int at = cursor;
        if (hasSelection()) {
            at = selStart();
            parts.add(deleteSelectionCommand());            // pasting over a selection replaces it
        }
        parts.add(new InsertCommand(at, clipboard, at));
        run(new MacroCommand("Paste", parts, cursor, at + clipboard.length()));
    }

    /** Replaces every occurrence as ONE undoable step. Returns the number of replacements. */
    public int replaceAll(String find, String replacement) {
        if (find.isEmpty()) throw new IllegalArgumentException("find must not be empty");
        String text = buffer.text();
        List<Integer> hits = new ArrayList<>();
        for (int i = text.indexOf(find); i >= 0; i = text.indexOf(find, i + find.length())) hits.add(i);
        if (hits.isEmpty()) return 0;

        // Build the parts from the LAST hit to the first: replacing at the end never shifts earlier positions.
        List<Command> parts = new ArrayList<>();
        for (int k = hits.size() - 1; k >= 0; k--) {
            int pos = hits.get(k);
            parts.add(new DeleteCommand(pos, find, cursor));
            parts.add(new InsertCommand(pos, replacement, cursor));
        }
        int newLength = text.length() + hits.size() * (replacement.length() - find.length());
        run(new MacroCommand("Replace all \"" + find + "\"", parts, cursor, Math.min(cursor, newLength)));
        return hits.size();
    }

    // ------------------------------------------------------------------ undo / redo

    public boolean undo() {
        Command c = undoStack.poll();
        if (c == null) return false;
        c.undo(buffer);
        cursor = c.cursorBefore();                          // put the cursor back where the edit happened
        anchor = -1;
        redoStack.push(c);
        mayCoalesce = false;
        return true;
    }

    public boolean redo() {
        Command c = redoStack.poll();
        if (c == null) return false;
        c.execute(buffer);
        cursor = c.cursorAfter();
        anchor = -1;
        undoStack.push(c);                                  // NOT push(): redo must not clear the redo stack
        trimHistory();
        mayCoalesce = false;
        return true;
    }

    // ------------------------------------------------------------------ queries

    public String text() { return buffer.text(); }
    public int cursor() { return cursor; }
    public int undoSize() { return undoStack.size(); }
    public int redoSize() { return redoStack.size(); }
    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    public String clipboard() { return clipboard; }
    public TextBuffer buffer() { return buffer; }

    /** Labels of the undo stack, most recent first (what an IDE's undo-history menu would list). */
    public List<String> undoHistory() { return undoStack.stream().map(Command::label).toList(); }

    // ------------------------------------------------------------------ internals

    /** Run a NEW edit: execute, move the cursor, record it. */
    private void run(Command c) {
        c.execute(buffer);
        cursor = c.cursorAfter();
        anchor = -1;
        push(c);
        mayCoalesce = false;
    }

    private void push(Command c) {
        undoStack.push(c);
        redoStack.clear();                                  // a new edit makes the old "future" invalid
        trimHistory();
    }

    private void trimHistory() {
        while (undoStack.size() > maxHistory) undoStack.removeLast();   // forget the OLDEST step
    }

    /**
     * Merge a keystroke into the previous typing command when it is the same "burst":
     * one character, right where the last insert ended, within the time window, no cursor jump in between,
     * and not the first letter after a space (so each word is its own undo step).
     */
    private boolean canCoalesce(InsertCommand cmd, long now) {
        if (!mayCoalesce || cmd.text().length() != 1) return false;
        if (!(undoStack.peek() instanceof InsertCommand last)) return false;
        if (last.end() != cmd.pos()) return false;
        if (now - lastTypedAtMillis > coalesceWindow.toMillis()) return false;
        boolean lastEndsWithSpace = Character.isWhitespace(last.text().charAt(last.text().length() - 1));
        boolean thisIsSpace = Character.isWhitespace(cmd.text().charAt(0));
        return !(lastEndsWithSpace && !thisIsSpace);        // "hello " + "w" starts a new word
    }

    private Command deleteSelectionCommand() {
        return new DeleteCommand(selStart(), buffer.substring(selStart(), selEnd()), cursor);
    }

    private int selStart() { return Math.min(anchor, cursor); }
    private int selEnd() { return Math.max(anchor, cursor); }
    private int clamp(int pos) { return Math.max(0, Math.min(pos, buffer.length())); }
}
