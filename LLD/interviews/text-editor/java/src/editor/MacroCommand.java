package editor;

import java.util.List;

/**
 * Several commands that undo and redo as ONE step (the Composite pattern): replace-all, paste over a
 * selection, typing over a selection. Undo runs the parts in reverse order, so each part sees exactly
 * the buffer state its own execute() produced.
 */
public record MacroCommand(String label, List<Command> parts, int cursorBefore, int cursorAfter) implements Command {

    public MacroCommand {
        parts = List.copyOf(parts);                          // immutable snapshot of the list
    }

    @Override public void execute(TextBuffer buffer) {
        for (Command c : parts) c.execute(buffer);
    }

    @Override public void undo(TextBuffer buffer) {
        for (int i = parts.size() - 1; i >= 0; i--) parts.get(i).undo(buffer);
    }
}
