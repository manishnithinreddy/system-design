package lrucache;

/** Watch an LRU cache of 3 "recently opened files" work, like an editor's Recent Files menu. */
public final class Demo {
    public static void main(String[] args) {
        LruCache<String, String> recent = new LruCache<>(3, null, java.time.Clock.systemUTC(),
                (key, value, cause) -> System.out.println("   evicted " + key + " (" + cause + ")"));

        String[] ops = {"put Main.java", "put Utils.java", "put README.md", "get Main.java", "put pom.xml", "get Utils.java"};
        for (String op : ops) {
            String[] p = op.split(" ");
            if (p[0].equals("put")) {
                recent.put(p[1], "<contents of " + p[1] + ">");
            } else {
                System.out.println("   get " + p[1] + " -> " + (recent.get(p[1]).isPresent() ? "HIT" : "MISS"));
            }
            System.out.printf("%-16s most recent first: %s%n", op, recent.keysMostRecentFirst());
        }
        System.out.printf("hit rate: %.0f%%%n", recent.stats().hitRate() * 100);
    }
}
