package org.obinexus.polycall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.obinexus.polycall.Fixtures.T;
import static org.obinexus.polycall.Fixtures.TOKEN;

/**
 * Cross-language interop with the C CLI: a `polycall peer serve` node in its
 * own process and `polycall peer send/recv/health/register` talking to a
 * Java-hosted node. Every delivery is verified at the receiver (exact bytes,
 * sender id, message id).
 */
class CliInteropTest {
    @TempDir
    Path dir;

    private static byte[] mixedPayload() {
        byte[] head = "héllo — 世界 🌍 ".getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + 256];
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = 0; i < 256; i++) {
            out[head.length + i] = (byte) i;
        }
        return out;
    }

    @Test
    void javaPeerAndCliPeerExchangePayloadsBothWays() throws Exception {
        Path cli = Fixtures.requireCli();
        try (Fixtures.Proc cnode = Fixtures.startCliPeer(dir, "cnode");
             Peer j = Peer.open("jnode", "127.0.0.1:0", TOKEN)) {
            j.register("cnode", cnode.endpoint);
            j.ping("cnode", T);

            // Java -> C: verified by taking the message out of the C node's inbox
            byte[] payload = mixedPayload();
            j.send("cnode", payload, "j2c-1", T);
            Fixtures.Result r = Fixtures.run(List.of(cli.toString(), "peer", "recv", "--to", cnode.endpoint,
                    "-t", "5000"), dir, null, Duration.ofSeconds(20));
            assertEquals(0, r.exit(), r.stderr());
            assertEquals("jnode", Fixtures.jsonString(r.out(), "from"), r.out());
            assertEquals("j2c-1", Fixtures.jsonString(r.out(), "id"), r.out());
            assertArrayEquals(payload, Base64.getDecoder().decode(Fixtures.jsonString(r.out(), "payload_b64")));

            // Java -> C, 1 MiB, read back raw
            byte[] big = new byte[Polycall.PEER_MAX_PAYLOAD];
            new Random(3).nextBytes(big);
            j.send("cnode", big, "j2c-max", 20_000);
            r = Fixtures.run(List.of(cli.toString(), "peer", "recv", "--to", cnode.endpoint, "-t", "10000",
                    "--raw"), dir, null, Duration.ofSeconds(30));
            assertEquals(0, r.exit(), r.stderr());
            assertArrayEquals(big, r.stdout(), "1 MiB payload identical at the C node");

            // C -> Java: payload file (binary incl. NUL) and UTF-8 text
            Path f = dir.resolve("c2j.bin");
            Files.write(f, payload);
            r = Fixtures.run(List.of(cli.toString(), "peer", "send", "--from", "cnode", "--to", j.endpoint(),
                    "--id", "c2j-1", "--payload-file", f.toString()), dir, null, Duration.ofSeconds(20));
            assertEquals(0, r.exit(), r.stderr());
            PeerMessage m = j.recv(T);
            assertEquals("cnode", m.sender());
            assertEquals("c2j-1", m.messageId());
            assertArrayEquals(payload, m.payload());

            r = Fixtures.run(List.of(cli.toString(), "peer", "send", "--from", "cnode", "--to", j.endpoint(),
                    "--id", "c2j-2", "--payload", "hello from C"), dir, null, Duration.ofSeconds(20));
            assertEquals(0, r.exit(), r.stderr());
            m = j.recv(T);
            assertEquals("cnode", m.sender());
            assertEquals("c2j-2", m.messageId());
            assertEquals("hello from C", m.text());

            // the C CLI's duplicate retry is de-duplicated by the Java-hosted node
            r = Fixtures.run(List.of(cli.toString(), "peer", "send", "--from", "cnode", "--to", j.endpoint(),
                    "--id", "c2j-2", "--payload", "hello from C"), dir, null, Duration.ofSeconds(20));
            assertEquals(0, r.exit(), r.stderr());
            assertTrue(j.tryRecv(400).isEmpty(), "duplicate from the C CLI delivered twice");
        }
    }

    @Test
    void cliManagesAJavaHostedNodeOverTheWire() throws Exception {
        Path cli = Fixtures.requireCli();
        try (Peer j = Peer.open("jhost", "127.0.0.1:0", TOKEN)) {
            Fixtures.Result r = Fixtures.run(List.of(cli.toString(), "peer", "health", "--to", j.endpoint()),
                    dir, null, Duration.ofSeconds(20));
            assertEquals(0, r.exit(), r.stderr());
            assertTrue(r.out().contains("\"node_id\":\"jhost\""), r.out());

            r = Fixtures.run(List.of(cli.toString(), "peer", "register", "--to", j.endpoint(), "--id", "remote1",
                    "--peer-endpoint", "127.0.0.1:9"), dir, null, Duration.ofSeconds(20));
            assertEquals(0, r.exit(), r.stderr());
            assertTrue(j.list().contains("\"remote1\":\"127.0.0.1:9\""), j.list());

            Map<String, String> noToken = new HashMap<>();
            noToken.put("POLYCALL_DEV_TOKEN", null);
            r = Fixtures.run(List.of(cli.toString(), "peer", "peers", "--to", j.endpoint()), dir, noToken,
                    Duration.ofSeconds(20));
            assertEquals(7, r.exit(), "reading the registry without the token: " + r.out() + r.stderr());

            Map<String, String> wrong = Map.of("POLYCALL_DEV_TOKEN", "wrong-token");
            r = Fixtures.run(List.of(cli.toString(), "peer", "send", "--from", "mallory", "--to", j.endpoint(),
                    "--payload", "x"), dir, wrong, Duration.ofSeconds(20));
            assertEquals(7, r.exit(), "wrong token: " + r.out() + r.stderr());
            assertTrue(j.tryRecv(300).isEmpty(), "nothing from mallory may be queued");
        }
    }
}
