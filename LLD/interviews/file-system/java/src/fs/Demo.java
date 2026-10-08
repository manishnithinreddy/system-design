package fs;

import java.util.List;

/** A short shell-like session: what each command does to the tree, with `ls -l` style output. */
public final class Demo {
    public static void main(String[] args) {
        FileSystem fs = new FileSystem();
        Shell root = new Shell(fs, User.ROOT);
        Shell alice = new Shell(fs, User.of("alice", "dev"));
        Shell bob = new Shell(fs, User.of("bob", "ops"));

        say("root", "mkdir -p /var/log/app /home/alice");
        root.mkdirs("/var/log/app");
        root.mkdirs("/home/alice");
        fs.chown(User.ROOT, "/home/alice", "alice", "dev");
        fs.chown(User.ROOT, "/var/log/app", "alice", "dev");

        say("alice", "cd /home/alice");
        alice.cd("/home/alice");
        say("alice", "echo 'hello' > notes.txt ; echo ' world' >> notes.txt ; mkdir projects");
        alice.write("notes.txt", "hello");
        alice.append("notes.txt", " world");
        alice.mkdir("projects");
        say("alice", "echo 'started' > /var/log/app/app-2026-10-08.log");
        alice.write("/var/log/app/app-2026-10-08.log", "started\n");
        say("alice", "ln -s /var/log/app logs");
        alice.ln("/var/log/app", "logs");
        say("alice", "ls -l");
        lsLong(alice.lsLong("."));
        say("alice", "cat logs/app-2026-10-08.log");
        System.out.print("  " + alice.cat("logs/app-2026-10-08.log"));

        say("alice", "mv projects projects/inner   # a folder into itself");
        try {
            alice.mv("projects", "projects/inner");
        } catch (FsException e) {
            System.out.println("  mv: " + e.code() + ": " + e.getMessage());
        }

        say("alice", "chmod 700 /home/alice");
        alice.chmod("/home/alice", 0700);
        say("bob", "cat /home/alice/notes.txt");
        try {
            bob.cat("/home/alice/notes.txt");
        } catch (FsException e) {
            System.out.println("  cat: " + e.code() + ": " + e.getMessage());
        }
        say("root", "cat /home/alice/notes.txt   # root skips permission checks");
        System.out.println("  " + root.cat("/home/alice/notes.txt"));

        say("root", "ln -s /loop-b /loop-a ; ln -s /loop-a /loop-b ; cat /loop-a");
        root.ln("/loop-b", "/loop-a");
        root.ln("/loop-a", "/loop-b");
        try {
            root.cat("/loop-a");
        } catch (FsException e) {
            System.out.println("  cat: " + e.code() + ": too many levels of symbolic links (" + e.getMessage() + ")");
        }

        say("root", "du -sb /home /var");
        System.out.println("  " + root.du("/home") + "\t/home");
        System.out.println("  " + root.du("/var") + "\t/var");
        say("root", "find / -name '*.log'");
        root.find("/", s -> s.isFile() && s.name().endsWith(".log")).forEach(p -> System.out.println("  " + p));

        say("root", "rm /home   # without -r");
        try {
            root.rm("/home", false);
        } catch (FsException e) {
            System.out.println("  rm: " + e.code() + ": " + e.getMessage());
        }
        say("root", "mv /home/alice /users-alice ; ls -l /");
        root.mv("/home/alice", "/users-alice");
        lsLong(root.lsLong("/"));
    }

    static void say(String who, String command) {
        System.out.println(who + "$ " + command);
    }

    static void lsLong(List<Stat> entries) {
        for (Stat s : entries) System.out.println("  " + s.lsLine());
    }
}
