package com.diegonmarcos.cloudlib.sysdns;

import android.annotation.TargetApi;
import android.net.DnsResolver;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * #741 THE DNS SERVER A TERMINAL'S SHELL TALKS TO (data/sysdns.json::_doc). A rootfs under proot
 * resolves with glibc, musl, Go or node, all of which read /etc/resolv.conf and speak DNS on the
 * wire; none of them can reach Android's resolver. Their resolv.conf names 127.0.0.1, proot -p
 * moves port 53 to [data/sysdns.json::bridge_port], and this answers there: UDP and TCP, each
 * query handed AS-IS to an {@link Upstream} — on the phone {@link #android()}, Android's own
 * resolver for this app's uid, which under the SuperApp's VPN is the DNS menu's upstream and
 * otherwise the network's, Private DNS honoured. Nothing here names a server or parses a name.
 *
 * Plain Java on purpose: the terminal forks build with AGP 4 and no Kotlin, and compile this file
 * by reference from libs/sysdns/src/bridge/java, so there is one bridge and not one per terminal.
 */
public final class SystemDnsBridge implements Closeable {

    /** One query out, one answer back through [done]; a null answer is a failed lookup. */
    public interface Upstream { void query(byte[] query, Answer done); }
    public interface Answer { void reply(byte[] answer); }
    public interface Log { void line(String line); }

    /** Classic DNS over UDP without EDNS: anything larger goes truncated so the client retries on TCP. */
    static final int UDP_PLAIN = 512;
    /** A client that sent an OPT record takes this much (the 2020 DNS flag-day size). */
    static final int UDP_EDNS = 1232;
    private static final long ANSWER_TIMEOUT_S = 10;

    private final DatagramSocket udp;
    private final ServerSocket tcp;
    private final Upstream upstream;
    private final Log log;
    /**
     * #791 where every answer is shaped and sent. An {@link Upstream} may call back on any thread,
     * and Android's DnsResolver calls back from the MAIN looper: a UDP send there is a
     * NetworkOnMainThreadException that killed the terminal on its first lookup. So an answer
     * never does anything on the thread it arrived on but hand itself to this one.
     */
    private final ExecutorService replies = Executors.newSingleThreadExecutor(named("sysdns-reply"));

    public SystemDnsBridge(int port, Upstream upstream, Log log) throws IOException {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        this.upstream = upstream;
        this.log = log;
        udp = new DatagramSocket(new InetSocketAddress(lo, port));
        try {
            tcp = new ServerSocket(port, 16, lo);
        } catch (IOException e) {
            udp.close();
            throw e;
        }
        daemon("sysdns-udp", this::serveUdp);
        daemon("sysdns-tcp", this::serveTcp);
        log.line("sysdns: answering 127.0.0.1:" + port + " (udp+tcp) from the system resolver");
    }

    public int port() { return udp.getLocalPort(); }

    @Override public void close() {
        udp.close();
        try { tcp.close(); } catch (IOException ignored) { }
        replies.shutdown();
    }

    private void serveUdp() {
        byte[] buf = new byte[65535];
        while (!udp.isClosed()) {
            final DatagramPacket in = new DatagramPacket(buf, buf.length);
            try { udp.receive(in); } catch (IOException e) { break; }
            final byte[] q = Arrays.copyOf(in.getData(), in.getLength());
            if (q.length < 12) continue;
            final int limit = arcount(q) > 0 ? UDP_EDNS : UDP_PLAIN;
            resolve(q, new Answer() {
                @Override public void reply(byte[] a) {
                    byte[] out = fit(q, a, limit);
                    try {
                        udp.send(new DatagramPacket(out, out.length, in.getSocketAddress()));
                    } catch (IOException e) {
                        log.line("sysdns: an answer could not be sent (" + e + "); dropped, the client retries");
                    }
                }
            });
        }
        log.line("sysdns: udp stopped");
    }

    private void serveTcp() {
        while (!tcp.isClosed()) {
            final Socket c;
            try { c = tcp.accept(); } catch (IOException e) { break; }
            daemon("sysdns-tcp-conn", new Runnable() {
                @Override public void run() { serveTcpClient(c); }
            });
        }
        log.line("sysdns: tcp stopped");
    }

    private void serveTcpClient(Socket c) {
        try {
            c.setSoTimeout(30_000);
            DataInputStream in = new DataInputStream(c.getInputStream());
            DataOutputStream out = new DataOutputStream(c.getOutputStream());
            while (true) {
                byte[] q = new byte[in.readUnsignedShort()];
                in.readFully(q);
                if (q.length < 12) break;
                final byte[][] slot = new byte[1][];
                final CountDownLatch done = new CountDownLatch(1);
                resolve(q, new Answer() {
                    @Override public void reply(byte[] a) { slot[0] = a; done.countDown(); }
                });
                if (!done.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) slot[0] = servfail(q);
                out.writeShort(slot[0].length);
                out.write(slot[0]);
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // The client hung up or went quiet: the one way a DNS-over-TCP conversation ends.
        } finally {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * One query to the upstream; whatever happens, [done] gets an answer carrying the query's id,
     * on the bridge's own reply thread and never on the one the upstream called back on.
     */
    private void resolve(final byte[] q, final Answer done) {
        try {
            upstream.query(q, new Answer() {
                @Override public void reply(byte[] a) { deliver(q, a, done); }
            });
        } catch (RuntimeException e) {
            log.line("sysdns: the system resolver refused the query (" + e + "); replying SERVFAIL");
            deliver(q, null, done);
        }
    }

    /** Runs on whatever thread the answer arrived on, so it only queues: no I/O, no blocking. */
    private void deliver(final byte[] q, final byte[] a, final Answer done) {
        try {
            replies.execute(new Runnable() {
                @Override public void run() {
                    try {
                        done.reply(own(q, a));
                    } catch (RuntimeException e) {
                        // A reply that fails costs that one client a retry, never the process.
                        log.line("sysdns: an answer failed (" + e + "); dropped");
                    }
                }
            });
        } catch (RejectedExecutionException closed) {
            // The bridge is closed: nobody is listening for this answer any more.
        }
    }

    private byte[] own(byte[] q, byte[] a) {
        if (a == null || a.length < 12) {
            log.line("sysdns: the system resolver gave no answer; replying SERVFAIL");
            return servfail(q);
        }
        byte[] own = a.clone();
        own[0] = q[0];
        own[1] = q[1];
        return own;
    }

    /** ARCOUNT: a client that added an OPT record (EDNS) takes answers over 512 bytes. */
    static int arcount(byte[] q) { return ((q[10] & 0xff) << 8) | (q[11] & 0xff); }

    /** Where the question section ends: past the name's labels, its QTYPE and QCLASS. */
    static int questionEnd(byte[] m) {
        int i = 12;
        while (i < m.length && m[i] != 0) {
            if ((m[i] & 0xc0) == 0xc0) return Math.min(i + 2 + 4, m.length); // a pointer ends the name
            i += 1 + (m[i] & 0xff);
        }
        return Math.min(i + 1 + 4, m.length);
    }

    /**
     * An answer that fits [limit] goes as it is; a larger one goes as its header and question with
     * TC set and no records, which every stub resolver reads as "ask again over TCP".
     */
    static byte[] fit(byte[] q, byte[] a, int limit) {
        if (a.length <= limit) return a;
        byte[] t = Arrays.copyOf(a, questionEnd(a));
        t[2] |= 0x02;                    // TC
        Arrays.fill(t, 6, 12, (byte) 0); // no answer, authority or additional records
        return t;
    }

    /**
     * SERVFAIL for [q], echoing its id, opcode, RD and question: glibc ignores an answer whose
     * question does not match its query and would wait out its timeout instead of moving on.
     */
    static byte[] servfail(byte[] q) {
        byte[] r = Arrays.copyOf(q, questionEnd(q));
        r[2] = (byte) (0x80 | (q[2] & 0x79)); // QR, the query's opcode and RD
        r[3] = (byte) (0x80 | 2);             // RA, RCODE=SERVFAIL
        r[4] = 0; r[5] = 1;                   // one question
        Arrays.fill(r, 6, 12, (byte) 0);
        return r;
    }

    private static void daemon(String name, Runnable body) {
        named(name).newThread(body).start();
    }

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable body) {
                Thread t = new Thread(body, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    /**
     * Android's resolver, raw: the query bytes go to netd for THIS app's uid on its default network
     * (the VPN's when the SuperApp routes this app), and the wire answer comes back. API 29+.
     *
     * #791 DnsResolver hands each answer to [callbacks] from a file-descriptor listener on the MAIN
     * looper; the inline executor this once passed ran the callback right there. Its own thread
     * keeps main out of it, and the bridge moves the answer to its reply thread regardless.
     */
    @TargetApi(29)
    public static Upstream android() {
        final Executor callbacks = Executors.newSingleThreadExecutor(named("sysdns-netd"));
        return new Upstream() {
            @Override public void query(byte[] query, final Answer done) {
                DnsResolver.getInstance().rawQuery(null, query, DnsResolver.FLAG_EMPTY, callbacks, null, new DnsResolver.Callback<byte[]>() {
                    @Override public void onAnswer(byte[] answer, int rcode) { done.reply(answer); }
                    @Override public void onError(DnsResolver.DnsException error) { done.reply(null); }
                });
            }
        };
    }
}
