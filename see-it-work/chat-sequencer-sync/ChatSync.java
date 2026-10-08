import java.util.*;

/**
 * A chat server's per-conversation sequencer + devices that detect gaps, ignore duplicates and sync after being offline.
 * Everything runs in one process with a fake, scriptable network, so the output is identical on every run.
 * Run:  java ChatSync.java
 */
public class ChatSync {
    static boolean quiet = false;                                    // the chaos phase prints counters instead of every line
    static void log(String fmt, Object... a) { if (!quiet) System.out.printf(fmt, a); }
    record Message(long seq, String clientMsgId, String from, String text) {}

    /** The server: one sequencer per conversation, an append-only log, dedup by client message id. */
    static final class Server {
        final List<Message> log = new ArrayList<>();                 // index i holds seq i+1
        final Map<String, Long> seqByClientId = new HashMap<>();
        final List<Device> online = new ArrayList<>();

        long send(String clientMsgId, String from, String text, Network net) {
            Long existing = seqByClientId.get(clientMsgId);
            if (existing != null) {                                  // a retry of something we already stored
                log("  server: %s is a retry, already stored as seq %d (not stored again)%n", clientMsgId, existing);
                return existing;
            }
            Message m = new Message(log.size() + 1, clientMsgId, from, text);
            log.add(m);
            seqByClientId.put(clientMsgId, m.seq());
            for (Device d : online) net.deliver(m, d);              // push; the network may lose/duplicate/reorder
            return m.seq();
        }

        /** "Give me what comes after seq N", in pages. Devices call this to fill gaps and after being offline. */
        List<Message> since(long afterSeq, int limit) {
            int from = (int) Math.min(afterSeq, log.size());
            return List.copyOf(log.subList(from, Math.min(from + limit, log.size())));
        }
    }

    /** A phone or laptop: applies messages strictly in seq order, buffers early ones, ignores duplicates. */
    static final class Device {
        final String name;
        final Server server;
        final List<Message> shown = new ArrayList<>();
        final TreeMap<Long, Message> early = new TreeMap<>();       // arrived before a missing one
        long lastSeq = 0;
        int gaps = 0, duplicates = 0;

        Device(String name, Server server) { this.name = name; this.server = server; }

        void receive(Message m) {
            if (m.seq() <= lastSeq || early.containsKey(m.seq())) {
                duplicates++;
                log("  %s: got seq %d again -> duplicate, ignored%n", name, m.seq());
                return;
            }
            if (m.seq() > lastSeq + 1) {
                gaps++;
                early.put(m.seq(), m);
                log("  %s: got seq %d but expected %d -> GAP, buffer it and ask the server for %d..%d%n",
                        name, m.seq(), lastSeq + 1, lastSeq + 1, m.seq() - 1);
                for (Message missing : server.since(lastSeq, (int) (m.seq() - 1 - lastSeq))) apply(missing, "fetched");
                drainEarly();
                return;
            }
            apply(m, "pushed");
            drainEarly();
        }

        void drainEarly() {
            while (!early.isEmpty() && early.firstKey() == lastSeq + 1) apply(early.pollFirstEntry().getValue(), "from buffer");
        }

        void apply(Message m, String how) {
            if (m.seq() != lastSeq + 1) return;                      // already have it (a fetch and a push raced)
            shown.add(m);
            lastSeq = m.seq();
            log("  %s: shows #%d %s: \"%s\" (%s)%n", name, m.seq(), m.from(), m.text(), how);
        }

        /** Reconnect after being offline: page through everything after lastSeq. */
        void syncAfterReconnect(int pageSize) {
            log("  %s: reconnects with lastSeq=%d, syncs in pages of %d%n", name, lastSeq, pageSize);
            while (true) {
                List<Message> page = server.since(lastSeq, pageSize);
                if (page.isEmpty()) break;
                log("  %s: page of %d (seq %d..%d)%n", name, page.size(), page.get(0).seq(), page.get(page.size() - 1).seq());
                for (Message m : page) apply(m, "synced");
            }
            drainEarly();
        }

        String texts() { return shown.stream().map(m -> m.seq() + ":" + m.text()).toList().toString(); }
    }

    /** The network between server and devices. Scripted faults for the story, random faults for the stress test. */
    static final class Network {
        final Set<Long> dropNext = new HashSet<>(), duplicateNext = new HashSet<>();
        final Map<Long, Message> held = new HashMap<>();               // delayed messages (to reorder)
        final Set<Long> delayNext = new HashSet<>();
        Random chaos = null;
        final List<Runnable> chaosQueue = new ArrayList<>();

        void deliver(Message m, Device d) {
            if (chaos != null) {                                     // random mode: drop 10%, duplicate 10%, shuffle order
                if (chaos.nextInt(10) == 0) return;
                chaosQueue.add(() -> d.receive(m));
                if (chaos.nextInt(10) == 0) chaosQueue.add(() -> d.receive(m));
                return;
            }
            if (dropNext.remove(m.seq())) { log("  network: seq %d to %s LOST%n", m.seq(), d.name); return; }
            if (delayNext.remove(m.seq())) { log("  network: seq %d to %s DELAYED%n", m.seq(), d.name); held.put(m.seq(), m); return; }
            d.receive(m);
            if (duplicateNext.remove(m.seq())) { log("  network: seq %d to %s DELIVERED TWICE%n", m.seq(), d.name); d.receive(m); }
        }

        void releaseDelayed(Device d) { held.values().forEach(d::receive); held.clear(); }

        void flushChaos(Random r) { Collections.shuffle(chaosQueue, r); chaosQueue.forEach(Runnable::run); chaosQueue.clear(); }
    }

    static void title(String t) { System.out.println("\n=== " + t + " ==="); }

    public static void main(String[] args) {
        Server server = new Server();
        Network net = new Network();
        Device phone = new Device("bob-phone", server), laptop = new Device("bob-laptop", server);
        server.online.add(phone);
        server.online.add(laptop);
        int[] counter = {0};
        java.util.function.Function<String, Long> alice = text -> server.send("alice-" + (++counter[0]), "alice", text, net);

        title("1. Normal delivery: the server numbers every message");
        alice.apply("hi Bob");
        alice.apply("are you coming tonight?");

        title("2. Bob's laptop goes offline (it will catch up later)");
        server.online.remove(laptop);
        System.out.println("  bob-laptop: offline");

        title("3. A message is lost, so the next one reveals a gap");
        net.dropNext.add(3L);
        alice.apply("dinner at 8");
        alice.apply("at the usual place");

        title("4. Reordering and duplicates");
        net.delayNext.add(5L);
        alice.apply("bring the cake");
        alice.apply("and candles");
        net.releaseDelayed(phone);
        net.duplicateNext.add(7L);
        alice.apply("see you!");

        title("5. Alice's app never got the ack, so it retries the same message");
        long first = server.send("alice-99", "alice", "running 10 min late", net);
        long retry = server.send("alice-99", "alice", "running 10 min late", net);
        System.out.printf("  alice: first attempt -> seq %d, retry -> seq %d (same message, stored once)%n", first, retry);

        title("6. The laptop comes back online and syncs");
        laptop.syncAfterReconnect(3);
        server.online.add(laptop);

        title("7. Chaos: 200 messages, 10% lost, 10% duplicated, all shuffled");
        Random r = new Random(42);
        net.chaos = r;
        quiet = true;
        int gapsBefore = phone.gaps + laptop.gaps, dupsBefore = phone.duplicates + laptop.duplicates;
        for (int i = 0; i < 200; i++) alice.apply("bulk message " + i);
        net.flushChaos(r);
        System.out.printf("  before final sync: phone lastSeq=%d, laptop lastSeq=%d, server has %d%n",
                phone.lastSeq, laptop.lastSeq, server.log.size());
        phone.syncAfterReconnect(100);                               // the periodic/foreground "anything newer?" check
        laptop.syncAfterReconnect(100);
        quiet = false;
        System.out.printf("  during chaos the devices detected %d gaps and ignored %d duplicates%n",
                phone.gaps + laptop.gaps - gapsBefore, phone.duplicates + laptop.duplicates - dupsBefore);

        title("Result");
        boolean same = phone.shown.equals(server.log) && laptop.shown.equals(server.log);
        System.out.printf("  server: %d messages; phone: %d; laptop: %d; identical and in order: %s%n",
                server.log.size(), phone.shown.size(), laptop.shown.size(), same);
    }
}
