package org.obinexus.polycall;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/** Real-process fixtures: the installed polycall CLI, runtimes and peer nodes. */
final class Fixtures {
    private Fixtures() {
    }

    /** Random shared token for this test run (never a real secret). */
    static final String TOKEN = "qa-token-" + UUID.randomUUID();
    static final long T = 5_000;
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** The polycall CLI: $POLYCALL_CLI, else polycall(.exe) on PATH. */
    static Optional<Path> cli() {
        String explicit = System.getenv("POLYCALL_CLI");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        String exe = WINDOWS ? "polycall.exe" : "polycall";
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isEmpty()) {
                    continue;
                }
                Path p = Path.of(dir, exe);
                if (Files.isRegularFile(p)) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    /** The CLI, or abort the test as SKIPPED (never passed) when it is absent. */
    static Path requireCli() {
        Optional<Path> c = cli();
        Assumptions.assumeTrue(c.isPresent(),
                "polycall CLI not found (set POLYCALL_CLI or put polycall on PATH)");
        return c.get();
    }

    static void environment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.put("POLYCALL_DEV_TOKEN", TOKEN);
        env.put("POLYCALL_TELEMETRY", "off");
    }

    /** A started background process with its log and resolved endpoint. */
    static final class Proc implements AutoCloseable {
        final Process process;
        final Path log;
        final String endpoint;

        Proc(Process process, Path log, String endpoint) {
            this.process = process;
            this.log = log;
            this.endpoint = endpoint;
        }

        String log() {
            try {
                return Files.readString(log, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "<no log: " + e + ">";
            }
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Wait until {@code file} holds a complete line; return it trimmed. */
    static String waitForLine(Path file, Process owner, Duration limit) throws Exception {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (Files.exists(file)) {
                String s = Files.readString(file, StandardCharsets.UTF_8);
                if (!s.isBlank() && (s.endsWith("\n") || s.trim().matches(".+:\\d+"))) {
                    return s.trim();
                }
            }
            if (owner != null && !owner.isAlive()) {
                break;
            }
            Thread.sleep(50);
        }
        return null;
    }

    static Proc start(Path dir, String name, List<String> cmd) throws Exception {
        Path epFile = dir.resolve(name + ".ep");
        Path log = dir.resolve(name + ".log");
        List<String> full = new ArrayList<>(cmd);
        full.add("--endpoint-file");
        full.add(epFile.toString());
        ProcessBuilder pb = new ProcessBuilder(full).directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        environment(pb);
        Process p = pb.start();
        String ep = waitForLine(epFile, p, Duration.ofSeconds(20));
        if (ep == null) {
            p.destroyForcibly();
            fail(name + " did not report an endpoint: " + Files.readString(log));
        }
        return new Proc(p, log, ep);
    }

    /** {@code polycall start} on an ephemeral loopback port. */
    static Proc startRuntime(Path dir) throws Exception {
        Path cli = requireCli();
        return start(dir, "runtime", List.of(cli.toString(), "start", "--endpoint", "127.0.0.1:0"));
    }

    /** {@code polycall peer serve} (the C CLI node) on an ephemeral loopback port. */
    static Proc startCliPeer(Path dir, String nodeId) throws Exception {
        Path cli = requireCli();
        return start(dir, "peer-" + nodeId, List.of(cli.toString(), "peer", "serve",
                "--node-id", nodeId, "--endpoint", "127.0.0.1:0"));
    }

    record Result(int exit, byte[] stdout, String stderr) {
        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    /** Run a command to completion (stdin optional) with the test token in its environment. */
    static Result run(List<String> cmd, Path dir, Map<String, String> extraEnv, Duration limit)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        environment(pb);
        if (extraEnv != null) {
            for (Map.Entry<String, String> e : extraEnv.entrySet()) {
                if (e.getValue() == null) {
                    pb.environment().remove(e.getKey());
                } else {
                    pb.environment().put(e.getKey(), e.getValue());
                }
            }
        }
        Path err = Files.createTempFile(dir, "stderr", ".txt");
        Path out = Files.createTempFile(dir, "stdout", ".bin");
        pb.redirectError(err.toFile()).redirectOutput(out.toFile());
        Process p = pb.start();
        p.getOutputStream().close(); // no stdin
        if (!p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            fail("timed out: " + cmd);
        }
        return new Result(p.exitValue(), Files.readAllBytes(out), Files.readString(err));
    }

    private static final Pattern STRING_FIELD = Pattern.compile("\"%s\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** A top-level-ish JSON string field (enough for the CLI's flat replies). */
    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile(String.format(STRING_FIELD.pattern(), Pattern.quote(key))).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static PolycallException expectStatus(int status, org.junit.jupiter.api.function.Executable action) {
        PolycallException e = assertThrows(PolycallException.class, action);
        assertEquals(Polycall.strerror(status).split(":")[0], e.statusName(), e.getMessage());
        assertEquals(status, e.status(), e.getMessage());
        return e;
    }

    static int freePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }
}
