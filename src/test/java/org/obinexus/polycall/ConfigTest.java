package org.obinexus.polycall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** polycall_ffi_run_config / polycall_ffi_describe against the real library. */
class ConfigTest {
    @TempDir
    Path dir;

    private String write(String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, text);
        return p.toString();
    }

    @Test
    void validConfigPassesStrictAndLenient() throws Exception {
        String f = write("valid-polycallrc", "log_level=info\nmax_connections=10\nnetwork_timeout=500\ntls_enabled=false\n");
        assertDoesNotThrow(() -> Polycall.runConfig(f));
        assertDoesNotThrow(() -> Polycall.runConfig(f, false));
        assertEquals(Status.OK, Polycall.runConfigStatus(f, true));
    }

    @Test
    void missingFileIsNotFound() {
        String f = dir.resolve("absent-polycallrc").toString();
        PolycallException e = Fixtures.expectStatus(Status.E_NOT_FOUND, () -> Polycall.runConfig(f));
        assertTrue(e.getMessage().contains("POLYCALL_E_NOT_FOUND"), e.getMessage());
    }

    @Test
    void invalidValueIsConfigErrorWithDetail() throws Exception {
        String f = write("invalid-polycallrc", "max_connections=lots\n");
        PolycallException e = Fixtures.expectStatus(Status.E_CONFIG, () -> Polycall.runConfig(f, false));
        assertTrue(e.detail().contains("max_connections"), e.detail());
        Fixtures.expectStatus(Status.E_CONFIG, () -> Polycall.runConfig(f, true));
    }

    @Test
    void malformedSyntaxIsConfigError() throws Exception {
        String f = write("garbage-polycallrc", "this is not a config line\n");
        Fixtures.expectStatus(Status.E_CONFIG, () -> Polycall.runConfig(f));
    }

    @Test
    void unknownKeyIsWarningLenientButErrorStrict() throws Exception {
        String f = write("unknown-polycallrc", "log_level=info\nbogus_key=1\n");
        assertDoesNotThrow(() -> Polycall.runConfig(f, false));
        PolycallException e = Fixtures.expectStatus(Status.E_CONFIG, () -> Polycall.runConfig(f, true));
        assertTrue(e.detail().contains("bogus_key"), e.detail());
    }

    @Test
    void tlsEnabledIsRefusedWhenRunningStrict() throws Exception {
        String f = write("tls-polycallrc", "tls_enabled=true\ncert_file=/x/c.pem\nkey_file=/x/k.pem\n");
        assertDoesNotThrow(() -> Polycall.runConfig(f, false));
        PolycallException e = Fixtures.expectStatus(Status.E_UNSUPPORTED, () -> Polycall.runConfig(f));
        assertTrue(e.detail().contains("tls_enabled"), e.detail());
    }

    @Test
    void emptyNullAndNulPathsAreInvalidArguments() {
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT, () -> Polycall.runConfig(""));
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT, () -> Polycall.runConfig(null));
        Fixtures.expectStatus(Status.E_INVALID_ARGUMENT, () -> Polycall.runConfig("a\0b"));
        assertEquals(Status.E_INVALID_ARGUMENT, Polycall.runConfigStatus("", true));
    }

    @Test
    void utf8PathIsPassedIntact() throws Exception {
        String f = write("café-世界-polycallrc", "log_level=info\n");
        assertDoesNotThrow(() -> Polycall.runConfig(f));
    }

    @Test
    void shippedConfigurationsValidateForRunning() {
        // the repository's own rc files must be valid for running with this build
        for (String f : new String[] {"java-polycallrc", "examples/java-polycallrc"}) {
            Path p = Path.of(f).toAbsolutePath();
            assertTrue(Files.isRegularFile(p), "missing " + p);
            assertDoesNotThrow(() -> Polycall.runConfig(p.toString()), f);
        }
    }

    @Test
    void describeReturnsJson() throws Exception {
        String f = write("describe-polycallrc", "log_level=debug\nmax_connections=7\n");
        String json = Polycall.describe(f);
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertTrue(json.contains("max_connections"), json);
        Fixtures.expectStatus(Status.E_NOT_FOUND, () -> Polycall.describe(dir.resolve("nope").toString()));
    }
}
