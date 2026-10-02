package org.obinexus.polycall;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A Polycall peer node ({@code polycall_peer_*}): an NSIGII-style node with
 * its own registry and inbox that exchanges payloads directly with other
 * nodes (any language, any process) over polycall-peer/1.
 *
 * <p>Thread-safe: any method may be called from any thread, including
 * {@link #cancel()} and {@link #close()} while another thread is blocked in
 * {@link #recv(long)}. {@link #close()} is idempotent; the methods of a
 * closed peer pass its stale handle to the library, which reports
 * {@link Status#E_INVALID_HANDLE}. A peer that is never closed is closed by
 * a {@link Cleaner} once unreachable.</p>
 */
public final class Peer implements AutoCloseable {
    /** {@code UINT32_MAX}: block in {@link #recv(long)} until a message, cancel or close. */
    public static final long WAIT_FOREVER = 0xFFFF_FFFFL;

    private static final Cleaner CLEANER = Cleaner.create();
    private static final int INITIAL_RECV_CAPACITY = 64 * 1024;

    private final int handle;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CloseAction closeAction;
    private final Cleaner.Cleanable cleanable;

    private static final class CloseAction implements Runnable {
        private final int handle;
        volatile int status = Status.OK;

        CloseAction(int handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            try {
                status = (int) Polycall.api().peerClose.invokeExact(handle);
            } catch (Throwable t) {
                status = Status.E_INTERNAL;
            }
        }
    }

    private Peer(int handle) {
        this.handle = handle;
        this.closeAction = new CloseAction(handle);
        this.cleanable = CLEANER.register(this, closeAction);
    }

    /**
     * Open a node ({@code polycall_peer_open}).
     *
     * @param nodeId       1-63 of [A-Za-z0-9._-]
     * @param bindEndpoint "host:port" to listen on ("127.0.0.1:0" = ephemeral
     *                     port), or null for a send-only node
     * @param authToken    shared token (null or "" = no authentication)
     */
    public static Peer open(String nodeId, String bindEndpoint, String authToken) {
        NativeApi api = Polycall.api();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = Polycall.cstr(arena, nodeId, "nodeId", false);
            MemorySegment ep = Polycall.cstr(arena, bindEndpoint, "bindEndpoint", true);
            MemorySegment tok = Polycall.cstr(arena, authToken, "authToken", true);
            MemorySegment out = arena.allocate(JAVA_INT);
            int st = (int) api.peerOpen.invokeExact(id, ep, tok, out);
            if (st != Status.OK) {
                throw Polycall.error(st, "peer_open(" + nodeId + ", " + bindEndpoint + ")");
            }
            return new Peer(out.get(JAVA_INT, 0));
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
    }

    /** Open a listening node without authentication. */
    public static Peer open(String nodeId, String bindEndpoint) {
        return open(nodeId, bindEndpoint, null);
    }

    /**
     * Close a raw handle ({@code polycall_peer_close}). The library validates
     * it: an unknown, closed or stale handle raises
     * {@link Status#E_INVALID_HANDLE}. Prefer {@link #close()}.
     */
    public static void closeHandle(int handle) {
        int st;
        try {
            st = (int) Polycall.api().peerClose.invokeExact(handle);
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
        Polycall.check(st, "peer_close(" + handle + ")");
    }

    /** The library's integer handle (> 0). */
    public int handle() {
        return handle;
    }

    /** True once {@link #close()} was called on this object. */
    public boolean isClosed() {
        return closed.get();
    }

    /** The bound "host:port" ("" for a send-only node). */
    public String endpoint() {
        NativeApi api = Polycall.api();
        return Polycall.text("peer_endpoint", (buf, cap, outLen) ->
                (int) api.peerEndpoint.invokeExact(handle, buf, cap));
    }

    /** This node's id. */
    public String nodeId() {
        NativeApi api = Polycall.api();
        return Polycall.text("peer_node_id", (buf, cap, outLen) ->
                (int) api.peerNodeId.invokeExact(handle, buf, cap));
    }

    /** Add or replace {@code peerId -> endpoint} in THIS node's registry. */
    public void register(String peerId, String endpoint) {
        NativeApi api = Polycall.api();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = Polycall.cstr(arena, peerId, "peerId", false);
            MemorySegment ep = Polycall.cstr(arena, endpoint, "endpoint", false);
            int st = (int) api.peerRegister.invokeExact(handle, id, ep);
            Polycall.check(st, "peer_register(" + peerId + ")");
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
    }

    /** Remove {@code peerId}; {@link Status#E_NOT_FOUND} when it is not registered. */
    public void unregister(String peerId) {
        NativeApi api = Polycall.api();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = Polycall.cstr(arena, peerId, "peerId", false);
            int st = (int) api.peerUnregister.invokeExact(handle, id);
            Polycall.check(st, "peer_unregister(" + peerId + ")");
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
    }

    /** THIS node's registry as a JSON object {@code {"id":"host:port",...}}. */
    public String list() {
        NativeApi api = Polycall.api();
        return Polycall.text("peer_list", (buf, cap, outLen) ->
                (int) api.peerList.invokeExact(handle, buf, cap, outLen));
    }

    /** This node's health as JSON (node id, endpoint, peers, inbox, counters). */
    public String health() {
        NativeApi api = Polycall.api();
        return Polycall.text("peer_health", (buf, cap, outLen) ->
                (int) api.peerHealth.invokeExact(handle, buf, cap, outLen));
    }

    /**
     * GET /health of {@code peer} (registered id or "host:port"); succeeds
     * only when it answers healthy (and, for a registered id, under that id).
     */
    public void ping(String peer, long timeoutMs) {
        NativeApi api = Polycall.api();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = Polycall.cstr(arena, peer, "peer", false);
            int st = (int) api.peerPing.invokeExact(handle, p, Polycall.uint32(timeoutMs, "timeoutMs"));
            Polycall.check(st, "peer_ping(" + peer + ")");
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
    }

    /**
     * Deliver {@code payload} (binary-safe, at most 1 MiB) to {@code peer}
     * (registered id or "host:port"). Exactly one delivery attempt: returning
     * normally means the receiver stored and acknowledged it. On
     * {@link Status#E_TIMEOUT} the outcome is unknown; retry with the SAME
     * {@code messageId} and the receiver drops the duplicate.
     *
     * @param messageId 1-63 of [A-Za-z0-9._-], or null to let the library generate one
     */
    public void send(String peer, byte[] payload, String messageId, long timeoutMs) {
        NativeApi api = Polycall.api();
        if (payload == null) {
            throw Polycall.argumentError("payload is null");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = Polycall.cstr(arena, peer, "peer", false);
            MemorySegment mid = Polycall.cstr(arena, messageId, "messageId", true);
            MemorySegment data;
            if (payload.length == 0) {
                data = MemorySegment.NULL;
            } else {
                data = arena.allocate(payload.length);
                MemorySegment.copy(payload, 0, data, JAVA_BYTE, 0, payload.length);
            }
            int st = (int) api.peerSend.invokeExact(handle, p, data, (long) payload.length, mid,
                    Polycall.uint32(timeoutMs, "timeoutMs"));
            Polycall.check(st, "peer_send(" + peer + ", " + payload.length + " bytes, id="
                    + messageId + ")");
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
    }

    /** Send UTF-8 text. */
    public void send(String peer, String text, String messageId, long timeoutMs) {
        if (text == null) {
            throw Polycall.argumentError("text is null");
        }
        send(peer, text.getBytes(StandardCharsets.UTF_8), messageId, timeoutMs);
    }

    /**
     * Take the oldest message, waiting up to {@code timeoutMs} (0 = poll,
     * {@link #WAIT_FOREVER} = until a message, cancel or close). The payload
     * buffer grows to whatever the message needs.
     *
     * @throws PolycallException {@link Status#E_TIMEOUT}, {@link Status#E_CANCELLED},
     *                           {@link Status#E_CLOSED}, ...
     */
    public PeerMessage recv(long timeoutMs) {
        Polycall.uint32(timeoutMs, "timeoutMs");
        long deadline = timeoutMs == WAIT_FOREVER ? Long.MAX_VALUE
                : System.nanoTime() + timeoutMs * 1_000_000L;
        long capacity = INITIAL_RECV_CAPACITY;
        long wait = timeoutMs;
        while (true) {
            try {
                return recv(wait, capacity);
            } catch (PolycallException e) {
                if (e.status() != Status.E_TOO_LARGE || e.requiredSize() <= capacity) {
                    throw e;
                }
                // the message stays queued: retry at once with a big enough buffer
                capacity = e.requiredSize();
                if (deadline != Long.MAX_VALUE) {
                    wait = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
                }
            }
        }
    }

    /**
     * Take the oldest message into a payload buffer of exactly
     * {@code payloadCapacity} bytes. If the message is larger this raises
     * {@link Status#E_TOO_LARGE} with {@link PolycallException#requiredSize()}
     * and the message stays queued.
     */
    public PeerMessage recv(long timeoutMs, long payloadCapacity) {
        NativeApi api = Polycall.api();
        int timeout = Polycall.uint32(timeoutMs, "timeoutMs");
        if (payloadCapacity < 0 || payloadCapacity > Integer.MAX_VALUE) {
            throw Polycall.argumentError("payloadCapacity out of range: " + payloadCapacity);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sender = arena.allocate(Polycall.ID_MAX);
            MemorySegment mid = arena.allocate(Polycall.ID_MAX);
            MemorySegment payload = payloadCapacity == 0 ? MemorySegment.NULL
                    : arena.allocate(payloadCapacity);
            MemorySegment len = arena.allocate(JAVA_LONG);
            int st = (int) api.peerRecv.invokeExact(handle, timeout,
                    sender, (long) Polycall.ID_MAX, mid, (long) Polycall.ID_MAX,
                    payload, payloadCapacity, len);
            long n = len.get(JAVA_LONG, 0);
            if (st == Status.E_TOO_LARGE) {
                throw Polycall.error(st, "peer_recv", null, n);
            }
            if (st != Status.OK) {
                throw Polycall.error(st, "peer_recv");
            }
            byte[] data = n == 0 ? new byte[0] : payload.asSlice(0, n).toArray(JAVA_BYTE);
            return new PeerMessage(Polycall.readString(sender), Polycall.readString(mid), data);
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
    }

    /** Like {@link #recv(long)} but returns empty instead of raising {@link Status#E_TIMEOUT}. */
    public Optional<PeerMessage> tryRecv(long timeoutMs) {
        try {
            return Optional.of(recv(timeoutMs));
        } catch (PolycallException e) {
            if (e.status() == Status.E_TIMEOUT) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Wake every {@link #recv} currently blocked on this node with {@link Status#E_CANCELLED}. */
    public void cancel() {
        int st;
        try {
            st = (int) Polycall.api().peerCancel.invokeExact(handle);
        } catch (Throwable t) {
            throw Polycall.rethrow(t);
        }
        Polycall.check(st, "peer_cancel");
    }

    /**
     * Stop the listener, wake blocked receivers ({@link Status#E_CLOSED}) and
     * release the node. Idempotent on this object.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cleanable.clean();
        int st = closeAction.status;
        if (st != Status.OK) {
            throw Polycall.error(st, "peer_close(" + handle + ")");
        }
    }

    @Override
    public String toString() {
        return "Peer[handle=" + handle + (closed.get() ? ", closed" : "") + "]";
    }
}
