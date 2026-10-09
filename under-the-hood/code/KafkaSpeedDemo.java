import java.io.*;
import java.lang.management.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/**
 * Kafka's three speed tricks, measured on your own machine:
 *  (a) sequential appends vs random-offset writes (page cache only, then with fsync)
 *  (b) file -> socket: read/write loop through a heap buffer vs FileChannel.transferTo (sendfile)
 *  (c) 100,000 tiny messages: one write() syscall each vs batched into 64 KB buffers
 * Run: java KafkaSpeedDemo.java   (uses up to ~250 MB in a temp dir, deleted at the end)
 */
public class KafkaSpeedDemo {
    static final int REC = 1024;                       // 1 KB records
    static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("kafka-speed");
        try {
            sequentialVsRandom(dir);
            zeroCopy(dir);
            batching(dir);
        } finally {
            try (var files = Files.list(dir)) { for (Path p : files.toList()) Files.delete(p); }
            Files.delete(dir);
            System.out.println("\ntemp dir deleted: " + dir);
        }
    }

    // (a) ---------------------------------------------------------------------------------
    static void sequentialVsRandom(Path dir) throws IOException {
        System.out.println("(a) 1 KB records: sequential append vs random offsets (write = into page cache, fsync = to disk)");
        Random rnd = new Random(42);
        for (int[] cfg : new int[][] {{50_000, 0}, {2_000, 1}}) {   // {records, fsync after every write?}
            int n = cfg[0]; boolean each = cfg[1] == 1;
            long slots = 64L * n;                                    // random writes land anywhere in a 64x bigger file
            long[] seq = writeAndSync(dir.resolve("seq.log"), n, each, -1, rnd);
            long[] rand = writeAndSync(dir.resolve("rand.db"), n, each, slots, rnd);
            System.out.printf("  %,6d records%s%n    sequential: write %5d ms + fsync %5d ms = %5d ms%n"
                    + "    random    : write %5d ms + fsync %5d ms = %5d ms  (%.1fx slower)%n",
                    n, each ? ", fsync after EVERY write:" : ", one fsync at the end:",
                    seq[0], seq[1], seq[0] + seq[1], rand[0], rand[1], rand[0] + rand[1],
                    (double) (rand[0] + rand[1]) / Math.max(1, seq[0] + seq[1]));
            Files.delete(dir.resolve("seq.log")); Files.delete(dir.resolve("rand.db"));
        }
    }

    /** slots < 0 = append at the end; else write at random 1 KB slots. Returns {ms in write(), ms in force()}. */
    static long[] writeAndSync(Path file, int n, boolean syncEach, long slots, Random rnd) throws IOException {
        ByteBuffer rec = ByteBuffer.allocateDirect(REC);
        long writeNs = 0, syncNs = 0;
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            FileChannel ch = f.getChannel();
            if (slots > 0) f.setLength(slots * REC);                 // sparse: uses no disk until written
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                rec.clear();
                if (slots < 0) ch.write(rec); else ch.write(rec, (long) rnd.nextInt((int) slots) * REC);
                long t1 = System.nanoTime(); writeNs += t1 - t0;
                if (syncEach) { ch.force(false); syncNs += System.nanoTime() - t1; }
            }
            long t2 = System.nanoTime(); ch.force(false); syncNs += System.nanoTime() - t2;
        }
        return new long[] {writeNs / 1_000_000, syncNs / 1_000_000};
    }

    // (b) ---------------------------------------------------------------------------------
    static void zeroCopy(Path dir) throws Exception {
        int mb = 64, rounds = 10;
        Path file = dir.resolve("segment.log");
        byte[] chunk = new byte[1 << 20]; new Random(1).nextBytes(chunk);
        try (OutputStream out = Files.newOutputStream(file)) { for (int i = 0; i < mb; i++) out.write(chunk); }
        System.out.printf("%n(b) send a %d MB file to a local socket, %d times each (file is in the page cache)%n", mb, rounds);

        ServerSocketChannel server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
        Thread sink = Thread.ofPlatform().daemon().start(() -> {   // the "consumer": reads and throws bytes away
            ByteBuffer drop = ByteBuffer.allocateDirect(1 << 20);
            try { while (true) try (SocketChannel c = server.accept()) { while (c.read(drop.clear()) >= 0) { } } }
            catch (IOException ignored) { }
        });
        for (int warm = 0; warm < 2; warm++) { send(server, file, true); send(server, file, false); } // JIT + cache warm-up
        long[] copy = new long[2], zero = new long[2];
        for (int r = 0; r < rounds; r++) { add(copy, send(server, file, false)); add(zero, send(server, file, true)); }
        double total = (double) mb * rounds;
        System.out.printf("  read+write via heap buffer : %5d ms wall (%5.0f MB/s), sender thread CPU %5d ms%n",
                copy[0], total * 1000 / copy[0], copy[1]);
        System.out.printf("  transferTo (sendfile)      : %5d ms wall (%5.0f MB/s), sender thread CPU %5d ms%n",
                zero[0], total * 1000 / zero[0], zero[1]);
        server.close();
    }

    /** Sends the whole file once; returns {wall ms, sender-thread CPU ms}. */
    static long[] send(ServerSocketChannel server, Path file, boolean zeroCopy) throws IOException {
        long t0 = System.nanoTime(), c0 = CPU.getCurrentThreadCpuTime();
        try (FileChannel in = FileChannel.open(file);
             SocketChannel out = SocketChannel.open(server.getLocalAddress())) {
            long size = in.size(), pos = 0;
            if (zeroCopy) {
                while (pos < size) pos += in.transferTo(pos, size - pos, out);  // kernel moves page cache -> socket
            } else {
                ByteBuffer heap = ByteBuffer.allocate(64 * 1024);              // classic: kernel -> JVM -> kernel
                while (in.read(heap.clear()) > 0) { heap.flip(); while (heap.hasRemaining()) out.write(heap); }
            }
        }
        return new long[] {(System.nanoTime() - t0) / 1_000_000, (CPU.getCurrentThreadCpuTime() - c0) / 1_000_000};
    }

    static void add(long[] acc, long[] x) { acc[0] += x[0]; acc[1] += x[1]; }

    // (c) ---------------------------------------------------------------------------------
    static void batching(Path dir) throws IOException {
        int n = 100_000; byte[] msg = new byte[100];             // 100-byte messages, ~10 MB total
        System.out.printf("%n(c) %,d messages of %d bytes written to a file (page cache, no fsync)%n", n, msg.length);
        for (int round = 0; round < 2; round++) {                // round 0 warms up the JIT, round 1 is printed
            long one = timeMs(() -> {
                try (FileChannel ch = FileChannel.open(dir.resolve("one.log"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    ByteBuffer b = ByteBuffer.allocateDirect(msg.length);
                    for (int i = 0; i < n; i++) { b.clear(); b.put(msg).flip(); ch.write(b); }   // 1 syscall per message
                }
            });
            long batched = timeMs(() -> {
                try (FileChannel ch = FileChannel.open(dir.resolve("batch.log"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    ByteBuffer b = ByteBuffer.allocateDirect(64 * 1024);
                    for (int i = 0; i < n; i++) {
                        if (b.remaining() < msg.length) { b.flip(); ch.write(b); b.clear(); }   // 1 syscall per 64 KB
                        b.put(msg);
                    }
                    b.flip(); ch.write(b);
                }
            });
            if (round == 1) System.out.printf("  one write() per message: %4d ms (%,d syscalls)%n  64 KB batches         : %4d ms (%,d syscalls)%n",
                    one, n, batched, (n * msg.length + 65535) / 65536);
            Files.delete(dir.resolve("one.log")); Files.delete(dir.resolve("batch.log"));
        }
    }

    interface IoTask { void run() throws IOException; }
    static long timeMs(IoTask t) throws IOException { long t0 = System.nanoTime(); t.run(); return (System.nanoTime() - t0) / 1_000_000; }
}
