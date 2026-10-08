package editor;

/** Insert text at pos. Undo = delete the same number of characters at the same place. */
public record InsertCommand(int pos, String text, int cursorBefore) implements Command {

    @Override public void execute(TextBuffer buffer) { buffer.insert(pos, text); }

    @Override public void undo(TextBuffer buffer) { buffer.delete(pos, text.length()); }

    @Override public int cursorAfter() { return pos + text.length(); }

    @Override public String label() { return "Typing \"" + text + "\""; }

    /** Position right after the inserted text: where the next keystroke lands if the user keeps typing. */
    public int end() { return pos + text.length(); }

    /** Coalescing: "hel" + "l" becomes one command "hell". Records are immutable, so this returns a new one. */
    public InsertCommand append(String more) { return new InsertCommand(pos, text + more, cursorBefore); }
}
