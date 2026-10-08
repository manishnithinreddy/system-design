import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A tiny Dynamo-style cluster in one process: a hash ring with virtual nodes, N/W/R quorums,
 * sloppy quorum with hinted handoff, and read repair. Run:  java HashRing.java   (or: java HashRing.java strict)
 * Nothing here is networked or threaded: "down" is a flag and "latency" is a number, so every run is identical.
 */
public class HashRing {
    static final int N = 3, W = 2, R = 2;                  // try changing these
    final int vnodes;
    static boolean sloppy = true;

    record Versioned(String value, long version) {}
    record Hint(Node forNode, String key, Versioned v) {}

    static final class Node {
        final String name;
        boolean up = true;
        int latencyMs = 10;                                // only used to decide who answers a read first
        final Map<String, Versioned> data = new TreeMap<>();
        final List<Hint> hints = new ArrayList<>();
        Node(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    final TreeMap<Integer, Node> ring = new TreeMap<>();  // ring position -> node (each node appears `vnodes` times)
    final Map<String, Node> nodes = new LinkedHashMap<>();
    long clock = 0;                                        // a single version counter stands in for timestamps

    HashRing(int vnodes) { this.vnodes = vnodes; }

    /** FNV-1a then a final bit-mix: same result on every run, and similar strings ("A#1", "A#2") land far apart. */
    static int hash(String s) {
        int h = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) { h ^= (b & 0xff); h *= 0x01000193; }
        h ^= h >>> 16; h *= 0x85ebca6b; h ^= h >>> 13; h *= 0xc2b2ae35; h ^= h >>> 16;   // murmur3 finalizer
        return h & 0x7fffffff;
    }

    void addNode(String name) {
        Node n = new Node(name);
        nodes.put(name, n);
        for (int i = 0; i < vnodes; i++) ring.put(hash(name + "#" + i), n);
    }

    Node node(String name) { return nodes.get(name); }

    /** All distinct physical nodes, walking clockwise from the key's position. The first N are its home replicas. */
    List<Node> walk(String key) {
        LinkedHashSet<Node> seen = new LinkedHashSet<>();
        int pos = hash(key);
        for (Node n : ring.tailMap(pos, true).values()) seen.add(n);
        for (Node n : ring.headMap(pos, false).values()) seen.add(n);
        return new ArrayList<>(seen);
    }

    boolean put(String key, String value) {
        Versioned v = new Versioned(value, ++clock);
        List<Node> walk = walk(key);
        List<Node> home = walk.subList(0, N);
        Iterator<Node> standIns = walk.subList(N, walk.size()).iterator();
        int acks = 0;
        StringBuilder log = new StringBuilder();
        for (Node h : home) {
            if (h.up) { h.data.put(key, v); acks++; log.append(" ").append(h).append(":ok"); continue; }
            log.append(" ").append(h).append(":DOWN");
            if (!sloppy) continue;
            while (standIns.hasNext()) {                   // next healthy node past the home replicas
                Node s = standIns.next();
                if (!s.up) continue;
                s.hints.add(new Hint(h, key, v));          // "this belongs to h; give it back when h returns"
                acks++;
                log.append(" ").append(s).append(":ok(hint for ").append(h).append(")");
                break;
            }
        }
        boolean ok = acks >= W;
        System.out.printf("PUT %s=%s v%d  home=%s  wrote:%s  acks=%d/%d  -> %s%n",
                key, value, v.version(), home, log, acks, W, ok ? "OK" : "FAILED (not enough replicas)");
        return ok;
    }

    /** Asks the healthy home replicas, uses the first R to answer, returns the newest, repairs stale responders. */
    String get(String key) {
        List<Node> asked = walk(key).subList(0, N).stream().filter(n -> n.up)
                .sorted(Comparator.comparingInt((Node n) -> n.latencyMs)).toList();
        if (asked.size() < R) {
            System.out.printf("GET %s  only %d home replicas up, need R=%d -> FAILED%n", key, asked.size(), R);
            return null;
        }
        List<Node> answered = asked.subList(0, R);
        Versioned newest = null;
        StringBuilder log = new StringBuilder();
        for (Node n : answered) {
            Versioned v = n.data.get(key);
            log.append(" ").append(n).append("=").append(v == null ? "missing" : v.value() + "(v" + v.version() + ")");
            if (v != null && (newest == null || v.version() > newest.version())) newest = v;
        }
        System.out.printf("GET %s  first %d answers:%s  -> %s%n", key, R, log, newest == null ? "NOT FOUND" : newest.value());
        if (newest != null) {
            for (Node n : answered) {                      // read repair: fix stale copies we happened to see
                Versioned v = n.data.get(key);
                if (v == null || v.version() < newest.version()) {
                    n.data.put(key, newest);
                    System.out.printf("    read repair: %s now has v%d%n", n, newest.version());
                }
            }
        }
        return newest == null ? null : newest.value();
    }

    /** Each node with hints tries to hand them to their owner (in a real system: a background task every few seconds). */
    void handoff() {
        if (nodes.values().stream().allMatch(n -> n.hints.isEmpty())) { System.out.println("HANDOFF: no hints to deliver"); return; }
        for (Node holder : nodes.values()) {
            if (!holder.up) continue;
            for (Iterator<Hint> it = holder.hints.iterator(); it.hasNext(); ) {
                Hint h = it.next();
                if (!h.forNode().up) continue;
                Versioned cur = h.forNode().data.get(h.key());
                boolean newer = cur == null || cur.version() < h.v().version();
                if (newer) h.forNode().data.put(h.key(), h.v());
                it.remove();
                System.out.printf("HANDOFF %s -> %s: %s v%d%s%n", holder, h.forNode(), h.key(), h.v().version(),
                        newer ? "" : "  (owner already has a newer version: hint discarded)");
            }
        }
    }

    void down(String... names) { for (String n : names) node(n).up = false; System.out.println("-- down: " + String.join(", ", names)); }
    void up(String... names)   { for (String n : names) node(n).up = true;  System.out.println("-- up: " + String.join(", ", names)); }

    /** How evenly do keys spread with this many vnodes, and how many move when a 7th node joins? */
    static void spread(int vnodes) {
        HashRing ring = new HashRing(vnodes);
        for (String n : List.of("A", "B", "C", "D", "E", "F")) ring.addNode(n);
        int keys = 60_000;
        Map<String, Integer> owned = new TreeMap<>();
        Node[] before = new Node[keys];
        for (int i = 0; i < keys; i++) {
            before[i] = ring.walk("user:" + i).get(0);
            owned.merge(before[i].name, 1, Integer::sum);
        }
        ring.addNode("G");
        int moved = 0;
        for (int i = 0; i < keys; i++) if (ring.walk("user:" + i).get(0) != before[i]) moved++;
        int max = Collections.max(owned.values()), min = Collections.min(owned.values());
        System.out.printf("%3d vnodes/node: busiest %5d, quietest %5d (ideal 10,000)  | add G: %5d keys moved = %4.1f%% (ideal 1/7 = 14.3%%)%n",
                vnodes, max, min, moved, moved * 100.0 / keys);
    }

    static void title(String t) { System.out.println("\n=== " + t + " ==="); }

    public static void main(String[] args) {
        sloppy = !(args.length > 0 && args[0].equals("strict"));
        title("1. Where do keys live?  Spread of 60,000 keys over 6 nodes, then add a 7th");
        for (int v : List.of(1, 8, 128)) spread(v);

        HashRing c = new HashRing(128);
        for (String n : List.of("A", "B", "C", "D", "E", "F", "G")) c.addNode(n);
        String key = "cart:42";
        List<Node> walk = c.walk(key);
        Node a = walk.get(0), b = walk.get(1), cc = walk.get(2);
        System.out.println(key + " home replicas: " + walk.subList(0, N) + "   stand-ins in order: " + walk.subList(N, walk.size()));

        title("2. All healthy");
        c.put(key, "[shoes]");
        c.get(key);

        title("3. One home replica down: two healthy home replicas still make W=2");
        c.down(cc.name);
        c.put(key, "[shoes, socks]");
        c.up(cc.name);
        b.latencyMs = 1; cc.latencyMs = 2;                 // pretend b and the returning node answer reads fastest
        c.get(key);                                        // b is new, cc is stale -> newest wins, cc repaired

        title("4. Two home replicas down: " + (sloppy ? "sloppy quorum with hints" : "strict quorum"));
        c.down(b.name, cc.name);
        c.put(key, "[shoes, socks, hat]");

        title("5. They come back BEFORE the hints are delivered");
        c.up(b.name, cc.name);
        String seen = c.get(key);                          // asks b and cc first: neither has the hat
        if (!sloppy) System.out.println("    Not stale: the hat write FAILED in strict mode, so [shoes, socks] really is the latest acknowledged value.");
        else if (seen.contains("hat")) System.out.println("    Fresh this time: a replica holding v3 happened to answer first.");
        else System.out.println("    STALE: R + W > N promised overlap, but 2 of the 3 acks were on stand-ins, not home replicas.");

        title("6. Hinted handoff, then read again");
        c.handoff();
        c.get(key);
    }
}
