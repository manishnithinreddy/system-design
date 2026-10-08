package kvstore;

/**
 * Text protocol on top of the store, like a tiny redis-cli:
 *   SET k v | GET k | DELETE k | COUNT v | EXPIRE k seconds | TTL k | BEGIN | ROLLBACK | COMMIT
 */
public final class CommandProcessor {
    private final KeyValueStore store;

    public CommandProcessor(KeyValueStore store) {
        this.store = store;
    }

    public String execute(String line) {
        String[] p = line.trim().split("\\s+");
        try {
            return switch (p[0].toUpperCase()) {
                case "SET" -> { need(p, 3); store.set(p[1], p[2]); yield "OK"; }
                case "GET" -> { need(p, 2); yield store.get(p[1]).orElse("NULL"); }
                case "DELETE" -> { need(p, 2); yield store.delete(p[1]) ? "1" : "0"; }
                case "COUNT" -> { need(p, 2); yield String.valueOf(store.count(p[1])); }
                case "EXPIRE" -> { need(p, 3); yield store.expire(p[1], Long.parseLong(p[2])) ? "1" : "0"; }
                case "TTL" -> { need(p, 2); yield String.valueOf(store.ttl(p[1])); }
                case "BEGIN" -> { store.begin(); yield "OK"; }
                case "ROLLBACK" -> { store.rollback(); yield "OK"; }
                case "COMMIT" -> { store.commit(); yield "OK"; }
                default -> "ERR unknown command " + p[0];
            };
        } catch (NoTransactionException e) {
            return "NO TRANSACTION";
        } catch (IllegalArgumentException e) {
            return "ERR " + e.getMessage();
        }
    }

    private static void need(String[] p, int n) {
        if (p.length != n) throw new IllegalArgumentException("wrong number of arguments for " + p[0]);
    }
}
