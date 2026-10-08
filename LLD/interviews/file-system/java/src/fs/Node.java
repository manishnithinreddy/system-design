package fs;

/**
 * One entry in the tree. This is the Composite pattern: a Directory holds Nodes (which may be
 * Directories again), and File / SymLink are the leaves. Callers can ask any node for its size()
 * without caring which kind it is.
 *
 * Sealed: only these three kinds exist, so a switch over them is checked by the compiler.
 * Fields are package-private and mutable on purpose: only FileSystem changes them, and only
 * while it holds its write lock. Outside the package, callers only ever see immutable Stat records.
 */
public abstract sealed class Node permits Directory, File, SymLink {
    String name;          // "" for the root
    Directory parent;     // null for the root (and for a node that has been removed)
    String owner;
    String group;
    int mode;             // permission bits, written in octal: 0755 = rwxr-xr-x

    Node(String name, Directory parent, String owner, String group, int mode) {
        this.name = name;
        this.parent = parent;
        this.owner = owner;
        this.group = group;
        this.mode = mode;
    }

    /** Bytes used by this node and, for a directory, by everything below it. */
    abstract long size();

    /** 'd', '-' or 'l': the first character of an `ls -l` line. */
    abstract char typeChar();

    /** Absolute path, built by walking parent pointers up to the root. O(depth). */
    String path() {
        if (parent == null) return "/";
        String up = parent.path();
        return up.equals("/") ? "/" + name : up + "/" + name;
    }
}
