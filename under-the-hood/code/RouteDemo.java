import java.util.*;

/** Dijkstra vs A* vs bidirectional Dijkstra on a seeded 200x200 grid "road map". JDK only. */
public class RouteDemo {
    static final int N = 200, V = N * N;
    static int[] right = new int[V], down = new int[V]; // edge weight to the right / down neighbour
    static int settled;

    static int[][] neighbours(int v) { // {node, weight} pairs
        int r = v / N, c = v % N;
        List<int[]> l = new ArrayList<>(4);
        if (c + 1 < N) l.add(new int[]{v + 1, right[v]});
        if (c > 0) l.add(new int[]{v - 1, right[v - 1]});
        if (r + 1 < N) l.add(new int[]{v + N, down[v]});
        if (r > 0) l.add(new int[]{v - N, down[v - N]});
        return l.toArray(new int[0][]);
    }

    static int h(int v, int t) { // straight-line-ish lower bound: min edge cost (10) * Manhattan distance
        return 10 * (Math.abs(v / N - t / N) + Math.abs(v % N - t % N));
    }

    /** Dijkstra when useH=false, A* when true. */
    static long search(int s, int t, boolean useH) {
        long[] dist = new long[V];
        Arrays.fill(dist, Long.MAX_VALUE);
        boolean[] done = new boolean[V];
        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        dist[s] = 0;
        pq.add(new long[]{useH ? h(s, t) : 0, s});
        settled = 0;
        while (!pq.isEmpty()) {
            int u = (int) pq.poll()[1];
            if (done[u]) continue;
            done[u] = true;
            settled++;
            if (u == t) return dist[u];
            for (int[] e : neighbours(u)) {
                long nd = dist[u] + e[1];
                if (nd < dist[e[0]]) {
                    dist[e[0]] = nd;
                    pq.add(new long[]{nd + (useH ? h(e[0], t) : 0), e[0]});
                }
            }
        }
        return -1;
    }

    /** Two Dijkstras, one from each end, stop when the two frontiers can no longer improve the best meeting. */
    static long bidirectional(int s, int t) {
        long[][] dist = new long[2][V];
        for (long[] d : dist) Arrays.fill(d, Long.MAX_VALUE);
        boolean[][] done = new boolean[2][V];
        List<PriorityQueue<long[]>> pq = List.of(new PriorityQueue<>(Comparator.comparingLong((long[] a) -> a[0])),
                new PriorityQueue<>(Comparator.comparingLong((long[] a) -> a[0])));
        dist[0][s] = 0; dist[1][t] = 0;
        pq.get(0).add(new long[]{0, s}); pq.get(1).add(new long[]{0, t});
        long best = Long.MAX_VALUE;
        settled = 0;
        while (!pq.get(0).isEmpty() && !pq.get(1).isEmpty()) {
            if (pq.get(0).peek()[0] + pq.get(1).peek()[0] >= best) break;
            int side = pq.get(0).size() <= pq.get(1).size() ? 0 : 1; // expand the smaller frontier
            int u = (int) pq.get(side).poll()[1];
            if (done[side][u]) continue;
            done[side][u] = true;
            settled++;
            for (int[] e : neighbours(u)) {
                long nd = dist[side][u] + e[1];
                if (nd < dist[side][e[0]]) {
                    dist[side][e[0]] = nd;
                    pq.get(side).add(new long[]{nd, e[0]});
                }
                if (dist[1 - side][e[0]] != Long.MAX_VALUE) best = Math.min(best, nd + dist[1 - side][e[0]]);
            }
        }
        return best;
    }

    public static void main(String[] a) {
        Random rnd = new Random(42);
        for (int v = 0; v < V; v++) { right[v] = 10 + rnd.nextInt(3); down[v] = 10 + rnd.nextInt(3); } // 10..20
        System.out.printf("Graph: %d x %d grid = %,d nodes, edge cost 10..12%n", N, N, V);
        int s = 0, t = V - 1;
        System.out.println("Route: top-left corner to bottom-right corner\n");
        long c1 = search(s, t, false); int s1 = settled;
        long c2 = search(s, t, true);  int s2 = settled;
        long c3 = bidirectional(s, t); int s3 = settled;
        System.out.printf("%-22s cost=%d  nodes settled=%,d (%.1f%% of map)%n", "Dijkstra", c1, s1, 100.0 * s1 / V);
        System.out.printf("%-22s cost=%d  nodes settled=%,d (%.1f%% of map)%n", "A* (10 x Manhattan)", c2, s2, 100.0 * s2 / V);
        System.out.printf("%-22s cost=%d  nodes settled=%,d (%.1f%% of map)%n", "Bidirectional", c3, s3, 100.0 * s3 / V);
        System.out.println(c1 == c2 && c2 == c3 ? "\nAll three agree on the cost." : "\nMISMATCH!");
        // short trip for contrast
        int m = 100 * N + 100, m2 = 105 * N + 108;
        System.out.printf("%nShort trip (13 blocks apart): Dijkstra settles %,d, A* settles %,d%n",
                (search(m, m2, false) >= 0 ? settled : 0), (search(m, m2, true) >= 0 ? settled : 0));
    }
}
