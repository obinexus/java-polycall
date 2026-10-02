package org.obinexus.polycall;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** polycall_call against a real `polycall start` runtime and `polycall daemon start`. */
class CallTest {
    @TempDir
    static Path dir;
    static Fixtures.Proc runtime;

    @BeforeAll
    static void startRuntime() throws Exception {
        runtime = Fixtures.startRuntime(dir);
    }

    @AfterAll
    static void stopRuntime() throws Exception {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void successReturnsOperationOutput() {
        String out = Polycall.call(runtime.endpoint, "inventory", "get", "{\"item_id\":\"widget-a\"}", 5000);
        assertTrue(out.contains("\"quantity\":42"), out);
        assertTrue(out.contains("\"in_stock\":true"), out);
    }

    @Test
    void utf8InputRoundTripsThroughDebugEcho() {
        String in = "{\"text\":\"héllo — 世界 🌍\"}";
        String out = Polycall.call(runtime.endpoint, "debug", "echo", in, 5000);
        assertEquals("{\"echo\":" + in + "}", out);
    }

    @Test
    void nullInputIsSentAsJsonNull() {
        assertEquals("{\"echo\":null}", Polycall.call(runtime.endpoint, "debug", "echo", null, 5000));
    }

    @Test
    void unknownOperationIsNotFoundWithRemoteErrorObject() {
        PolycallException e = Fixtures.expectStatus(Status.E_NOT_FOUND,
                () -> Polycall.call(runtime.endpoint, "inventory", "nope", "{}", 5000));
        assertNotNull(e.remoteError());
        assertTrue(e.remoteError().contains("operation.unknown"), e.remoteError());
    }

    @Test
    void operationErrorIsRemote() {
        PolycallException e = Fixtures.expectStatus(Status.E_REMOTE,
                () -> Polycall.call(runtime.endpoint, "inventory", "get", "{\"item_id\":\"nope\"}", 5000));
        assertTrue(e.remoteError().contains("item.unknown"), e.remoteError());
    }

    @Test
    void deadlineExceededIsTimeout() {
        long t0 = System.nanoTime();
        PolycallException e = Fixtures.expectStatus(Status.E_TIMEOUT,
                () -> Polycall.call(runtime.endpoint, "debug", "sleep", "{\"ms\":5000}", 300));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 4000, "returned after " + ms + " ms");
        assertNotNull(e.remoteError());
    }

    @Test
    void invalidInputIsRejectedBeforeAnyIo() {
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT,
                () -> Polycall.call(runtime.endpoint, "debug", "echo", "{not json", 5000));
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT,
                () -> Polycall.call(runtime.endpoint, "debug", "echo", "{}", 0));
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT,
                () -> Polycall.call(runtime.endpoint, "debug", "echo", "{}", 600_001));
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT,
                () -> Polycall.call("no-port", "debug", "echo", "{}", 5000));
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT,
                () -> Polycall.call(runtime.endpoint, "", "echo", "{}", 5000));
    }

    @Test
    void noRuntimeIsTransport() throws Exception {
        int port = Fixtures.freePort();
        Fixtures.expectStatus(Status.E_TRANSPORT,
                () -> Polycall.call("127.0.0.1:" + port, "inventory", "get", "{}", 2000));
    }

    @Test
    void concurrentCallsFromManyThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final int n = i;
                futures.add(pool.submit(() ->
                        Polycall.call(runtime.endpoint, "debug", "echo", "{\"n\":" + n + "}", 10_000)));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals("{\"echo\":{\"n\":" + i + "}}", futures.get(i).get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void callThroughPolycallDaemon(@TempDir Path project) throws Exception {
        Path cli = Fixtures.requireCli();
        Path file = project.resolve("Polycallfile");
        Files.writeString(file, "# java-polycall daemon test\nserver node 8080:8084\nnetwork start\n"
                + "daemon_endpoint=127.0.0.1:0\nauth_token_env=POLYCALL_DEV_TOKEN\n");
        Fixtures.Result start = Fixtures.run(List.of(cli.toString(), "--format", "json", "daemon", "start",
                "-t", "15000", file.toString()), project, null, Duration.ofSeconds(30));
        try {
            assertEquals(0, start.exit(), start.out() + start.stderr());
            String endpoint = Fixtures.jsonString(start.out(), "endpoint");
            assertNotNull(endpoint, start.out());
            String out = Polycall.call(endpoint, "inventory", "get", "{\"item_id\":\"widget-b\"}", 5000);
            assertTrue(out.contains("\"quantity\":7"), out);
        } finally {
            Fixtures.Result stop = Fixtures.run(List.of(cli.toString(), "daemon", "stop", "-t", "10000",
                    file.toString()), project, null, Duration.ofSeconds(30));
            assertEquals(0, stop.exit(), "daemon stop: " + stop.out() + stop.stderr());
        }
    }
}
