package org.obinexus.polycall;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.obinexus.polycall.Fixtures.T;
import static org.obinexus.polycall.Fixtures.TOKEN;
import static org.obinexus.polycall.Fixtures.expectStatus;

/** Two (or more) real peer nodes in this process exchanging payloads over loopback. */
class PeerTest {
    Peer alpha;
    Peer beta;

    @BeforeEach
    void open() {
        alpha = Peer.open("alpha", "127.0.0.1:0", TOKEN);
        beta = Peer.open("beta", "127.0.0.1:0", TOKEN);
        alpha.register("beta", beta.endpoint());
        beta.register("alpha", alpha.endpoint());
    }

    @AfterEach
    void close() {
        alpha.close();
        beta.close();
    }

    private static void assertMessage(PeerMessage m, String sender, String id, byte[] payload) {
        assertEquals(sender, m.sender(), "sender id");
        assertEquals(id, m.messageId(), "message id");
        assertArrayEquals(payload, m.payload(), "payload bytes");
    }

    @Test
    void nodeIdAndEndpoint() {
        assertEquals("alpha", alpha.nodeId());
        assertTrue(alpha.endpoint().matches("127\\.0\\.0\\.1:\\d+"), alpha.endpoint());
        assertNotEquals(alpha.endpoint(), beta.endpoint());
        assertTrue(alpha.handle() > 0);
    }

    @Test
    void payloadsInBothDirectionsVerifiedAtTheReceiver() {
        alpha.send("beta", "hello beta", "m-a2b", T);
        assertMessage(beta.recv(T), "alpha", "m-a2b", "hello beta".getBytes(StandardCharsets.UTF_8));
        beta.send("alpha", "hello alpha", "m-b2a", T);
        assertMessage(alpha.recv(T), "beta", "m-b2a", "hello alpha".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyPayload() {
        alpha.send("beta", new byte[0], "m-empty", T);
        PeerMessage m = beta.recv(T);
        assertMessage(m, "alpha", "m-empty", new byte[0]);
        assertEquals(0, m.length());
    }

    @Test
    void utf8Payload() {
        String text = "héllo — 世界 🌍";
        alpha.send("beta", text, "m-utf8", T);
        PeerMessage m = beta.recv(T);
        assertMessage(m, "alpha", "m-utf8", text.getBytes(StandardCharsets.UTF_8));
        assertEquals(text, m.text());
    }

    @Test
    void binaryPayloadWithNulBytes() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        beta.send("alpha", all, "m-bin", T);
        assertMessage(alpha.recv(T), "beta", "m-bin", all);
    }

    @Test
    void exactlyOneMebibyte() {
        byte[] big = new byte[Polycall.PEER_MAX_PAYLOAD];
        new Random(42).nextBytes(big);
        alpha.send("beta", big, "m-max", 20_000);
        assertMessage(beta.recv(20_000), "alpha", "m-max", big);
    }

    @Test
    void oneMebibytePlusOneIsRefusedBeforeAnyIo() {
        byte[] over = new byte[Polycall.PEER_MAX_PAYLOAD + 1];
        expectStatus(Status.E_TOO_LARGE, () -> alpha.send("beta", over, "m-over", T));
        assertTrue(beta.tryRecv(300).isEmpty(), "nothing may arrive");
        assertTrue(beta.health().contains("\"received\":0"), beta.health());
    }

    @Test
    void registryBelongsToEachNodeAndChangesOnlyExplicitly() {
        try (Peer c = Peer.open("carol", "127.0.0.1:0", TOKEN);
             Peer d = Peer.open("dave", "127.0.0.1:0", TOKEN)) {
            assertEquals("{}", c.list());
            assertEquals("{}", d.list());
            c.register("dave", d.endpoint());
            assertEquals("{\"dave\":\"" + d.endpoint() + "\"}", c.list());
            assertEquals("{}", d.list(), "registering on carol must not touch dave");
            c.send("dave", "hi", "m-reg", T);
            assertMessage(d.recv(T), "carol", "m-reg", "hi".getBytes(StandardCharsets.UTF_8));
            assertEquals("{}", d.list(), "receiving never registers the sender");
            expectStatus(Status.E_NOT_FOUND, () -> d.send("carol", "back", "m-x", T));
            c.unregister("dave");
            assertEquals("{}", c.list());
            expectStatus(Status.E_NOT_FOUND, () -> c.unregister("dave"));
            expectStatus(Status.E_NOT_FOUND, () -> c.send("dave", "x", "m-y", T));
            // a host:port target needs no registration
            c.send(d.endpoint(), "direct", "m-direct", T);
            assertMessage(d.recv(T), "carol", "m-direct", "direct".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void duplicateMessageIdIsDeliveredOnce() {
        alpha.send("beta", "once", "m-dup", T);
        alpha.send("beta", "once", "m-dup", T);
        assertMessage(beta.recv(T), "alpha", "m-dup", "once".getBytes(StandardCharsets.UTF_8));
        assertTrue(beta.tryRecv(500).isEmpty(), "the duplicate must not be queued");
        assertTrue(beta.health().contains("\"duplicates\":1"), beta.health());
    }

    @Test
    void wrongOrMissingTokenIsAnAuthFailure() {
        try (Peer mallory = Peer.open("mallory", null, "wrong-token");
             Peer anon = Peer.open("anon", null, null)) {
            expectStatus(Status.E_AUTH, () -> mallory.send(beta.endpoint(), "x", "m-auth1", T));
            expectStatus(Status.E_AUTH, () -> anon.send(beta.endpoint(), "x", "m-auth2", T));
            assertTrue(beta.tryRecv(300).isEmpty(), "nothing from an unauthenticated sender is queued");
            // /health needs no token
            assertDoesNotThrow(() -> anon.ping(beta.endpoint(), T));
        }
    }

    @Test
    void sendToADeadPeerIsTransport() {
        Peer dead = Peer.open("dead", "127.0.0.1:0", TOKEN);
        String ep = dead.endpoint();
        dead.close();
        expectStatus(Status.E_TRANSPORT, () -> alpha.send(ep, "into the void", "m-dead", 3000));
        expectStatus(Status.E_TRANSPORT, () -> alpha.ping(ep, 3000));
    }

    @Test
    void receiveTimeout() {
        long t0 = System.nanoTime();
        expectStatus(Status.E_TIMEOUT, () -> beta.recv(250));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 200 && ms < 5000, "waited " + ms + " ms");
        expectStatus(Status.E_TIMEOUT, () -> beta.recv(0));
        assertTrue(beta.tryRecv(0).isEmpty());
    }

    @Test
    void tooSmallBufferLeavesTheMessageQueued() {
        byte[] hundred = new byte[100];
        new Random(7).nextBytes(hundred);
        alpha.send("beta", hundred, "m-small", T);
        PolycallException e = expectStatus(Status.E_TOO_LARGE, () -> beta.recv(T, 10));
        assertEquals(100, e.requiredSize());
        e = expectStatus(Status.E_TOO_LARGE, () -> beta.recv(T, 0));
        assertEquals(100, e.requiredSize());
        assertMessage(beta.recv(T, 100), "alpha", "m-small", hundred);

        // the growing recv() handles messages bigger than its first buffer
        byte[] large = new byte[300_000];
        new Random(8).nextBytes(large);
        alpha.send("beta", large, "m-large", T);
        assertMessage(beta.recv(T), "alpha", "m-large", large);
    }

    @Test
    void cancelWakesABlockedReceive() throws Exception {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            try {
                beta.recv(Peer.WAIT_FOREVER);
            } catch (Throwable x) {
                seen.set(x);
            }
        });
        t.start();
        started.await();
        Thread.sleep(400);
        beta.cancel();
        t.join(5000);
        assertFalse(t.isAlive(), "cancel must wake the receiver");
        PolycallException e = (PolycallException) seen.get();
        assertNotNull(e);
        assertEquals(Status.E_CANCELLED, e.status());
        // later receives wait normally
        alpha.send("beta", "after cancel", "m-after", T);
        assertMessage(beta.recv(T), "alpha", "m-after", "after cancel".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void closeWakesABlockedReceive() throws Exception {
        Peer c = Peer.open("closer", "127.0.0.1:0", TOKEN);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                c.recv(Peer.WAIT_FOREVER);
            } catch (Throwable x) {
                seen.set(x);
            }
        });
        t.start();
        Thread.sleep(400);
        c.close();
        t.join(5000);
        assertFalse(t.isAlive(), "close must wake the receiver");
        assertEquals(Status.E_CLOSED, ((PolycallException) seen.get()).status());
    }

    @Test
    void doubleCloseCallsAfterCloseAndInvalidHandles() {
        Peer c = Peer.open("gone", "127.0.0.1:0", TOKEN);
        int h = c.handle();
        c.close();
        assertTrue(c.isClosed());
        assertDoesNotThrow(c::close, "close() is idempotent on the object");
        expectStatus(Status.E_INVALID_HANDLE, () -> Peer.closeHandle(h));
        expectStatus(Status.E_INVALID_HANDLE, c::endpoint);
        expectStatus(Status.E_INVALID_HANDLE, c::nodeId);
        expectStatus(Status.E_INVALID_HANDLE, c::list);
        expectStatus(Status.E_INVALID_HANDLE, c::health);
        expectStatus(Status.E_INVALID_HANDLE, c::cancel);
        expectStatus(Status.E_INVALID_HANDLE, () -> c.register("x", "127.0.0.1:1"));
        expectStatus(Status.E_INVALID_HANDLE, () -> c.unregister("x"));
        expectStatus(Status.E_INVALID_HANDLE, () -> c.ping(beta.endpoint(), T));
        expectStatus(Status.E_INVALID_HANDLE, () -> c.send(beta.endpoint(), "x", "m-closed", T));
        expectStatus(Status.E_INVALID_HANDLE, () -> c.recv(0));
        for (int bogus : new int[] {0, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, h + 1}) {
            if (bogus == alpha.handle() || bogus == beta.handle()) {
                continue;
            }
            expectStatus(Status.E_INVALID_HANDLE, () -> Peer.closeHandle(bogus));
        }
        // a stale handle is never reused for a new node
        try (Peer e = Peer.open("fresh", "127.0.0.1:0", TOKEN)) {
            assertNotEquals(h, e.handle());
            expectStatus(Status.E_INVALID_HANDLE, c::endpoint);
            assertEquals("fresh", e.nodeId());
        }
    }

    private static String openAndDrop() {
        return Peer.open("dropped", "127.0.0.1:0", TOKEN).endpoint();
    }

    /** A peer dropped without close() is closed by its Cleaner (its listener stops). */
    @Test
    void anUnreachablePeerIsClosedByTheCleaner() throws Exception {
        String ep = openAndDrop();
        alpha.ping(ep, T);
        long end = System.nanoTime() + 30_000_000_000L;
        PolycallException last = null;
        while (System.nanoTime() < end) {
            System.gc();
            Thread.sleep(200);
            try {
                alpha.ping(ep, 3000);
            } catch (PolycallException e) {
                if (e.status() == Status.E_TRANSPORT) {
                    return; // the listener is gone: the Cleaner closed the node
                }
                last = e;
            }
        }
        fail("the dropped peer was never closed; last error: " + last);
    }

    @Test
    void concurrentSenders() throws Exception {
        int senders = 4;
        int threadsPerSender = 2;
        int perThread = 20;
        List<Peer> nodes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(senders * threadsPerSender);
        try {
            for (int s = 0; s < senders; s++) {
                Peer p = Peer.open("sender-" + s, null, TOKEN);
                p.register("beta", beta.endpoint());
                nodes.add(p);
            }
            List<Future<?>> futures = new ArrayList<>();
            for (int s = 0; s < senders; s++) {
                for (int t = 0; t < threadsPerSender; t++) {
                    final Peer p = nodes.get(s);
                    final String prefix = "s" + s + "-t" + t + "-";
                    futures.add(pool.submit(() -> {
                        for (int i = 0; i < perThread; i++) {
                            p.send("beta", prefix + i, prefix + i, 10_000);
                        }
                        return null;
                    }));
                }
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            Set<String> got = new HashSet<>();
            int total = senders * threadsPerSender * perThread;
            for (int i = 0; i < total; i++) {
                PeerMessage m = beta.recv(T);
                assertEquals(m.messageId(), m.text(), "payload matches its id");
                assertTrue(m.sender().startsWith("sender-"));
                assertTrue(got.add(m.sender() + "/" + m.messageId()), "duplicate " + m);
            }
            assertEquals(total, got.size());
            assertTrue(beta.tryRecv(200).isEmpty());
        } finally {
            pool.shutdownNow();
            nodes.forEach(Peer::close);
        }
    }

    @Test
    void pingChecksHealthAndIdentity() {
        alpha.ping("beta", T);
        alpha.ping(beta.endpoint(), T);
        alpha.register("gamma", beta.endpoint());
        PolycallException e = expectStatus(Status.E_PROTOCOL, () -> alpha.ping("gamma", T));
        assertTrue(e.detail().contains("beta"), e.detail());
        String health = beta.health();
        assertTrue(health.contains("\"node_id\":\"beta\""), health);
        assertTrue(health.contains("\"protocol\":\"polycall-peer/1\""), health);
    }

    @Test
    void sendOnlyNodeHasNoEndpoint() {
        try (Peer s = Peer.open("send-only", null, TOKEN)) {
            assertEquals("", s.endpoint());
            s.send(beta.endpoint(), "from a send-only node", "m-so", T);
            assertMessage(beta.recv(T), "send-only", "m-so",
                    "from a send-only node".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void invalidArgumentsAndBindRules() {
        expectStatus(Status.E_INVALID_ARGUMENT, () -> Peer.open("bad id!", "127.0.0.1:0", TOKEN));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> Peer.open("x".repeat(64), "127.0.0.1:0", TOKEN));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> Peer.open("ok", "not-an-endpoint", TOKEN));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.send("beta", "x", "bad id!", T));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.send("", "x", "m-1", T));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.register("bad id!", beta.endpoint()));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.recv(-1));
        // uint32_t timeouts: values outside 0..UINT32_MAX never reach the library truncated
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.recv(Peer.WAIT_FOREVER + 1));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.ping("beta", Peer.WAIT_FOREVER + 1));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.send("beta", "x", "m-t", -1));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.recv(T, -1));
        expectStatus(Status.E_INVALID_ARGUMENT, () -> alpha.recv(T, Integer.MAX_VALUE + 1L));
        // a non-loopback listener without a token is refused
        expectStatus(Status.E_CONFIG, () -> Peer.open("exposed", "0.0.0.0:0", null));
        // the port is taken
        expectStatus(Status.E_ADDRESS_IN_USE, () -> Peer.open("clash", beta.endpoint(), TOKEN));
    }

    @Test
    void errorCarriesStatusNameAndDetail() {
        PolycallException e = expectStatus(Status.E_NOT_FOUND, () -> alpha.send("nobody", "x", "m-1", T));
        assertEquals("POLYCALL_E_NOT_FOUND", e.statusName());
        assertTrue(e.statusText().startsWith("POLYCALL_E_NOT_FOUND:"), e.statusText());
        assertTrue(e.detail().contains("nobody"), e.detail());
        assertTrue(e.getMessage().contains("POLYCALL_E_NOT_FOUND (-7)"), e.getMessage());
    }
}
