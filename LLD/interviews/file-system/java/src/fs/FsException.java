package fs;

/**
 * One exception type per failure, named after the Linux error code (errno) it mirrors, so callers
 * can catch exactly the case they handle. Unchecked: most callers can't do anything but report it.
 */
public abstract sealed class FsException extends RuntimeException {
    FsException(String message) { super(message); }

    /** The Linux errno name, for messages like the shell prints. */
    public abstract String code();

    public static final class NoSuchFile extends FsException {
        NoSuchFile(String m) { super(m); }
        public String code() { return "ENOENT"; }
    }
    public static final class NotADirectory extends FsException {
        NotADirectory(String m) { super(m); }
        public String code() { return "ENOTDIR"; }
    }
    public static final class IsADirectory extends FsException {
        IsADirectory(String m) { super(m); }
        public String code() { return "EISDIR"; }
    }
    public static final class AlreadyExists extends FsException {
        AlreadyExists(String m) { super(m); }
        public String code() { return "EEXIST"; }
    }
    public static final class DirectoryNotEmpty extends FsException {
        DirectoryNotEmpty(String m) { super(m); }
        public String code() { return "ENOTEMPTY"; }
    }
    public static final class AccessDenied extends FsException {
        AccessDenied(String m) { super(m); }
        public String code() { return "EACCES"; }
    }
    /** Too many symlinks followed while resolving one path: almost always a loop. */
    public static final class TooManyLinks extends FsException {
        TooManyLinks(String m) { super(m); }
        public String code() { return "ELOOP"; }
    }
    /** Bad path or impossible operation, e.g. moving a directory into its own subtree. */
    public static final class InvalidOperation extends FsException {
        InvalidOperation(String m) { super(m); }
        public String code() { return "EINVAL"; }
    }
}
