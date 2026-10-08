package fs;

/** An immutable snapshot of one node's metadata: what `stat` / `ls -l` show. Safe to hand out. */
public record Stat(String path, String name, char type, long size, int mode,
                   String owner, String group, String linkTarget) {

    static Stat of(Node n) {
        String target = n instanceof SymLink link ? link.target : null;
        return new Stat(n.path(), n.name, n.typeChar(), n.size(), n.mode, n.owner, n.group, target);
    }

    public boolean isDirectory() { return type == 'd'; }
    public boolean isFile() { return type == '-'; }
    public boolean isSymLink() { return type == 'l'; }

    public String modeString() { return Permissions.modeString(type, mode); }

    /** e.g. "drwxr-xr-x  alice  dev       0  logs" or "lrwxrwxrwx ... current -> /var/log/app". */
    public String lsLine() {
        String shown = name.isEmpty() ? "/" : name;
        if (linkTarget != null) shown += " -> " + linkTarget;
        return String.format("%s  %-6s %-6s %6d  %s", modeString(), owner, group, size, shown);
    }
}
