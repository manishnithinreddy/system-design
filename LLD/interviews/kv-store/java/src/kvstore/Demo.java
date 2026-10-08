package kvstore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** A short session, then a "restart" that recovers from the log. */
public final class Demo {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempDirectory("kvdemo").resolve("appendonly.log");
        String[] script = {
            "SET balance:alice 100", "SET balance:bob 50",
            "BEGIN", "SET balance:alice 70", "SET balance:bob 80", "COMMIT",       // a transfer
            "BEGIN", "SET balance:alice 0", "ROLLBACK",                             // a mistake, undone
            "GET balance:alice", "COUNT 80", "ROLLBACK"
        };
        try (WriteAheadLog log = new WriteAheadLog(file, FsyncPolicy.ALWAYS, Clock.systemUTC())) {
            CommandProcessor cli = new CommandProcessor(new KeyValueStore(Clock.systemUTC(), log));
            for (String cmd : script) System.out.printf("> %-24s %s%n", cmd, cli.execute(cmd));
        }
        System.out.println("\n-- log file contents --");
        Files.readAllLines(file).forEach(l -> System.out.println("   " + l.replace("\t", " | ")));
        System.out.println("\n-- restart: replaying the log --");
        try (WriteAheadLog log = new WriteAheadLog(file, FsyncPolicy.ALWAYS, Clock.systemUTC())) {
            CommandProcessor cli = new CommandProcessor(new KeyValueStore(Clock.systemUTC(), log));
            for (String cmd : new String[]{"GET balance:alice", "GET balance:bob"}) {
                System.out.printf("> %-24s %s%n", cmd, cli.execute(cmd));
            }
        }
    }
}
