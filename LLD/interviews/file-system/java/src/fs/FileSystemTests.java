package fs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plain-Java tests (no JUnit) so the code runs with just javac + java.
 * Most tests act as root; the permission tests use alice (groups dev) and bob (groups ops).
 */
public final class FileSystemTests {
    private static int passed = 0;
    static final User ROOT = User.ROOT;
    static final User ALICE = User.of("alice", "dev");
    static final User BOB = User.of("bob", "ops");
    static final User CAROL = User.of("carol", "dev");

    public static void main(String[] args) throws Exception {
        // tree basics
        mkdirAndMkdirs();
        createReadWriteAppend();
        lsIsSortedAndWorksOnFiles();
        errorsAreSpecific();
        // paths
        pathNormalisationEdgeCases();
        walkHandlesDotDotAndSlashesLikeNormalize();
        shellRelativePathsAndCd();
        // move / remove / size / find
        renameFile();
        moveDirectoryKeepsItsSubtree();
        moveIntoOwnSubtreeFails();
        rmNonEmptyNeedsRecursive();
        duSumsRecursively();
        findByExtensionAndSize();
        // symlinks
        symlinkToFileAndToDirectory();
        symlinkLoopIsDetected();
        // permissions
        noExecOnDirectoryBlocksTraversal();
        noWriteOnDirectoryBlocksCreateAndDelete();
        readOnlyFileAndOwnerClassRule();
        rootBypassesPermissions();
        // concurrency and properties
        concurrentWritersAndReaders();
        randomOperationsMatchReferenceModel();
        System.out.println("All " + passed + " tests passed.");
    }

    // =============================================================== tree basics

    static void mkdirAndMkdirs() {
        FileSystem fs = new FileSystem();
        fs.mkdir(ROOT, "/var");
        assertThrows(FsException.NoSuchFile.class, () -> fs.mkdir(ROOT, "/var/log/app"), "mkdir needs the parent");
        fs.mkdirs(ROOT, "/var/log/app");
        assertTrue(fs.stat(ROOT, "/var/log/app").isDirectory(), "mkdir -p created the chain");
        fs.mkdirs(ROOT, "/var/log/app");                        // already there: no error, like mkdir -p
        assertThrows(FsException.AlreadyExists.class, () -> fs.mkdir(ROOT, "/var/log"), "plain mkdir on an existing dir");
        fs.write(ROOT, "/var/f", "x");
        assertThrows(FsException.AlreadyExists.class, () -> fs.mkdirs(ROOT, "/var/f/sub"), "mkdir -p through a file");
        pass("mkdirAndMkdirs");
    }

    static void createReadWriteAppend() {
        FileSystem fs = new FileSystem();
        fs.createFile(ROOT, "/notes.txt");
        assertEquals("", fs.read(ROOT, "/notes.txt"), "a new file is empty");
        assertThrows(FsException.AlreadyExists.class, () -> fs.createFile(ROOT, "/notes.txt"), "create refuses to overwrite");
        fs.write(ROOT, "/notes.txt", "hello");
        fs.append(ROOT, "/notes.txt", " world");
        assertEquals("hello world", fs.read(ROOT, "/notes.txt"), "write then append");
        fs.write(ROOT, "/notes.txt", "bye");
        assertEquals("bye", fs.read(ROOT, "/notes.txt"), "write replaces (like >)");
        fs.append(ROOT, "/log.txt", "a");                       // >> on a missing file creates it
        assertEquals("a", fs.read(ROOT, "/log.txt"), "append creates");
        fs.write(ROOT, "/utf8.txt", "é");
        assertEquals(2L, fs.stat(ROOT, "/utf8.txt").size(), "size is in UTF-8 bytes");
        pass("createReadWriteAppend");
    }

    static void lsIsSortedAndWorksOnFiles() {
        FileSystem fs = new FileSystem();
        for (String n : List.of("zeta", "alpha", "Beta", "mid")) fs.mkdir(ROOT, "/" + n);
        fs.write(ROOT, "/b.txt", "1");
        assertEquals(List.of("Beta", "alpha", "b.txt", "mid", "zeta"), fs.ls(ROOT, "/"), "sorted by name (uppercase first, like LC_ALL=C)");
        assertEquals(List.of("b.txt"), fs.ls(ROOT, "/b.txt"), "ls on a file prints the file");
        assertEquals(List.of(), fs.ls(ROOT, "/mid"), "empty dir");
        assertEquals("drwxr-xr-x", fs.lsLong(ROOT, "/").get(0).modeString(), "ls -l mode string");
        pass("lsIsSortedAndWorksOnFiles");
    }

    static void errorsAreSpecific() {
        FileSystem fs = new FileSystem();
        fs.mkdir(ROOT, "/d");
        fs.write(ROOT, "/f", "x");
        assertThrows(FsException.NoSuchFile.class, () -> fs.read(ROOT, "/missing"), "ENOENT");
        assertThrows(FsException.NotADirectory.class, () -> fs.read(ROOT, "/f/inside"), "ENOTDIR: a file in the middle");
        assertThrows(FsException.IsADirectory.class, () -> fs.read(ROOT, "/d"), "EISDIR: cat a directory");
        assertThrows(FsException.IsADirectory.class, () -> fs.write(ROOT, "/d", "x"), "EISDIR: write a directory");
        assertThrows(FsException.InvalidOperation.class, () -> fs.rm(ROOT, "/", true), "can't remove the root");
        assertThrows(FsException.InvalidOperation.class, () -> fs.read(ROOT, "relative"), "FileSystem wants absolute paths");
        assertThrows(FsException.NoSuchFile.class, () -> fs.read(ROOT, ""), "empty path is ENOENT, like Linux");
        pass("errorsAreSpecific");
    }

    // =============================================================== paths

    static void pathNormalisationEdgeCases() {
        assertEquals("/a/c", Path.normalize("/a/./b/../c"), "dot and dot-dot");
        assertEquals("/", Path.normalize("/.."), ".. at the root stays at the root");
        assertEquals("/", Path.normalize("/../../.."), "many .. at the root");
        assertEquals("/a/b", Path.normalize("/a/b/"), "trailing slash");
        assertEquals("/a/b", Path.normalize("//a///b"), "double slashes");
        assertEquals("/", Path.normalize("/"), "root");
        assertEquals("/", Path.normalize("/a/.."), "back to root");
        assertEquals("../b", Path.normalize("a/../../b"), "relative paths may climb above their start");
        assertEquals(".", Path.normalize("a/.."), "relative path that cancels out");
        assertEquals("/home/alice/x", Path.join("/home/alice", "x"), "join relative");
        assertEquals("/etc", Path.join("/home/alice", "/etc"), "join absolute ignores cwd");
        pass("pathNormalisationEdgeCases");
    }

    static void walkHandlesDotDotAndSlashesLikeNormalize() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/a/b");
        fs.write(ROOT, "/a/c", "C");
        for (String p : List.of("/a/./b/../c", "//a///c", "/../../a/c", "/a/b/../../a/c", "/a/c/")) {
            assertEquals("C", fs.read(ROOT, p), "walk resolves " + p);
            assertEquals("/a/c", Path.normalize(p), "normalize agrees for " + p);
        }
        assertEquals("/", fs.realPath(ROOT, "/.."), "realpath of /.. is /");
        assertEquals("/a", fs.realPath(ROOT, "/a/b/.."), "realpath climbs one level");
        pass("walkHandlesDotDotAndSlashesLikeNormalize");
    }

    static void shellRelativePathsAndCd() {
        FileSystem fs = new FileSystem();
        Shell sh = new Shell(fs, ROOT);
        sh.mkdirs("home/alice/projects");
        sh.cd("home/alice");
        assertEquals("/home/alice", sh.pwd(), "cd relative");
        sh.write("projects/readme.md", "hi");
        assertEquals("hi", sh.cat("/home/alice/projects/readme.md"), "relative write, absolute read");
        sh.cd("..");
        assertEquals("/home", sh.pwd(), "cd ..");
        sh.cd("../../../..");
        assertEquals("/", sh.pwd(), "cd .. past the root stays at /");
        assertThrows(FsException.NotADirectory.class, () -> sh.cd("home/alice/projects/readme.md"), "cd into a file");
        assertEquals("/", sh.pwd(), "a failed cd keeps the old directory");
        pass("shellRelativePathsAndCd");
    }

    // =============================================================== move / remove / size / find

    static void renameFile() {
        FileSystem fs = new FileSystem();
        fs.write(ROOT, "/a.txt", "A");
        fs.mv(ROOT, "/a.txt", "/b.txt");
        assertEquals(List.of("b.txt"), fs.ls(ROOT, "/"), "renamed");
        assertEquals("A", fs.read(ROOT, "/b.txt"), "content kept");
        fs.write(ROOT, "/c.txt", "C");
        fs.mv(ROOT, "/c.txt", "/b.txt");                        // a file may replace a file
        assertEquals("C", fs.read(ROOT, "/b.txt"), "file replaced file");
        fs.mkdir(ROOT, "/d");
        assertThrows(FsException.AlreadyExists.class, () -> { fs.mkdir(ROOT, "/e"); fs.mv(ROOT, "/e", "/b.txt"); }, "dir over a file");
        fs.mv(ROOT, "/b.txt", "/d");                            // dst is a directory: move INTO it
        assertEquals("C", fs.read(ROOT, "/d/b.txt"), "moved into dir keeping its name");
        fs.mv(ROOT, "/d/b.txt", "/d/b.txt");
        assertEquals("C", fs.read(ROOT, "/d/b.txt"), "mv onto itself is a no-op");
        pass("renameFile");
    }

    static void moveDirectoryKeepsItsSubtree() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/data/2025/q1");
        fs.write(ROOT, "/data/2025/q1/sales.csv", "1,2,3");
        fs.mkdir(ROOT, "/archive");
        fs.mv(ROOT, "/data/2025", "/archive/y2025");
        assertEquals("1,2,3", fs.read(ROOT, "/archive/y2025/q1/sales.csv"), "subtree came along");
        assertEquals("/archive/y2025/q1", fs.realPath(ROOT, "/archive/y2025/q1"), "parent pointers updated");
        assertEquals(List.of(), fs.ls(ROOT, "/data"), "old place is empty");
        pass("moveDirectoryKeepsItsSubtree");
    }

    static void moveIntoOwnSubtreeFails() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/a/b/c");
        assertThrows(FsException.InvalidOperation.class, () -> fs.mv(ROOT, "/a", "/a/b/c/a2"), "into a grandchild");
        assertThrows(FsException.InvalidOperation.class, () -> fs.mv(ROOT, "/a", "/a/b"), "into a child (existing dir)");
        assertThrows(FsException.InvalidOperation.class, () -> fs.mv(ROOT, "/a", "/a"), "into itself");
        assertTrue(fs.stat(ROOT, "/a/b/c").isDirectory(), "tree untouched after the failures");
        fs.mv(ROOT, "/a/b/c", "/c");                            // moving UP is fine
        assertEquals(List.of("a", "c"), fs.ls(ROOT, "/"), "moved up");
        pass("moveIntoOwnSubtreeFails");
    }

    static void rmNonEmptyNeedsRecursive() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/tmp/build/classes");
        fs.write(ROOT, "/tmp/build/classes/A.class", "...");
        assertThrows(FsException.DirectoryNotEmpty.class, () -> fs.rm(ROOT, "/tmp/build", false), "rm without -r");
        fs.rm(ROOT, "/tmp/build", true);
        assertEquals(List.of(), fs.ls(ROOT, "/tmp"), "rm -r removed the subtree");
        fs.mkdir(ROOT, "/tmp/empty");
        fs.rm(ROOT, "/tmp/empty", false);                       // empty dir: allowed without -r (like rmdir)
        assertThrows(FsException.NoSuchFile.class, () -> fs.rm(ROOT, "/tmp/empty", false), "already gone");
        pass("rmNonEmptyNeedsRecursive");
    }

    static void duSumsRecursively() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/srv/a/b");
        fs.write(ROOT, "/srv/one", "12345");                   // 5 bytes
        fs.write(ROOT, "/srv/a/two", "1234567890");            // 10
        fs.write(ROOT, "/srv/a/b/three", "123");               // 3
        assertEquals(18L, fs.du(ROOT, "/srv"), "5 + 10 + 3");
        assertEquals(13L, fs.du(ROOT, "/srv/a"), "10 + 3");
        fs.append(ROOT, "/srv/a/b/three", "45");
        assertEquals(20L, fs.du(ROOT, "/srv"), "a write deep down shows up at the top");
        fs.symlink(ROOT, "/srv", "/srv/a/loop");                // 4 bytes: the link itself, never followed
        assertEquals(24L, fs.du(ROOT, "/srv"), "symlink counts its own size and is not followed");
        pass("duSumsRecursively");
    }

    static void findByExtensionAndSize() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/repo/src/main");
        fs.mkdirs(ROOT, "/repo/docs");
        fs.write(ROOT, "/repo/src/main/App.java", "class App {}");
        fs.write(ROOT, "/repo/src/main/Util.java", "x");
        fs.write(ROOT, "/repo/docs/guide.md", "# guide");
        fs.write(ROOT, "/repo/Build.java", "");
        assertEquals(List.of("/repo/Build.java", "/repo/src/main/App.java", "/repo/src/main/Util.java"),
                fs.find(ROOT, "/repo", s -> s.isFile() && s.name().endsWith(".java")), "find -name '*.java' (depth-first, sorted)");
        assertEquals(List.of("/repo/docs/guide.md", "/repo/src/main/App.java"),
                fs.find(ROOT, "/repo", s -> s.isFile() && s.size() > 5), "find -size +5c");
        assertEquals(List.of("/repo", "/repo/docs", "/repo/src", "/repo/src/main"),
                fs.find(ROOT, "/repo", Stat::isDirectory), "find -type d includes the start");
        pass("findByExtensionAndSize");
    }

    // =============================================================== symlinks

    static void symlinkToFileAndToDirectory() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/opt/app-1.2/bin");
        fs.write(ROOT, "/opt/app-1.2/bin/run", "v1.2");
        fs.symlink(ROOT, "/opt/app-1.2", "/opt/current");        // absolute target, to a dir
        fs.symlink(ROOT, "bin/run", "/opt/app-1.2/start");       // relative target, to a file
        assertEquals("v1.2", fs.read(ROOT, "/opt/current/bin/run"), "through a dir link");
        assertEquals("v1.2", fs.read(ROOT, "/opt/app-1.2/start"), "file link, relative target");
        assertEquals("v1.2", fs.read(ROOT, "/opt/current/start"), "two links in one path");
        assertTrue(fs.stat(ROOT, "/opt/current").isSymLink(), "stat does not follow the last link");
        assertEquals("/opt/app-1.2/bin", fs.realPath(ROOT, "/opt/current/bin"), "realpath follows");
        assertEquals("/opt", fs.realPath(ROOT, "/opt/current/bin/../.."), ".. after a link is physical: the TARGET's parent");
        fs.write(ROOT, "/opt/current/bin/run", "patched");       // writes go through the link
        assertEquals("patched", fs.read(ROOT, "/opt/app-1.2/bin/run"), "same file");
        fs.rm(ROOT, "/opt/current", false);                       // removes the link, not the target
        assertEquals("patched", fs.read(ROOT, "/opt/app-1.2/bin/run"), "target survives rm of the link");
        fs.symlink(ROOT, "/nowhere", "/opt/dangling");
        assertThrows(FsException.NoSuchFile.class, () -> fs.read(ROOT, "/opt/dangling"), "dangling link");
        pass("symlinkToFileAndToDirectory");
    }

    static void symlinkLoopIsDetected() {
        FileSystem fs = new FileSystem();
        fs.symlink(ROOT, "/b", "/a");
        fs.symlink(ROOT, "/a", "/b");
        assertThrows(FsException.TooManyLinks.class, () -> fs.read(ROOT, "/a"), "a -> b -> a");
        fs.symlink(ROOT, "self", "/self");
        assertThrows(FsException.TooManyLinks.class, () -> fs.ls(ROOT, "/self"), "self -> self");
        fs.mkdir(ROOT, "/d");
        fs.symlink(ROOT, "..", "/d/up");                          // a link to a parent is legal...
        assertEquals("/", fs.realPath(ROOT, "/d/up/d/up/d/up"), "...and resolves fine when the path is finite");
        // A chain of exactly 40 links works, 41 fails: the hop limit, not "any repeat", decides.
        for (int i = 0; i < 41; i++) fs.symlink(ROOT, i == 0 ? "/d" : "/c" + (i - 1), "/c" + i);
        assertEquals("/d", fs.realPath(ROOT, "/c39"), "40 hops is allowed");
        assertThrows(FsException.TooManyLinks.class, () -> fs.realPath(ROOT, "/c40"), "41 hops is ELOOP");
        pass("symlinkLoopIsDetected");
    }

    // =============================================================== permissions

    static FileSystem homes() {
        FileSystem fs = new FileSystem();
        fs.mkdirs(ROOT, "/home/alice");
        fs.chown(ROOT, "/home/alice", "alice", "dev");
        fs.write(ALICE, "/home/alice/notes.txt", "secret plan");
        return fs;
    }

    static void noExecOnDirectoryBlocksTraversal() {
        FileSystem fs = homes();
        assertEquals("secret plan", fs.read(BOB, "/home/alice/notes.txt"), "0755 dir + 0644 file: others can read");
        fs.chmod(ALICE, "/home/alice", 0744);                    // r but no x for group/others
        assertThrows(FsException.AccessDenied.class, () -> fs.read(BOB, "/home/alice/notes.txt"), "no x: can't pass through");
        assertEquals(List.of("notes.txt"), fs.ls(BOB, "/home/alice"), "r without x: may still list the names");
        fs.chmod(ALICE, "/home/alice", 0711);                    // x but no r
        assertEquals("secret plan", fs.read(BOB, "/home/alice/notes.txt"), "x without r: can open a name you know");
        assertThrows(FsException.AccessDenied.class, () -> fs.ls(BOB, "/home/alice"), "x without r: can't list");
        assertEquals("secret plan", fs.read(ALICE, "/home/alice/notes.txt"), "owner still fine");
        pass("noExecOnDirectoryBlocksTraversal");
    }

    static void noWriteOnDirectoryBlocksCreateAndDelete() {
        FileSystem fs = homes();
        assertThrows(FsException.AccessDenied.class, () -> fs.write(BOB, "/home/alice/bob.txt", "hi"), "create needs w on the dir");
        assertThrows(FsException.AccessDenied.class, () -> fs.rm(BOB, "/home/alice/notes.txt", false), "delete needs w on the dir, not the file");
        assertThrows(FsException.AccessDenied.class, () -> fs.mv(BOB, "/home/alice/notes.txt", "/tmp-notes"), "mv needs w on both dirs");
        fs.chmod(ALICE, "/home/alice", 0775);                    // group dev may now write
        fs.write(CAROL, "/home/alice/carol.txt", "from carol");  // carol is in dev
        assertEquals("carol", fs.stat(ROOT, "/home/alice/carol.txt").owner(), "creator owns the new file");
        fs.chmod(ALICE, "/home/alice/notes.txt", 0400);          // read-only, owner only
        fs.rm(CAROL, "/home/alice/notes.txt", false);            // ...but carol can still delete it: w on the DIR decides
        assertEquals(List.of("carol.txt"), fs.ls(ALICE, "/home/alice"), "deleted by a group member");
        pass("noWriteOnDirectoryBlocksCreateAndDelete");
    }

    static void readOnlyFileAndOwnerClassRule() {
        FileSystem fs = homes();
        fs.chmod(ALICE, "/home/alice/notes.txt", 0444);
        assertThrows(FsException.AccessDenied.class, () -> fs.write(ALICE, "/home/alice/notes.txt", "x"), "even the owner can't write 0444");
        assertThrows(FsException.AccessDenied.class, () -> fs.append(ALICE, "/home/alice/notes.txt", "x"), "nor append");
        assertThrows(FsException.AccessDenied.class, () -> fs.chmod(BOB, "/home/alice/notes.txt", 0666), "only the owner may chmod");
        fs.chmod(ALICE, "/home/alice/notes.txt", 0066);          // owner: nothing; group and others: rw
        assertThrows(FsException.AccessDenied.class, () -> fs.read(ALICE, "/home/alice/notes.txt"), "owner class only: no fall-through to others");
        assertEquals("secret plan", fs.read(BOB, "/home/alice/notes.txt"), "others may read");
        pass("readOnlyFileAndOwnerClassRule");
    }

    static void rootBypassesPermissions() {
        FileSystem fs = homes();
        fs.chmod(ALICE, "/home/alice/notes.txt", 0000);
        fs.chmod(ALICE, "/home/alice", 0000);
        assertThrows(FsException.AccessDenied.class, () -> fs.read(ALICE, "/home/alice/notes.txt"), "owner locked out by 0000");
        assertEquals("secret plan", fs.read(ROOT, "/home/alice/notes.txt"), "root reads anyway (decision: root skips all checks)");
        fs.write(ROOT, "/home/alice/notes.txt", "root was here");
        fs.rm(ROOT, "/home/alice", true);
        assertEquals(List.of(), fs.ls(ROOT, "/home"), "root deleted it all");
        pass("rootBypassesPermissions");
    }

    // =============================================================== concurrency

    /**
     * 8 writer threads each append 500 single bytes to one shared file and create 50 dirs; 4 reader
     * threads run du/ls/find meanwhile. With the read-write lock, no append is lost and no reader
     * sees a broken tree. Assertions are on final counts, so the test is deterministic.
     */
    static void concurrentWritersAndReaders() throws Exception {
        FileSystem fs = new FileSystem();
        fs.mkdir(ROOT, "/shared");
        fs.createFile(ROOT, "/shared/log");
        int writers = 8, readers = 4, appends = 500, dirs = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + readers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int id = w;
            threads.add(new Thread(() -> run(start, done, failure, () -> {
                for (int i = 0; i < appends; i++) fs.append(ROOT, "/shared/log", "x");
                for (int i = 0; i < dirs; i++) fs.mkdir(ROOT, "/shared/w" + id + "-" + i);
            })));
        }
        for (int r = 0; r < readers; r++) {
            threads.add(new Thread(() -> run(start, done, failure, () -> {
                for (int i = 0; i < 300; i++) {
                    long size = fs.du(ROOT, "/shared");
                    if (size < 0 || size > (long) writers * appends) throw new AssertionError("impossible size " + size);
                    fs.ls(ROOT, "/shared");
                    fs.find(ROOT, "/shared", Stat::isDirectory);
                }
            })));
        }
        threads.forEach(Thread::start);
        start.countDown();                                       // release everyone at once
        done.await();
        if (failure.get() != null) throw new AssertionError("a thread failed: " + failure.get(), failure.get());
        assertEquals((long) writers * appends, fs.stat(ROOT, "/shared/log").size(), "no lost appends: 8 x 500");
        assertEquals(writers * dirs + 1, fs.ls(ROOT, "/shared").size(), "400 dirs + the log file");
        pass("concurrentWritersAndReaders");
    }

    static void run(CountDownLatch start, CountDownLatch done, AtomicReference<Throwable> failure, Runnable body) {
        try {
            start.await();
            body.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        } finally {
            done.countDown();
        }
    }

    // =============================================================== property test

    /**
     * 3,000 random mkdir / write / append / rm -r / mv operations (seeded, so a failure replays) on
     * paths built from a tiny alphabet, so names collide and every error path is hit. The same
     * operations run on a trivially correct reference model: a TreeMap from full path to content
     * (DIR for directories). After every step: same success/failure; every 100 steps and at the
     * end: the full listing (path, type, content) must be identical.
     */
    static void randomOperationsMatchReferenceModel() {
        for (long seed : new long[] {1, 2, 42}) {
            Random rnd = new Random(seed);
            FileSystem fs = new FileSystem();
            Model model = new Model();
            for (int step = 0; step < 3_000; step++) {
                String p = randomPath(rnd), q = randomPath(rnd), text = "" + (char) ('a' + rnd.nextInt(3));
                int op = rnd.nextInt(5);
                String what = "seed " + seed + " step " + step + " op " + op + " " + p + " " + q;
                boolean real = succeeds(() -> {
                    switch (op) {
                        case 0 -> fs.mkdir(ROOT, p);
                        case 1 -> fs.write(ROOT, p, text);
                        case 2 -> fs.append(ROOT, p, text);
                        case 3 -> fs.rm(ROOT, p, true);
                        default -> fs.mv(ROOT, p, q);
                    }
                });
                boolean expected = switch (op) {
                    case 0 -> model.mkdir(p);
                    case 1 -> model.write(p, text, false);
                    case 2 -> model.write(p, text, true);
                    case 3 -> model.rm(p);
                    default -> model.mv(p, q);
                };
                assertEquals(expected, real, what + ": success");
                if (step % 100 == 0 || step == 2_999) assertEquals(model.listing(), listing(fs), what + ": tree");
            }
        }
        pass("randomOperationsMatchReferenceModel");
    }

    static String randomPath(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int depth = 1 + rnd.nextInt(3);
        for (int i = 0; i < depth; i++) sb.append('/').append("abc".charAt(rnd.nextInt(3)));
        return sb.toString();
    }

    static Map<String, String> listing(FileSystem fs) {
        Map<String, String> out = new TreeMap<>();
        for (String path : fs.find(ROOT, "/", s -> true)) {
            if (path.equals("/")) continue;
            out.put(path, fs.stat(ROOT, path).isDirectory() ? Model.DIR : fs.read(ROOT, path));
        }
        return out;
    }

    /** The reference model: no tree at all, just "full path -> content". Slow, but obviously right. */
    static final class Model {
        static final String DIR = "<dir>";
        final TreeMap<String, String> entries = new TreeMap<>();

        boolean isDir(String p) { return p.equals("/") || DIR.equals(entries.get(p)); }
        boolean exists(String p) { return p.equals("/") || entries.containsKey(p); }
        static String parent(String p) { int i = p.lastIndexOf('/'); return i == 0 ? "/" : p.substring(0, i); }
        static String name(String p) { return p.substring(p.lastIndexOf('/') + 1); }

        boolean mkdir(String p) {
            if (!isDir(parent(p)) || exists(p)) return false;
            entries.put(p, DIR);
            return true;
        }

        boolean write(String p, String text, boolean append) {
            if (!isDir(parent(p)) || isDir(p)) return false;
            entries.put(p, append && entries.containsKey(p) ? entries.get(p) + text : text);
            return true;
        }

        boolean rm(String p) {
            if (!exists(p)) return false;
            entries.keySet().removeIf(k -> k.equals(p) || k.startsWith(p + "/"));
            return true;
        }

        /** O(n) re-keying of every entry under src: exactly what a flat key space (like S3) must do. */
        boolean mv(String src, String dst) {
            if (!exists(src)) return false;
            String target = isDir(dst) ? (dst.equals("/") ? "" : dst) + "/" + name(src) : dst;
            if (!isDir(parent(target))) return false;
            if (target.equals(src)) return true;
            if (target.startsWith(src + "/")) return false;      // into its own subtree
            if (exists(target) && (isDir(target) || isDir(src))) return false;
            TreeMap<String, String> moved = new TreeMap<>();
            entries.entrySet().removeIf(e -> {
                String k = e.getKey();
                if (!k.equals(src) && !k.startsWith(src + "/")) return false;
                moved.put(target + k.substring(src.length()), e.getValue());
                return true;
            });
            entries.putAll(moved);
            return true;
        }

        Map<String, String> listing() { return new TreeMap<>(entries); }
    }

    // =============================================================== helpers

    static boolean succeeds(Runnable r) {
        try {
            r.run();
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable r, String what) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
