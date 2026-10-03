package org.obinexus.polycall;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Library loading, version and ABI checks against the real installed library. */
class LibraryTest {

    @Test
    void abiVersionIsOne() {
        assertEquals(1, Polycall.abiVersion());
        assertEquals(Polycall.ABI_VERSION, Polycall.abiVersion());
    }

    @Test
    void versionIsAtLeast110() {
        String v = Polycall.version();
        assertTrue(v.matches("\\d+\\.\\d+\\.\\d+.*"), v);
        String[] p = v.split("[.]");
        int major = Integer.parseInt(p[0]);
        int minor = Integer.parseInt(p[1]);
        assertTrue(major > 1 || (major == 1 && minor >= 1), "binding ABI 1 needs >= 1.1.0, got " + v);
        assertFalse(Polycall.coreVersion().isEmpty(), "polycall_get_version (1.0 API)");
    }

    @Test
    void polycallLibraryEnvironmentVariableIsHonoured() {
        String env = System.getenv("POLYCALL_LIBRARY");
        Assumptions.assumeTrue(env != null && !env.isBlank(), "POLYCALL_LIBRARY is not set for this run");
        assertEquals(env, Polycall.libraryName());
    }

    @Test
    void platformLibraryNameIsUsedWithoutPolycallLibrary() {
        String env = System.getenv("POLYCALL_LIBRARY");
        String prop = System.getProperty("polycall.library");
        Assumptions.assumeTrue((env == null || env.isBlank()) && (prop == null || prop.isBlank()),
                "POLYCALL_LIBRARY is set for this run (the default search is covered by a separate run)");
        assertTrue(NativeApi.defaultNames().contains(Polycall.libraryName()), Polycall.libraryName());
    }

    @Test
    void strerrorNamesEveryStatus() {
        String[] names = {"POLYCALL_OK", "POLYCALL_E_INVALID_ARGUMENT", "POLYCALL_E_NO_MEMORY",
            "POLYCALL_E_INVALID_HANDLE", "POLYCALL_E_TIMEOUT", "POLYCALL_E_TRANSPORT",
            "POLYCALL_E_PROTOCOL", "POLYCALL_E_NOT_FOUND", "POLYCALL_E_AUTH", "POLYCALL_E_REMOTE",
            "POLYCALL_E_TOO_LARGE", "POLYCALL_E_BUSY", "POLYCALL_E_CANCELLED", "POLYCALL_E_CONFIG",
            "POLYCALL_E_ADDRESS_IN_USE", "POLYCALL_E_UNSUPPORTED", "POLYCALL_E_PERMISSION",
            "POLYCALL_E_CLOSED", "POLYCALL_E_INTERNAL"};
        for (int i = 0; i < names.length; i++) {
            String s = Polycall.strerror(-i);
            assertTrue(s.startsWith(names[i] + ":"), -i + " -> " + s);
        }
        assertTrue(Polycall.strerror(-999).startsWith("POLYCALL_E_UNKNOWN"));
        assertTrue(Polycall.strerror(12345).startsWith("POLYCALL_E_UNKNOWN"));
    }

    @Test
    void lastErrorIsPerThreadDetailOfTheLastFailure(@TempDir Path dir) throws Exception {
        assertEquals(Status.E_NOT_FOUND,
                Polycall.runConfigStatus(dir.resolve("missing-polycallrc").toString(), true));
        String detail = Polycall.lastError();
        assertFalse(detail.isEmpty(), "detail after a failure");
        // another thread's state is independent
        String[] other = new String[1];
        Thread t = new Thread(() -> other[0] = Polycall.lastError());
        t.start();
        t.join();
        assertEquals("", other[0]);
        assertEquals(detail, Polycall.lastError());
    }

    @Test
    void missingLibraryIsAClearErrorNotACrash(@TempDir Path dir) {
        String bogus = dir.resolve(Fixtures.WINDOWS ? "no-such-polycall.dll" : "libno-such-polycall.so.1").toString();
        PolycallLoadException e = assertThrows(PolycallLoadException.class, () -> NativeApi.load(bogus));
        assertEquals(bogus, e.library());
        assertTrue(e.getMessage().contains("cannot load the library"), e.getMessage());
    }

    @Test
    void libraryWithoutBindingAbiSymbolsIsAClearError() {
        // a real system library that lacks every polycall symbol, like a pre-1.1 core
        String other = Fixtures.WINDOWS ? "kernel32.dll" : "libc.so.6";
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("mac"), "no libc.so.6 on macOS");
        PolycallLoadException e = assertThrows(PolycallLoadException.class, () -> NativeApi.load(other));
        assertTrue(e.reason().contains("missing symbol(s)"), e.getMessage());
        assertTrue(e.reason().contains("polycall_ffi_abi_version"), e.getMessage());
    }

    /**
     * A REAL pre-ABI core: libpolycall built from polycall v1.0.0 (commit
     * 9fa354a), which exports only the 1.0 API. POLYCALL_TEST_V1_0_LIBRARY
     * names that build; the test is skipped (never passed) without it.
     */
    @Test
    void realOneZeroCoreIsRefusedWithAClearError() {
        String old = System.getenv("POLYCALL_TEST_V1_0_LIBRARY");
        Assumptions.assumeTrue(old != null && !old.isBlank(),
                "POLYCALL_TEST_V1_0_LIBRARY not set (no libpolycall 1.0.x build provided)");
        PolycallLoadException e = assertThrows(PolycallLoadException.class, () -> NativeApi.load(old));
        assertEquals(old, e.library());
        assertTrue(e.reason().contains("missing symbol(s)"), e.getMessage());
        assertTrue(e.reason().contains("polycall_ffi_abi_version"), e.getMessage());
        assertTrue(e.reason().contains("older 1.0 core"), e.getMessage());
        assertFalse(e.reason().contains("polycall_get_version"), "the 1.0 API symbol is present: " + e.getMessage());
    }

    /**
     * ABI mismatch: a clearly-labelled FAKE library (src/test/c/fake_polycall_abi2.c,
     * built by the test scripts) exporting every symbol with
     * polycall_ffi_abi_version() == 2. It exercises only the binding's loader.
     */
    @Test
    void abiMismatchIsRefused() {
        String fake = System.getenv("POLYCALL_TEST_FAKE_ABI2");
        Assumptions.assumeTrue(fake != null && !fake.isBlank(),
                "POLYCALL_TEST_FAKE_ABI2 not set (fake ABI-2 library not built)");
        PolycallLoadException e = assertThrows(PolycallLoadException.class, () -> NativeApi.load(fake));
        assertTrue(e.reason().contains("returned 2"), e.getMessage());
    }

    @Test
    void defaultNamesFollowThePlatformContract() {
        if (Fixtures.WINDOWS) {
            assertEquals(java.util.List.of("polycall.dll", "libpolycall.dll"), NativeApi.defaultNames());
        } else if (!System.getProperty("os.name").toLowerCase().contains("mac")) {
            assertEquals(java.util.List.of("libpolycall.so.1"), NativeApi.defaultNames());
        }
    }
}
