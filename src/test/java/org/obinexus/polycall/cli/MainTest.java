package org.obinexus.polycall.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.obinexus.polycall.Status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The java-polycall CLI over the real library (in-process). */
class MainTest {
    @TempDir
    Path dir;

    private record Out(int code, String out, String err) {
    }

    private static Out run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Out(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void versionReportsTheLoadedLibrary() {
        Out r = run("version");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("binding ABI 1"), r.out());
    }

    @Test
    void validateAndDescribe() throws Exception {
        Path ok = dir.resolve("ok-polycallrc");
        Files.writeString(ok, "log_level=info\n");
        assertEquals(0, run("validate", ok.toString()).code());
        Path tls = dir.resolve("tls-polycallrc");
        Files.writeString(tls, "tls_enabled=true\ncert_file=/c\nkey_file=/k\n");
        assertEquals(4, run("validate", tls.toString()).code(), "unsupported TLS");
        assertEquals(0, run("validate", "--lenient", tls.toString()).code());
        Path bad = dir.resolve("bad-polycallrc");
        Files.writeString(bad, "max_connections=lots\n");
        Out r = run("validate", bad.toString());
        assertEquals(3, r.code());
        assertTrue(r.err().contains("max_connections"), r.err());
        r = run("describe", ok.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().trim().startsWith("{"), r.out());
    }

    @Test
    void usageErrors() {
        assertEquals(2, run("frobnicate").code());
        assertEquals(2, run("call", "debug").code());
        assertEquals(2, run("peer", "echo").code());
        assertEquals(0, run().code());
    }

    @Test
    void exitCodeMapping() {
        assertEquals(5, Main.exitCode(Status.E_TRANSPORT));
        assertEquals(6, Main.exitCode(Status.E_TIMEOUT));
        assertEquals(7, Main.exitCode(Status.E_AUTH));
        assertEquals(1, Main.exitCode(Status.E_INTERNAL));
    }
}
