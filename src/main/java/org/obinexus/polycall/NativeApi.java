package org.obinexus.polycall;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Method handles for every Binding ABI v1 function of polycall.h, resolved
 * up front through the Java Foreign Function &amp; Memory API (no C glue).
 *
 * <p>Loading order (docs/BINDING_ABI.md): the {@code POLYCALL_LIBRARY}
 * environment variable, then the {@code polycall.library} system property,
 * then the platform name ({@code polycall.dll} / {@code libpolycall.dll} on
 * Windows, {@code libpolycall.so.1} on Linux, {@code libpolycall.1.dylib} on
 * macOS) through the operating system's normal library search.</p>
 */
final class NativeApi {
    /** Environment variable naming an explicit library path. */
    static final String LIBRARY_ENV = "POLYCALL_LIBRARY";
    /** System property naming an explicit library path (after the env var). */
    static final String LIBRARY_PROPERTY = "polycall.library";

    final String library;

    final MethodHandle abiVersion;
    final MethodHandle ffiVersion;
    final MethodHandle strerror;
    final MethodHandle lastError;
    final MethodHandle getVersion;
    final MethodHandle runConfig;
    final MethodHandle describe;
    final MethodHandle call;
    final MethodHandle peerOpen;
    final MethodHandle peerClose;
    final MethodHandle peerEndpoint;
    final MethodHandle peerNodeId;
    final MethodHandle peerRegister;
    final MethodHandle peerUnregister;
    final MethodHandle peerList;
    final MethodHandle peerPing;
    final MethodHandle peerSend;
    final MethodHandle peerRecv;
    final MethodHandle peerCancel;
    final MethodHandle peerHealth;

    private NativeApi(String library, SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();
        MemoryLayout sizeT = linker.canonicalLayouts().get("size_t");
        if (sizeT == null || sizeT.byteSize() != 8) {
            throw new PolycallLoadException(library,
                    "only 64-bit platforms are supported (size_t is "
                            + (sizeT == null ? "unknown" : sizeT.byteSize() + " bytes") + ")");
        }
        this.library = library;
        List<String> missing = new ArrayList<>();
        Resolver r = new Resolver(linker, lookup, missing);

        abiVersion = r.get("polycall_ffi_abi_version", FunctionDescriptor.of(JAVA_INT));
        ffiVersion = r.get("polycall_ffi_version", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        strerror = r.get("polycall_strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        lastError = r.get("polycall_last_error", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        getVersion = r.get("polycall_get_version", FunctionDescriptor.of(ADDRESS));
        runConfig = r.get("polycall_ffi_run_config", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        describe = r.get("polycall_ffi_describe",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        call = r.get("polycall_call", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        peerOpen = r.get("polycall_peer_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        peerClose = r.get("polycall_peer_close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        peerEndpoint = r.get("polycall_peer_endpoint",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
        peerNodeId = r.get("polycall_peer_node_id",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
        peerRegister = r.get("polycall_peer_register",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        peerUnregister = r.get("polycall_peer_unregister",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        peerList = r.get("polycall_peer_list",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        peerPing = r.get("polycall_peer_ping",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        peerSend = r.get("polycall_peer_send", FunctionDescriptor.of(JAVA_INT,
                JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
        peerRecv = r.get("polycall_peer_recv", FunctionDescriptor.of(JAVA_INT,
                JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
        peerCancel = r.get("polycall_peer_cancel", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        peerHealth = r.get("polycall_peer_health",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

        if (!missing.isEmpty()) {
            throw new PolycallLoadException(library, "missing symbol(s) " + missing
                    + ": this is not a libpolycall >= 1.1.0 with binding ABI "
                    + Polycall.ABI_VERSION + " (an older 1.0 core?)");
        }
        int abi;
        try {
            abi = (int) abiVersion.invokeExact();
        } catch (Throwable t) {
            throw new PolycallLoadException(library, "polycall_ffi_abi_version() failed: " + t, t);
        }
        if (abi != Polycall.ABI_VERSION) {
            throw new PolycallLoadException(library, "polycall_ffi_abi_version() returned " + abi
                    + ", this binding implements binding ABI " + Polycall.ABI_VERSION);
        }
    }

    private static final class Resolver {
        private final Linker linker;
        private final SymbolLookup lookup;
        private final List<String> missing;

        Resolver(Linker linker, SymbolLookup lookup, List<String> missing) {
            this.linker = linker;
            this.lookup = lookup;
            this.missing = missing;
        }

        MethodHandle get(String name, FunctionDescriptor fd) {
            MemorySegment sym = lookup.find(name).orElse(null);
            if (sym == null) {
                missing.add(name);
                return null;
            }
            return linker.downcallHandle(sym, fd);
        }
    }

    /** Platform library names tried, in order, when no explicit path is given. */
    static List<String> defaultNames() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("polycall.dll", "libpolycall.dll");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("libpolycall.1.dylib");
        }
        return List.of("libpolycall.so.1");
    }

    /**
     * Load and verify one specific library (a path or a bare name for the OS
     * loader). Throws {@link PolycallLoadException}; never crashes on a
     * missing library, missing symbol or ABI mismatch.
     */
    static NativeApi load(String library) {
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(library, Arena.global());
        } catch (IllegalArgumentException | IllegalCallerException e) {
            throw new PolycallLoadException(library, "cannot load the library: " + e.getMessage(), e);
        }
        return new NativeApi(library, lookup);
    }

    /** Resolve the library following the documented search order. */
    static NativeApi loadDefault() {
        String explicit = System.getenv(LIBRARY_ENV);
        String source = LIBRARY_ENV;
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getProperty(LIBRARY_PROPERTY);
            source = "-D" + LIBRARY_PROPERTY;
        }
        if (explicit != null && !explicit.isBlank()) {
            try {
                return load(explicit);
            } catch (PolycallLoadException e) {
                throw new PolycallLoadException(explicit,
                        e.reason() + " (selected by " + source + ")", e);
            }
        }
        List<String> tried = new ArrayList<>();
        PolycallLoadException last = null;
        for (String name : defaultNames()) {
            try {
                return load(name);
            } catch (PolycallLoadException e) {
                // a library that loads but is wrong (missing symbols, ABI) is fatal
                if (!e.reason().startsWith("cannot load the library")) {
                    throw e;
                }
                tried.add(name);
                last = e;
            }
        }
        throw new PolycallLoadException(String.join(", ", tried),
                "libpolycall was not found; set " + LIBRARY_ENV
                        + " to the full path of the library or put it on the system library path", last);
    }

    /** Either the loaded {@link NativeApi} or the {@link PolycallLoadException} it failed with. */
    private static volatile Object state;

    /**
     * The process-wide library, loaded on first use. A load failure is
     * remembered and reported again (as a fresh exception) on every call.
     */
    static NativeApi get() {
        Object s = state;
        if (s == null) {
            synchronized (NativeApi.class) {
                s = state;
                if (s == null) {
                    try {
                        s = loadDefault();
                    } catch (PolycallLoadException e) {
                        s = e;
                    }
                    state = s;
                }
            }
        }
        if (s instanceof NativeApi api) {
            return api;
        }
        PolycallLoadException e = (PolycallLoadException) s;
        throw new PolycallLoadException(e.library(), e.reason(), e);
    }
}
