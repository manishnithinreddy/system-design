package editor;

/**
 * One undoable edit (the Command pattern). A command stores just enough to redo AND undo itself:
 * an insert remembers what it inserted, a delete remembers what it deleted. No copies of the document.
 * Sealed: the editor knows every kind of command, so pattern matching over them is checked by the compiler.
 */
public sealed interface Command permits InsertCommand, DeleteCommand, MacroCommand {

    void execute(TextBuffer buffer);

    /** Must exactly reverse execute(), assuming the buffer is in the state execute() left it in. */
    void undo(TextBuffer buffer);

    /** Where the cursor was before the edit: restored on undo. */
    int cursorBefore();

    /** Where the cursor goes after the edit: restored on redo. */
    int cursorAfter();

    /** What Edit > Undo would show, e.g. "Undo Typing". */
    String label();
}
