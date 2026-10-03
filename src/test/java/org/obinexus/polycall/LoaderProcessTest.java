package org.obinexus.polycall;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documented loading path end to end, in a fresh JVM per case: the
 * {@code java-polycall} command line with {@code POLYCALL_LIBRARY} naming a
 * missing file, a library without the binding ABI, a real 1.0 core or an
 * ABI-2 fake must exit 8 with a clear message naming the library -- never
 * crash the JVM (no hs_err file, no fatal-error banner).
 */
class LoaderProcessTest {
    @TempDir
    Path dir;

    private Fixtures.Result cli(String library, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Fixtures.WINDOWS ? "java.exe" : "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> cmd = new ArrayList<>(List.of(java, "--enable-native-access=ALL-UNNAMED", "-cp", cp,
                "org.obinexus.polycall.cli.Main"));
        cmd.addAll(List.of(args));
        Map<String, String> env = new HashMap<>();
        env.put("POLYCALL_LIBRARY", library);
        Fixtures.Result r = Fixtures.run(cmd, dir, env, Duration.ofSeconds(60));
        String all = r.out() + r.stderr();
        assertFalse(all.contains("A fatal error has been detected"), all);
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("hs_err")), "JVM crash log written");
        }
        return r;
    }

    private void assertLoadError(Fixtures.Result r, String library, String reason) {
        assertEquals(8, r.exit(), r.out() + r.stderr());
        assertTrue(r.stderr().contains("cannot use library '" + library + "'"), r.stderr());
        assertTrue(r.stderr().contains(reason), r.stderr());
        assertTrue(r.stderr().contains("(selected by POLYCALL_LIBRARY)"), r.stderr());
    }

    @Test
    void theRealLibraryLoads() throws Exception {
        String lib = System.getenv("POLYCALL_LIBRARY");
        Assumptions.assumeTrue(lib != null && !lib.isBlank(), "POLYCALL_LIBRARY is not set for this run");
        Fixtures.Result r = cli(lib, "version");
        assertEquals(0, r.exit(), r.stderr());
        assertTrue(r.out().contains("(binding ABI 1) loaded from " + lib), r.out());
    }

    @Test
    void missingLibrary() throws Exception {
        String bogus = dir.resolve(Fixtures.WINDOWS ? "no-such-polycall.dll" : "libno-such-polycall.so.1").toString();
        assertLoadError(cli(bogus, "version"), bogus, "cannot load the library");
    }

    @Test
    void libraryWithoutTheBindingAbi() throws Exception {
        String other = Fixtures.WINDOWS ? "kernel32.dll" : "libc.so.6";
        assertLoadError(cli(other, "version"), other, "missing symbol(s)");
    }

    @Test
    void realOneZeroCore() throws Exception {
        String old = System.getenv("POLYCALL_TEST_V1_0_LIBRARY");
        Assumptions.assumeTrue(old != null && !old.isBlank(),
                "POLYCALL_TEST_V1_0_LIBRARY not set (no libpolycall 1.0.x build provided)");
        Fixtures.Result r = cli(old, "version");
        assertLoadError(r, old, "missing symbol(s)");
        assertTrue(r.stderr().contains("older 1.0 core"), r.stderr());
    }

    @Test
    void abiMismatch() throws Exception {
        String fake = System.getenv("POLYCALL_TEST_FAKE_ABI2");
        Assumptions.assumeTrue(fake != null && !fake.isBlank(),
                "POLYCALL_TEST_FAKE_ABI2 not set (fake ABI-2 library not built)");
        assertLoadError(cli(fake, "version"), fake, "polycall_ffi_abi_version() returned 2");
    }
}
