import java.util.*;

/**
 * Why databases use B-trees: compare "nodes visited per lookup" (each node = one disk page read)
 * for a binary search tree vs B-trees of growing fan-out, on the same 1,000,000 random keys.
 * Run: java BTreeHeight.java
 */
public class BTreeHeight {
    static final int N = 1_000_000;
    static final int LOOKUPS = 100_000;

    // ---------- A plain B-tree (CLRS style). maxChildren = 2t, each node holds up to 2t-1 keys. ----------
    static final class BTree {
        final int t;                       // minimum degree: every node except the root has t-1 .. 2t-1 keys
        Node root;
        int nodeCount = 1;

        final class Node {
            final long[] keys = new long[2 * t - 1];
            final Node[] kids;
            int n;                         // keys in use
            final boolean leaf;
            Node(boolean leaf) { this.leaf = leaf; kids = leaf ? null : new Node[2 * t]; }
        }

        BTree(int maxChildren) { this.t = maxChildren / 2; root = new Node(true); }

        void insert(long k) {
            if (root.n == 2 * t - 1) {     // root full: split it, tree grows ONE level taller, at the top
                Node r = new Node(false); nodeCount++;
                r.kids[0] = root; root = r;
                split(r, 0);
            }
            Node x = root;
            while (!x.leaf) {              // walk down, splitting any full child before entering it
                int i = upperBound(x, k);
                if (x.kids[i].n == 2 * t - 1) { split(x, i); if (k > x.keys[i]) i++; }
                x = x.kids[i];
            }
            int i = upperBound(x, k);
            System.arraycopy(x.keys, i, x.keys, i + 1, x.n - i);
            x.keys[i] = k; x.n++;
        }

        /** Child i of parent is full: move its upper half to a new sibling, push the median key up. */
        void split(Node parent, int i) {
            Node full = parent.kids[i], right = new Node(full.leaf); nodeCount++;
            right.n = t - 1;
            System.arraycopy(full.keys, t, right.keys, 0, t - 1);
            if (!full.leaf) System.arraycopy(full.kids, t, right.kids, 0, t);
            full.n = t - 1;
            System.arraycopy(parent.kids, i + 1, parent.kids, i + 2, parent.n - i);
            System.arraycopy(parent.keys, i, parent.keys, i + 1, parent.n - i);
            parent.kids[i + 1] = right;
            parent.keys[i] = full.keys[t - 1];
            parent.n++;
        }

        /** Returns how many nodes (= page reads) the search touched, or -1 if not found. */
        int nodesVisited(long k) {
            Node x = root;
            for (int visited = 1; ; visited++) {
                int i = Arrays.binarySearch(x.keys, 0, x.n, k);   // search INSIDE the page: cheap, it's in memory
                if (i >= 0) return visited;
                if (x.leaf) return -1;
                x = x.kids[-i - 1];
            }
        }

        int height() { int h = 1; for (Node x = root; !x.leaf; x = x.kids[0]) h++; return h; }

        private static int upperBound(BTree.Node x, long k) {
            int lo = 0, hi = x.n;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (x.keys[mid] <= k) lo = mid + 1; else hi = mid; }
            return lo;
        }
    }

    // ---------- A plain (unbalanced) binary search tree: one key per node. ----------
    static final class Bst {
        long[] key = new long[N]; int[] left = new int[N], right = new int[N]; int size = 0;
        Bst() { Arrays.fill(left, -1); Arrays.fill(right, -1); }
        void insert(long k) {
            key[size] = k;
            if (size > 0) {
                int x = 0;
                while (true) {
                    if (k < key[x]) { if (left[x] < 0) { left[x] = size; break; } x = left[x]; }
                    else            { if (right[x] < 0) { right[x] = size; break; } x = right[x]; }
                }
            }
            size++;
        }
        int nodesVisited(long k) {
            int visited = 1;
            for (int x = 0; key[x] != k; visited++) x = k < key[x] ? left[x] : right[x];
            return visited;
        }
    }

    public static void main(String[] args) {
        Random rnd = new Random(42);
        long[] keys = rnd.longs().distinct().limit(N).toArray();          // 1M distinct random keys
        long[] probes = new long[LOOKUPS];
        for (int i = 0; i < LOOKUPS; i++) probes[i] = keys[rnd.nextInt(N)];

        System.out.printf("%,d random keys, %,d lookups of existing keys%n%n", N, LOOKUPS);
        System.out.printf("%-26s %7s %11s %13s %9s%n", "structure", "height", "nodes", "avg visited", "max");

        Bst bst = new Bst();
        for (long k : keys) bst.insert(k);
        long sum = 0; int max = 0;
        for (long p : probes) { int v = bst.nodesVisited(p); sum += v; max = Math.max(max, v); }
        System.out.printf("%-26s %7s %,11d %13.1f %9d%n", "binary search tree", "-", bst.size, (double) sum / LOOKUPS, max);

        for (int order : new int[]{4, 16, 64, 400}) {
            BTree bt = new BTree(order);
            for (long k : keys) bt.insert(k);
            sum = 0; max = 0;
            for (long p : probes) { int v = bt.nodesVisited(p); sum += v; max = Math.max(max, v); }
            System.out.printf("%-26s %7d %,11d %13.2f %9d%n", "B-tree, fan-out " + order, bt.height(), bt.nodeCount, (double) sum / LOOKUPS, max);
        }

        System.out.printf("%nsorted array + binary search: log2(%,d) = %.1f probes, each a random jump%n", N, Math.log(N) / Math.log(2));

        double billion = 1e9;
        System.out.println("\nScaling to 1,000,000,000 keys:");
        System.out.printf("  binary search / balanced BST: log2(1e9)   = %.1f levels -> ~30 random disk reads%n", Math.log(billion) / Math.log(2));
        System.out.printf("  B-tree, fan-out 400:          log400(1e9) = %.2f -> %d levels%n",
                Math.log(billion) / Math.log(400), (int) Math.ceil(Math.log(billion) / Math.log(400)));
        // Bottom-up: full pages of 400 entries. Leaves hold the 1e9 keys, each level above holds 1/400 as many pages.
        long pages = (long) Math.ceil(billion / 400); long internal = 0; StringBuilder lv = new StringBuilder();
        lv.append(String.format("%,d leaf pages", pages));
        while (pages > 1) { pages = (pages + 399) / 400; internal += pages; lv.insert(0, String.format("%,d -> ", pages)); }
        System.out.println("  pages per level (root -> leaves): " + lv);
        System.out.printf("  all non-leaf pages = %,d x 8 KB = %,d MB -> cached in RAM, so ~1 disk read (the leaf) per lookup%n",
                internal, internal * 8 / 1024);
    }
}
