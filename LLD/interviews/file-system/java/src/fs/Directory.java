package fs;

import java.util.TreeMap;

/** A node that contains other nodes. TreeMap keeps children sorted by name, so `ls` needs no sort. */
public final class Directory extends Node {
    final TreeMap<String, Node> children = new TreeMap<>();

    Directory(String name, Directory parent, String owner, String group, int mode) {
        super(name, parent, owner, group, mode);
    }

    /**
     * Recomputed on every call (the simple choice): O(number of nodes below). Symlinks count their
     * own few bytes and are never followed, so a link loop can't make this recurse forever.
     * L5 discusses caching sizes and updating the ancestors on each write instead.
     */
    @Override long size() {
        long total = 0;
        for (Node child : children.values()) total += child.size();
        return total;
    }

    @Override char typeChar() { return 'd'; }
}
