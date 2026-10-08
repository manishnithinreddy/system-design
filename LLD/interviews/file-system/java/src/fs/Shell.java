package fs;

import java.util.List;
import java.util.function.Predicate;

/**
 * One user's terminal session: a user plus a current directory. Turns relative paths into
 * absolute ones and forwards to the shared FileSystem. Each thread should use its own Shell
 * (the cwd is per-session state, like each terminal tab has its own `pwd`).
 */
public final class Shell {
    private final FileSystem fs;
    private final User user;
    private String cwd = "/";

    public Shell(FileSystem fs, User user) {
        this.fs = fs;
        this.user = user;
    }

    public String pwd() { return cwd; }

    /** `cd path`: must be a directory we may enter. Stores the physical path (like `pwd -P`). */
    public void cd(String path) {
        String target = fs.realPath(user, abs(path));
        if (!fs.stat(user, target).isDirectory()) throw new FsException.NotADirectory(path);
        fs.ls(user, target);   // throws if we may not read it; real cd only needs x, kept simple here
        cwd = target;
    }

    public void mkdir(String p) { fs.mkdir(user, abs(p)); }
    public void mkdirs(String p) { fs.mkdirs(user, abs(p)); }
    public void touch(String p) { fs.createFile(user, abs(p)); }
    public void write(String p, String text) { fs.write(user, abs(p), text); }
    public void append(String p, String text) { fs.append(user, abs(p), text); }
    public String cat(String p) { return fs.read(user, abs(p)); }
    public List<String> ls(String p) { return fs.ls(user, abs(p)); }
    public List<Stat> lsLong(String p) { return fs.lsLong(user, abs(p)); }
    public void rm(String p, boolean recursive) { fs.rm(user, abs(p), recursive); }
    public void mv(String src, String dst) { fs.mv(user, abs(src), abs(dst)); }
    public void ln(String target, String link) { fs.symlink(user, target, abs(link)); }
    public long du(String p) { return fs.du(user, abs(p)); }
    public List<String> find(String p, Predicate<Stat> filter) { return fs.find(user, abs(p), filter); }
    public void chmod(String p, int mode) { fs.chmod(user, abs(p), mode); }

    private String abs(String path) { return Path.join(cwd, path); }
}
