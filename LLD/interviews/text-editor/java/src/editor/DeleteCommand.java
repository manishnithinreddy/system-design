package editor;

/**
 * Delete the characters `deleted` starting at pos. The deleted text is captured BEFORE the delete runs
 * (by the editor), because after it runs the text is gone and undo would have nothing to put back.
 */
public record DeleteCommand(int pos, String deleted, int cursorBefore) implements Command {

    @Override public void execute(TextBuffer buffer) { buffer.delete(pos, deleted.length()); }

    @Override public void undo(TextBuffer buffer) { buffer.insert(pos, deleted); }

    @Override public int cursorAfter() { return pos; }

    @Override public String label() { return "Delete \"" + deleted + "\""; }
}
