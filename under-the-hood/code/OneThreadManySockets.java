import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

/** One server thread, thousands of connections: Java NIO's Selector (epoll on Linux). Run: java OneThreadManySockets.java */
public class OneThreadManySockets {
    static final int CLIENTS = 5_000;
    static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        Selector selector = Selector.open();
        System.out.println("Selector implementation: " + selector.getClass().getName());
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", 0), CLIENTS);
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
        int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

        // The whole server: ONE thread looping on select().
        Thread serverThread = new Thread(() -> {
            ByteBuffer buf = ByteBuffer.allocate(64);
            long wakeups = 0;
            try {
                while (running) {
                    selector.select();                         // sleeps until at least one socket is ready
                    wakeups++;
                    for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext(); ) {
                        SelectionKey key = it.next(); it.remove();
                        if (key.isAcceptable()) {
                            SocketChannel c = server.accept();
                            if (c != null) { c.configureBlocking(false); c.register(selector, SelectionKey.OP_READ); }
                        } else if (key.isReadable()) {
                            SocketChannel c = (SocketChannel) key.channel();
                            buf.clear();
                            int n = c.read(buf);
                            if (n < 0) { key.cancel(); c.close(); continue; }
                            buf.flip();
                            c.write(buf);                          // echo back (tiny message, fits the socket buffer)
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("server thread woke up " + wakeups + " times in total");
        }, "the-only-server-thread");
        serverThread.start();

        long t0 = System.nanoTime();
        List<SocketChannel> clients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) clients.add(SocketChannel.open(new InetSocketAddress("127.0.0.1", port)));
        System.out.printf("%,d clients connected in %d ms%n", CLIENTS, (System.nanoTime() - t0) / 1_000_000);

        long t1 = System.nanoTime();
        ByteBuffer ping = ByteBuffer.wrap("ping".getBytes());
        for (SocketChannel c : clients) { ping.rewind(); c.write(ping); }
        int replies = 0;
        ByteBuffer in = ByteBuffer.allocate(4);
        for (SocketChannel c : clients) { in.clear(); while (in.hasRemaining()) c.read(in); replies++; }
        System.out.printf("%,d pings echoed by the server in %d ms%n", replies, (System.nanoTime() - t1) / 1_000_000);

        long serverThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("the-only-server-thread")).count();
        System.out.println("threads serving those connections: " + serverThreads);

        for (SocketChannel c : clients) c.close();
        running = false;
        selector.wakeup();                                     // make select() return so the loop sees running == false
        serverThread.join();
        selector.close();
    }
}
