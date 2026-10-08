package fs;

/** Unix-style rwx checks for owner / group / others. */
final class Permissions {
    static final int READ = 4, WRITE = 2, EXEC = 1;

    private Permissions() {}

    /**
     * Linux picks exactly ONE class: owner if you own it, else group if you're in its group, else
     * others. It does not fall through: an owner with mode 0077 is denied even though "others" may.
     */
    static boolean allowed(User u, Node n, int wanted) {
        if (u.isRoot()) return true;
        int bits;
        if (u.name().equals(n.owner)) bits = (n.mode >> 6) & 7;
        else if (u.groups().contains(n.group)) bits = (n.mode >> 3) & 7;
        else bits = n.mode & 7;
        return (bits & wanted) == wanted;
    }

    static void check(User u, Node n, int wanted, String operation) {
        if (!allowed(u, n, wanted))
            throw new FsException.AccessDenied(operation + " '" + n.path() + "' as " + u.name());
    }

    /** 0755 for a directory -> "drwxr-xr-x". */
    static String modeString(char type, int mode) {
        StringBuilder sb = new StringBuilder().append(type);
        String letters = "rwx";
        for (int shift = 6; shift >= 0; shift -= 3)
            for (int i = 0; i < 3; i++)
                sb.append(((mode >> shift) & (4 >> i)) != 0 ? letters.charAt(i) : '-');
        return sb.toString();
    }
}
