package fs;

/**
 * A leaf whose content is just another path. The target may not exist (a "dangling" link) and may
 * point back up the tree (a loop); both are only discovered when someone follows the link.
 */
public final class SymLink extends Node {
    final String target;

    SymLink(String name, Directory parent, String owner, String group, String target) {
        super(name, parent, owner, group, 0777);   // Linux ignores a symlink's own mode bits
        this.target = target;
    }

    /** Like Linux: a symlink's size is the length of the target path it stores. */
    @Override long size() { return target.length(); }

    @Override char typeChar() { return 'l'; }
}
