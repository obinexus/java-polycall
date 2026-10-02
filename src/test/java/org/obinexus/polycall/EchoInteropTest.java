package org.obinexus.polycall;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.obinexus.polycall.Fixtures.TOKEN;

/**
 * Interop with ANOTHER language binding's echo agent (e.g. fsharp-polycall,
 * kotlin-polycall). Set POLYCALL_INTEROP_ECHO to the agent's command line
 * (arguments separated by spaces; double quotes group); the agent must
 * implement the shared contract:
 *
 * <pre>AGENT peer echo --node-id ID --endpoint 127.0.0.1:0 --endpoint-file F
 *       --peer ORIGIN=HOST:PORT --count N --idle-timeout-ms MS</pre>
 *
 * receiving N messages and sending each back to its sender with message id
 * "echo-" + id. Java sends, the other binding's node receives and replies,
 * Java verifies sender, id and exact bytes of every reply. Skipped when the
 * variable is not set.
 */
class EchoInteropTest {
    @TempDir
    Path dir;

    static List<String> split(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : command.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) {
            out.add(cur.toString());
        }
        return out;
    }

    @Test
    void javaPeerExchangesPayloadsWithAnotherBindingsPeer() throws Exception {
        String command = System.getenv("POLYCALL_INTEROP_ECHO");
        Assumptions.assumeTrue(command != null && !command.isBlank(),
                "POLYCALL_INTEROP_ECHO not set (no other binding's echo agent provided)");
        String label = System.getenv().getOrDefault("POLYCALL_INTEROP_ECHO_NAME", "external");

        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) {
            binary[i] = (byte) i;
        }
        byte[] large = new byte[200_000];
        new Random(11).nextBytes(large);
        byte[] max = new byte[Polycall.PEER_MAX_PAYLOAD];
        new Random(12).nextBytes(max);
        byte[][] payloads = {
            new byte[0],
            ("hello from java to " + label).getBytes(StandardCharsets.UTF_8),
            "héllo — 世界 🌍".getBytes(StandardCharsets.UTF_8),
            binary, large, max,
        };

        try (Peer origin = Peer.open("java-origin", "127.0.0.1:0", TOKEN)) {
            List<String> cmd = new ArrayList<>(split(command));
            cmd.addAll(List.of("peer", "echo", "--node-id", "echo-agent", "--endpoint", "127.0.0.1:0",
                    "--peer", "java-origin=" + origin.endpoint(),
                    "--count", Integer.toString(payloads.length), "--idle-timeout-ms", "30000"));
            Fixtures.Proc agent = Fixtures.start(dir, "echo-agent", cmd);
            try {
                origin.register("echo-agent", agent.endpoint);
                for (int i = 0; i < payloads.length; i++) {
                    origin.send("echo-agent", payloads[i], "x" + i, 20_000);
                    PeerMessage m = origin.recv(20_000);
                    assertEquals("echo-agent", m.sender(), agent.log());
                    assertEquals("echo-x" + i, m.messageId());
                    assertArrayEquals(payloads[i], m.payload(), "payload " + i + " after the round trip");
                }
                assertTrue(agent.process.waitFor(20, TimeUnit.SECONDS), "agent did not finish: " + agent.log());
                assertEquals(0, agent.process.exitValue(), agent.log());
                String log = agent.log();
                assertNotNull(log);
                assertTrue(log.contains("\"from\":\"java-origin\""), log);
            } finally {
                agent.close();
            }
        }
        // keep the agent's transcript next to the other logs
        System.out.println("echo interop with " + label + ": " + payloads.length + " round trips verified");
    }
}
