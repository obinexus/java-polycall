package org.obinexus.examples;

import org.obinexus.polycall.Peer;
import org.obinexus.polycall.PeerMessage;
import org.obinexus.polycall.Polycall;
import org.obinexus.polycall.PolycallException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * java-polycall demo: org.obinexus:java-polycall (Maven Central) driving the
 * Polycall library and a real {@code polycall start} runtime.
 *
 * <pre>
 *   POLYCALL_LIBRARY  polycall.dll / libpolycall.so.1  (or on PATH / LD_LIBRARY_PATH)
 *   POLYCALL_CLI      polycall.exe / polycall          (or on PATH)
 *
 *   mvn -q compile exec:exec
 * </pre>
 */
public final class PolycallDemo {
    private PolycallDemo() {
    }

    public static void main(String[] args) throws Exception {
        // 1. The library, loaded through the Foreign Function & Memory API
        System.out.println("== library");
        System.out.println("loaded    " + Polycall.libraryName());
        System.out.println("version   " + Polycall.version() + " (binding ABI " + Polycall.abiVersion() + ")");

        Path work = Files.createTempDirectory("polycall-demo");
        try {
            // 2. Configuration: polycall_ffi_run_config + polycall_ffi_describe
            System.out.println("\n== configuration");
            Path rc = work.resolve("java-polycallrc");
            Files.writeString(rc, "log_level=info\nmax_connections=1000\nnetwork_timeout=5000\ntls_enabled=false\n");
            Polycall.runConfig(rc.toString()); // throws PolycallException if invalid
            System.out.println("valid     " + rc.getFileName());
            System.out.println("describe  " + Polycall.describe(rc.toString()));

            // 3. Calls into a runtime started with `polycall start`
            String token = "demo-" + UUID.randomUUID();
            try (RuntimeProcess runtime = RuntimeProcess.start(cli(), work, token)) {
                String ep = runtime.endpoint;
                System.out.println("\n== polycall start on " + ep);
                System.out.println("inventory.get  -> "
                        + Polycall.call(ep, "inventory", "get", "{\"item_id\":\"widget-a\"}", 5000));
                System.out.println("debug.echo     -> "
                        + Polycall.call(ep, "debug", "echo", "{\"from\":\"java\",\"n\":1}", 5000));
                // compared in code: the console code page may not show non-ASCII text
                String utf8 = "{\"text\":\"héllo 世界\"}";
                boolean same = Polycall.call(ep, "debug", "echo", utf8, 5000).equals("{\"echo\":" + utf8 + "}");
                System.out.println("UTF-8 echo     -> " + (same ? "round trip identical" : "MISMATCH"));
                System.out.println("debug.sleep    -> "
                        + Polycall.call(ep, "debug", "sleep", "{\"ms\":200}", 5000));
                try {
                    Polycall.call(ep, "inventory", "get", "{\"item_id\":\"no-such-item\"}", 5000);
                } catch (PolycallException e) {
                    System.out.println("unknown item   -> " + e.statusName() + " " + e.remoteError());
                }
            }

            // 4. Two peer nodes in this JVM exchanging a message
            System.out.println("\n== peers");
            try (Peer alpha = Peer.open("alpha", "127.0.0.1:0", token);
                 Peer beta = Peer.open("beta", "127.0.0.1:0", token)) {
                alpha.register("beta", beta.endpoint());
                beta.register("alpha", alpha.endpoint());
                alpha.send("beta", "hello from alpha", "msg-1", 5000);
                PeerMessage m = beta.recv(5000);
                System.out.println("beta got  " + m.messageId() + " from " + m.sender() + ": " + m.text());
                beta.send("alpha", "hi alpha, beta here", "msg-2", 5000);
                m = alpha.recv(5000);
                System.out.println("alpha got " + m.messageId() + " from " + m.sender() + ": " + m.text());
            }
        } finally {
            deleteTree(work);
        }
    }

    /** The polycall CLI: $POLYCALL_CLI, else polycall(.exe) on PATH. */
    static Path cli() {
        String explicit = System.getenv("POLYCALL_CLI");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exe = windows ? "polycall.exe" : "polycall";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isRegularFile(Path.of(dir, exe))) {
                return Path.of(dir, exe);
            }
        }
        throw new IllegalStateException(exe + " not found: set POLYCALL_CLI or put it on PATH");
    }

    /** {@code polycall start} on an ephemeral loopback port; {@code polycall stop} on close. */
    static final class RuntimeProcess implements AutoCloseable {
        final Process process;
        final String endpoint;
        final Path cli;
        final String token;

        private RuntimeProcess(Process process, String endpoint, Path cli, String token) {
            this.process = process;
            this.endpoint = endpoint;
            this.cli = cli;
            this.token = token;
        }

        static RuntimeProcess start(Path cli, Path dir, String token) throws Exception {
            Path epFile = dir.resolve("runtime.ep");
            Path log = dir.resolve("runtime.log");
            ProcessBuilder pb = new ProcessBuilder(List.of(cli.toString(), "start",
                    "--endpoint", "127.0.0.1:0", "--endpoint-file", epFile.toString()))
                    .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            pb.environment().put("POLYCALL_DEV_TOKEN", token);
            pb.environment().put("POLYCALL_TELEMETRY", "off");
            Process p = pb.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < end && p.isAlive()) {
                if (Files.exists(epFile)) {
                    String ep = Files.readString(epFile, StandardCharsets.UTF_8).trim();
                    if (ep.matches(".+:\\d+")) {
                        return new RuntimeProcess(p, ep, cli, token);
                    }
                }
                Thread.sleep(50);
            }
            p.destroyForcibly();
            throw new IllegalStateException("polycall start did not report an endpoint: "
                    + Files.readString(log, StandardCharsets.UTF_8));
        }

        /** Authenticated shutdown, so the runtime runs its own cleanup; kill only as a fallback. */
        @Override
        public void close() throws Exception {
            ProcessBuilder pb = new ProcessBuilder(List.of(cli.toString(), "stop", "--endpoint", endpoint))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.environment().put("POLYCALL_DEV_TOKEN", token);
            Process stop = pb.start();
            boolean acknowledged = stop.waitFor(10, TimeUnit.SECONDS) && stop.exitValue() == 0;
            if (stop.isAlive()) {
                stop.destroyForcibly();
            }
            if (acknowledged && process.waitFor(5, TimeUnit.SECONDS)) {
                System.out.println("polycall stop  -> runtime exited with " + process.exitValue());
            } else {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                System.out.println("polycall stop  -> no clean exit; runtime killed");
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
