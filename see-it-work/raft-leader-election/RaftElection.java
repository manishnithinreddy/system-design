import java.util.*;

/**
 * Raft leader election only (no log replication), simulated in one process with simulated time.
 * Five nodes, a fake network with 5 ms delay, random election timeouts from a fixed seed: every run is identical.
 * Run:  java RaftElection.java          (the full story)
 *       java RaftElection.java fixed    (every node uses the SAME timeout: watch split votes repeat forever)
 */
public class RaftElection {
    enum Role { FOLLOWER, CANDIDATE, LEADER }

    static final int NODES = 5, MAJORITY = NODES / 2 + 1;
    static final int HEARTBEAT_MS = 50, NET_DELAY_MS = 5, TIMEOUT_MIN = 150, TIMEOUT_MAX = 300;
    static boolean fixedTimeouts = false;

    sealed interface Msg permits RequestVote, Vote, Heartbeat, HeartbeatAck {}
    record RequestVote(int term, int from) implements Msg {}
    record Vote(int term, int from, boolean granted) implements Msg {}
    record Heartbeat(int term, int from) implements Msg {}
    record HeartbeatAck(int term, int from) implements Msg {}

    record Event(long at, long seq, Runnable action) {}

    final PriorityQueue<Event> events = new PriorityQueue<>(
            Comparator.comparingLong(Event::at).thenComparingLong(Event::seq));
    final Random random = new Random(7);
    final List<Node> nodes = new ArrayList<>();
    final int[] partition = new int[NODES];        // nodes talk only if they're in the same group
    long now = 0, seq = 0;

    void at(long time, Runnable r) { events.add(new Event(time, seq++, r)); }

    void runFor(long ms) {
        long until = now + ms;
        while (!events.isEmpty() && events.peek().at() <= until) {
            Event e = events.poll();
            now = e.at();
            e.action().run();
        }
        now = until;
    }

    void log(String s) { System.out.printf("[%5d ms] %s%n", now, s); }

    /** The network: delivers after a delay, only if both ends are running and in the same partition. */
    void send(Node from, int to, Msg m) {
        if (!from.running) return;
        at(now + NET_DELAY_MS, () -> {
            Node dst = nodes.get(to);
            if (dst.running && from.running && partition[from.id] == partition[to]) dst.handle(m);
        });
    }

    final class Node {
        final int id;
        Role role = Role.FOLLOWER;
        int term = 0;                 // Raft: currentTerm (would be persisted to disk)
        int votedFor = -1;            // Raft: votedFor in this term (would be persisted to disk)
        boolean running = true;
        long timerEpoch = 0;          // bumping it cancels the previous election timer
        final Set<Integer> votes = new TreeSet<>();
        final Set<Integer> acks = new TreeSet<>();
        boolean warnedNoMajority = false;
        int roundsAsLeader = 0;       // acks are judged from the second heartbeat round on

        Node(int id) { this.id = id; }

        @Override public String toString() { return "n" + id; }

        void resetElectionTimer() {
            long epoch = ++timerEpoch;
            int timeout = fixedTimeouts ? TIMEOUT_MIN
                    : TIMEOUT_MIN + random.nextInt(TIMEOUT_MAX - TIMEOUT_MIN + 1);
            at(now + timeout, () -> { if (running && epoch == timerEpoch && role != Role.LEADER) startElection(); });
        }

        void startElection() {
            term++;
            role = Role.CANDIDATE;
            votedFor = id;
            votes.clear();
            votes.add(id);
            log(this + ": no heartbeat -> CANDIDATE for term " + term + ", asks everyone for votes");
            for (int other = 0; other < NODES; other++) if (other != id) send(this, other, new RequestVote(term, id));
            resetElectionTimer();     // if this election fails (split vote), try again after a new random timeout
        }

        void becomeLeader() {
            role = Role.LEADER;
            log(this + ": LEADER for term " + term + " (votes from " + names(votes) + ")");
            acks.clear();
            warnedNoMajority = false;
            roundsAsLeader = 0;
            heartbeatLoop();
        }

        void heartbeatLoop() {
            if (!running || role != Role.LEADER) return;
            // A leader that hasn't heard from a majority since the last round can't commit anything.
            if (roundsAsLeader++ > 0 && acks.size() + 1 < MAJORITY && !warnedNoMajority) {
                log(this + ": still thinks it is LEADER (term " + term + ") but only " + (acks.size() + 1)
                        + "/" + NODES + " nodes answer -> could NOT commit any write");
                warnedNoMajority = true;
            }
            acks.clear();
            for (int other = 0; other < NODES; other++) if (other != id) send(this, other, new Heartbeat(term, id));
            at(now + HEARTBEAT_MS, this::heartbeatLoop);
        }

        /** Rule used everywhere in Raft: seeing a higher term means you are out of date. */
        void stepDownIfNewer(int seenTerm, Node from) {
            if (seenTerm <= term) return;
            if (role != Role.FOLLOWER) log(this + ": sees term " + seenTerm + " from " + from + " -> steps down to FOLLOWER");
            term = seenTerm;
            role = Role.FOLLOWER;
            votedFor = -1;
            resetElectionTimer();
        }

        void handle(Msg m) {
            switch (m) {
                case RequestVote rv -> {
                    Node c = nodes.get(rv.from());
                    stepDownIfNewer(rv.term(), c);
                    boolean grant = rv.term() == term && (votedFor == -1 || votedFor == rv.from());
                    // (Real Raft also refuses candidates whose log is behind; this simulation has no log.)
                    if (grant) { votedFor = rv.from(); resetElectionTimer(); log("  " + this + " votes for " + c + " in term " + term); }
                    send(this, rv.from(), new Vote(term, id, grant));
                }
                case Vote v -> {
                    stepDownIfNewer(v.term(), nodes.get(v.from()));
                    if (role == Role.CANDIDATE && v.term() == term && v.granted()) {
                        votes.add(v.from());
                        if (votes.size() >= MAJORITY) becomeLeader();
                    }
                }
                case Heartbeat hb -> {
                    Node leader = nodes.get(hb.from());
                    if (hb.term() < term) { send(this, hb.from(), new HeartbeatAck(term, id)); return; }  // stale leader: tell it
                    if (hb.term() > term || role != Role.FOLLOWER) {
                        if (role != Role.FOLLOWER) log(this + ": heartbeat from " + leader + " (term " + hb.term() + ") -> FOLLOWER");
                        if (hb.term() > term) votedFor = -1;   // new term: no vote cast in it yet
                        term = hb.term();
                        role = Role.FOLLOWER;
                    }
                    resetElectionTimer();
                    send(this, hb.from(), new HeartbeatAck(term, id));
                }
                case HeartbeatAck a -> {
                    stepDownIfNewer(a.term(), nodes.get(a.from()));
                    if (role == Role.LEADER && a.term() == term) acks.add(a.from());
                }
            }
        }

        void pause()  { running = false; log("-- " + this + " PAUSED (think: a 2-second GC pause or a frozen VM); it still believes it is " + role); }
        void resume() {
            running = true;
            log("-- " + this + " RESUMED, still believing it is " + role + " of term " + term);
            if (role == Role.LEADER) { acks.clear(); warnedNoMajority = false; roundsAsLeader = 0; heartbeatLoop(); } else resetElectionTimer();
        }
    }

    String names(Collection<Integer> ids) { return ids.stream().map(i -> "n" + i).toList().toString(); }

    Node leader() {
        return nodes.stream().filter(n -> n.running && n.role == Role.LEADER).max(Comparator.comparingInt(n -> n.term)).orElse(null);
    }

    void status() {
        StringBuilder sb = new StringBuilder("   status:");
        for (Node n : nodes) sb.append(String.format("  %s=%s/t%d%s", n, n.role.name().charAt(0), n.term, n.running ? "" : "(paused)"));
        System.out.println(sb + "   (F/C/L = follower/candidate/leader, t = term)");
    }

    static void title(String t) { System.out.println("\n=== " + t + " ==="); }

    public static void main(String[] args) {
        fixedTimeouts = args.length > 0 && args[0].equals("fixed");
        RaftElection sim = new RaftElection();
        for (int i = 0; i < NODES; i++) sim.nodes.add(sim.new Node(i));
        sim.nodes.forEach(Node::resetElectionTimer);

        title("1. Cold start: everyone is a follower, the first timeout wins");
        sim.runFor(fixedTimeouts ? 1000 : 600);
        sim.status();
        if (fixedTimeouts) { System.out.println("\nSame timeouts -> same moment -> everyone votes for itself -> split vote, forever."); return; }

        Node first = sim.leader();
        title("2. The leader freezes: followers stop hearing heartbeats and elect a new one");
        first.pause();
        sim.runFor(600);
        sim.status();

        title("3. The old leader wakes up with an old term and steps down");
        first.resume();
        sim.runFor(300);
        sim.status();

        Node second = sim.leader();
        Node buddy = sim.nodes.stream().filter(n -> n != second).findFirst().orElseThrow();
        title("4. Network partition: {" + second + ", " + buddy + "} cut off from the other three");
        for (Node n : sim.nodes) sim.partition[n.id] = (n == second || n == buddy) ? 1 : 2;
        sim.runFor(800);
        sim.status();

        title("5. The partition heals: the higher term wins, one leader again");
        Arrays.fill(sim.partition, 0);
        sim.runFor(300);
        sim.status();
    }
}
