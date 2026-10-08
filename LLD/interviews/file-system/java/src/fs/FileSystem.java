package fs;

import static fs.Permissions.EXEC;
import static fs.Permissions.READ;
import static fs.Permissions.WRITE;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * An in-memory, Unix-like file system: a tree of Directory / File / SymLink nodes.
 *
 * Paths given to this class must be absolute ("/a/b"); Shell turns relative ones into absolute
 * ones using its current directory. Every call says which User is acting, for permission checks.
 *
 * Thread safety: ONE ReentrantReadWriteLock for the whole tree. Reads (read, ls, stat, du, find)
 * run in parallel; any change (mkdir, write, rm, mv, ...) runs alone. Simple and obviously correct:
 * an operation that touches two places (mv) can't deadlock, and nobody sees half a move.
 * Finer-grained locking is discussed in L5-senior.md.
 */
public final class FileSystem {
    /** Linux gives up after 40 symlinks in one lookup (MAXSYMLINKS) and returns ELOOP. */
    public static final int MAX_SYMLINK_HOPS = 40;
    static final int DIR_MODE = 0755, FILE_MODE = 0644;   // 0777 / 0666 with the usual umask 022

    private final Directory root = new Directory("", null, "root", "root", DIR_MODE);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ------------------------------------------------------------------ directories

    /** `mkdir path`: the parent must exist; the name must not. */
    public void mkdir(User u, String path) {
        write(() -> mkdirLocked(u, path));
    }

    /** `mkdir -p path`: create every missing directory on the way; fine if it already exists. */
    public void mkdirs(User u, String path) {
        write(() -> {
            List<String> parts = Path.split(requireAbsolute(path));
            for (int i = 1; i <= parts.size(); i++) {
                String prefix = "/" + String.join("/", parts.subList(0, i));
                Node existing = resolveOrNull(u, prefix);
                if (existing == null) mkdirLocked(u, prefix);
                else if (!(existing instanceof Directory)) throw new FsException.AlreadyExists(prefix + " exists and is not a directory");
            }
            return null;
        });
    }

    /** `ls path`: sorted child names. On a file, just its own name (like ls does). */
    public List<String> ls(User u, String path) {
        return read(() -> {
            Node n = resolve(u, path, true);
            if (!(n instanceof Directory d)) return List.of(n.name);
            Permissions.check(u, d, READ, "list");
            return List.copyOf(d.children.keySet());           // TreeMap: already sorted
        });
    }

    /** `ls -l path`: one Stat per child, sorted by name. Child symlinks are shown, not followed. */
    public List<Stat> lsLong(User u, String path) {
        return read(() -> {
            Node n = resolve(u, path, true);
            if (!(n instanceof Directory d)) return List.of(Stat.of(n));
            Permissions.check(u, d, READ, "list");
            return d.children.values().stream().map(Stat::of).toList();
        });
    }

    // ------------------------------------------------------------------ files

    /** `touch` that refuses to overwrite: creates an empty file. */
    public void createFile(User u, String path) {
        write(() -> createFileLocked(u, path));
    }

    /** `echo text > path`: create if missing, otherwise replace the content. */
    public void write(User u, String path, String text) {
        write(() -> { fileForWriting(u, path).write(text); return null; });
    }

    /** `echo text >> path`: create if missing, otherwise add to the end. */
    public void append(User u, String path, String text) {
        write(() -> { fileForWriting(u, path).append(text); return null; });
    }

    /** `cat path`. Follows symlinks. */
    public String read(User u, String path) {
        return read(() -> {
            Node n = resolve(u, path, true);
            if (!(n instanceof File f)) throw new FsException.IsADirectory(path);
            Permissions.check(u, f, READ, "read");
            return f.read();
        });
    }

    // ------------------------------------------------------------------ links, metadata

    /** `ln -s target linkPath`. The target is stored as text and not checked. */
    public void symlink(User u, String target, String linkPath) {
        write(() -> {
            Where w = parentOf(u, linkPath);
            requireAbsentAndWritable(u, w);
            w.dir.children.put(w.name, new SymLink(w.name, w.dir, u.name(), u.primaryGroup(), target));
            return null;
        });
    }

    /** `stat path` without following a final symlink (that's `lstat` in C). */
    public Stat stat(User u, String path) {
        return read(() -> Stat.of(resolve(u, path, false)));
    }

    /** `realpath path`: the physical absolute path after following every symlink and "..". */
    public String realPath(User u, String path) {
        return read(() -> resolve(u, path, true).path());
    }

    /** `chmod mode path`: only the owner or root may change permissions. */
    public void chmod(User u, String path, int mode) {
        write(() -> {
            Node n = resolve(u, path, true);
            if (!u.isRoot() && !u.name().equals(n.owner))
                throw new FsException.AccessDenied("chmod '" + path + "': not the owner");
            n.mode = mode;
            return null;
        });
    }

    /** `chown owner:group path`: root only (as on Linux, so users can't give away files). */
    public void chown(User u, String path, String owner, String group) {
        write(() -> {
            if (!u.isRoot()) throw new FsException.AccessDenied("chown '" + path + "': root only");
            Node n = resolve(u, path, true);
            n.owner = owner;
            n.group = group;
            return null;
        });
    }

    // ------------------------------------------------------------------ remove, move

    /** `rm path` / `rm -r path`. An empty directory can be removed without -r (like `rmdir`). */
    public void rm(User u, String path, boolean recursive) {
        write(() -> {
            Where w = parentOf(u, path);
            Node victim = w.dir.children.get(w.name);
            if (victim == null) throw new FsException.NoSuchFile(path);
            Permissions.check(u, w.dir, WRITE | EXEC, "remove entry from");
            if (victim instanceof Directory d && !d.children.isEmpty()) {
                if (!recursive) throw new FsException.DirectoryNotEmpty(path);
                checkSubtreeDeletable(u, d);                    // all-or-nothing, unlike real rm -r
            }
            w.dir.children.remove(w.name);
            victim.parent = null;
            return null;
        });
    }

    /**
     * `mv src dst`. If dst is an existing directory, src moves INTO it, keeping its name.
     * O(1) whatever the size of the subtree: one entry leaves one map and joins another.
     * Rules: a directory can't move into its own subtree; a file may replace a file;
     * anything else that already exists at the destination is an error.
     */
    public void mv(User u, String src, String dst) {
        write(() -> {
            Where from = parentOf(u, src);
            Node node = from.dir.children.get(from.name);
            if (node == null) throw new FsException.NoSuchFile(src);
            Permissions.check(u, from.dir, WRITE | EXEC, "move out of");

            Where to = resolveOrNull(u, dst) instanceof Directory d ? new Where(d, from.name) : parentOf(u, dst);
            Permissions.check(u, to.dir, WRITE | EXEC, "move into");

            // Walk UP from the destination: if we meet the node being moved, dst is inside it.
            for (Directory a = to.dir; a != null; a = a.parent)
                if (a == node)
                    throw new FsException.InvalidOperation("cannot move '" + src + "' into its own subtree '" + dst + "'");

            Node existing = to.dir.children.get(to.name);
            if (existing == node) return null;                  // mv a a: nothing to do
            if (existing != null) {
                if (!(existing instanceof File && node instanceof File))
                    throw new FsException.AlreadyExists(to.dir.path() + "/" + to.name);
                existing.parent = null;                         // the old file is replaced
            }
            from.dir.children.remove(from.name);
            node.name = to.name;
            node.parent = to.dir;
            to.dir.children.put(to.name, node);
            return null;
        });
    }

    // ------------------------------------------------------------------ du, find

    /** `du -sb path`: total bytes below path (recomputed, see Directory.size). */
    public long du(User u, String path) {
        return read(() -> {
            Node n = resolve(u, path, true);
            if (n instanceof Directory) Permissions.check(u, n, READ, "du");
            return n.size();
        });
    }

    /**
     * `find start -<filters>`: depth-first, children in name order, symlinks not followed (like
     * find's default). Directories the user may not read are skipped silently (find prints an error).
     * Uses an explicit stack instead of recursion so a very deep tree can't overflow the call stack.
     */
    public List<String> find(User u, String start, Predicate<Stat> filter) {
        return read(() -> {
            List<String> out = new ArrayList<>();
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(resolve(u, start, true));
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                Stat s = Stat.of(n);
                if (filter.test(s)) out.add(s.path());
                if (n instanceof Directory d && Permissions.allowed(u, d, READ | EXEC))
                    for (Node child : d.children.descendingMap().values()) stack.push(child);   // reversed so "a" pops first
            }
            return out;
        });
    }

    // ================================================================== internals (caller holds the lock)

    /** A parent directory and the last name in a path: where something is (or will be). */
    private record Where(Directory dir, String name) {}

    /**
     * The path walk. Starts at the root and looks up one name at a time:
     *  - needs x (search permission) on every directory it looks inside;
     *  - "." stays, ".." goes to the parent (physically: after a symlink, the TARGET's parent);
     *  - a symlink in the middle of the path, or at the end when followLast, is replaced by its
     *    target's parts (absolute targets restart at the root), counting hops to catch loops.
     */
    private Node resolve(User u, String path, boolean followLast) {
        Deque<String> todo = new ArrayDeque<>(Path.split(requireAbsolute(path)));
        Node cur = root;
        int hops = 0;
        while (!todo.isEmpty()) {
            if (!(cur instanceof Directory dir)) throw new FsException.NotADirectory(cur.path() + " (in " + path + ")");
            Permissions.check(u, dir, EXEC, "search");
            String part = todo.pollFirst();
            Node next = switch (part) {
                case "." -> dir;
                case ".." -> dir.parent == null ? dir : dir.parent;   // ".." at the root stays at the root
                default -> dir.children.get(part);
            };
            if (next == null) throw new FsException.NoSuchFile(path);
            if (next instanceof SymLink link && (followLast || !todo.isEmpty())) {
                if (++hops > MAX_SYMLINK_HOPS) throw new FsException.TooManyLinks(path);
                List<String> target = Path.split(link.target);
                for (int i = target.size() - 1; i >= 0; i--) todo.addFirst(target.get(i));
                cur = Path.isAbsolute(link.target) ? root : dir;      // relative targets start at the link's directory
                continue;
            }
            cur = next;
        }
        return cur;
    }

    private Node resolveOrNull(User u, String path) {
        try {
            return resolve(u, path, true);
        } catch (FsException.NoSuchFile e) {
            return null;
        }
    }

    /** Resolves everything but the last name, which must be a real name (not "." or ".."). */
    private Where parentOf(User u, String path) {
        List<String> parts = Path.split(requireAbsolute(path));
        if (parts.isEmpty()) throw new FsException.InvalidOperation("'/' has no parent");
        String name = parts.getLast();
        if (name.equals(".") || name.equals(".."))
            throw new FsException.InvalidOperation("path must end in a name: " + path);
        String parentPath = "/" + String.join("/", parts.subList(0, parts.size() - 1));
        if (!(resolve(u, parentPath, true) instanceof Directory d)) throw new FsException.NotADirectory(parentPath);
        return new Where(d, name);
    }

    private Void mkdirLocked(User u, String path) {
        Where w = parentOf(u, path);
        requireAbsentAndWritable(u, w);
        w.dir.children.put(w.name, new Directory(w.name, w.dir, u.name(), u.primaryGroup(), DIR_MODE));
        return null;
    }

    private File createFileLocked(User u, String path) {
        Where w = parentOf(u, path);
        requireAbsentAndWritable(u, w);
        File f = new File(w.name, w.dir, u.name(), u.primaryGroup(), FILE_MODE);
        w.dir.children.put(w.name, f);
        return f;
    }

    private File fileForWriting(User u, String path) {
        Node n = resolveOrNull(u, path);
        if (n == null) return createFileLocked(u, path);
        if (!(n instanceof File f)) throw new FsException.IsADirectory(path);
        Permissions.check(u, f, WRITE, "write");
        return f;
    }

    /** Creating a name needs w (change the entry list) and x (look inside) on the directory. */
    private void requireAbsentAndWritable(User u, Where w) {
        Permissions.check(u, w.dir, WRITE | EXEC, "create in");
        if (w.dir.children.containsKey(w.name)) throw new FsException.AlreadyExists(w.dir.path() + "/" + w.name);
    }

    /** rm -r needs r (list), w (unlink) and x (search) on every directory it empties. */
    private void checkSubtreeDeletable(User u, Directory top) {
        Deque<Directory> stack = new ArrayDeque<>(List.of(top));
        while (!stack.isEmpty()) {
            Directory d = stack.pop();
            Permissions.check(u, d, READ | WRITE | EXEC, "remove contents of");
            for (Node c : d.children.values()) if (c instanceof Directory sub) stack.push(sub);
        }
    }

    private static String requireAbsolute(String path) {
        if (path == null || path.isEmpty()) throw new FsException.NoSuchFile("empty path");
        if (!Path.isAbsolute(path)) throw new FsException.InvalidOperation("expected an absolute path: " + path);
        return path;
    }

    private <T> T read(Supplier<T> body) {
        lock.readLock().lock();
        try { return body.get(); } finally { lock.readLock().unlock(); }
    }

    private <T> T write(Supplier<T> body) {
        lock.writeLock().lock();
        try { return body.get(); } finally { lock.writeLock().unlock(); }
    }
}
