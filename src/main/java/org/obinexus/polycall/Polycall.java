package org.obinexus.polycall;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java binding for the Polycall Binding ABI v1 ({@code polycall.h}).
 *
 * <p>Every call goes straight to {@code polycall.dll} / {@code libpolycall.so.1}
 * through the Foreign Function &amp; Memory API (JDK 22+); there is no C glue.
 * Failures raise {@link PolycallException} (status, name, detail); a missing
 * or incompatible library raises {@link PolycallLoadException}. All methods
 * are thread-safe.</p>
 *
 * <p>Run the JVM with {@code --enable-native-access=ALL-UNNAMED} (or the
 * module name) so the JDK does not warn about native access.</p>
 */
public final class Polycall {
    /** The binding ABI this binding implements; the library must report the same. */
    public static final int ABI_VERSION = 1;
    /** Default configuration file of this binding. */
    public static final String DEFAULT_CONFIG = "java-polycallrc";
    /** {@code POLYCALL_PEER_MAX_PAYLOAD}: 1 MiB per peer message. */
    public static final int PEER_MAX_PAYLOAD = 1 << 20;
    /** {@code POLYCALL_CALL_MAX_OUTPUT}: 1 MiB of call output. */
    public static final int CALL_MAX_OUTPUT = 1 << 20;
    /** {@code POLYCALL_PEER_ID_MAX} / {@code POLYCALL_MESSAGE_ID_MAX} (incl. NUL). */
    public static final int ID_MAX = 64;
    /** {@code POLYCALL_ENDPOINT_MAX} (incl. NUL). */
    public static final int ENDPOINT_MAX = 128;

    private Polycall() {
    }

    static NativeApi api() {
        return NativeApi.get();
    }

    /** The library name or path that was loaded. */
    public static String libraryName() {
        return api().library;
    }

    /** {@code polycall_ffi_abi_version()} of the loaded library (verified == 1 at load). */
    public static int abiVersion() {
        try {
            return (int) api().abiVersion.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code polycall_ffi_version()}: the library version, e.g. "1.1.0". */
    public static String version() {
        NativeApi api = api();
        try (Arena arena = Arena.ofConfined()) {
            int cap = 32;
            while (true) {
                MemorySegment buf = arena.allocate(cap);
                int n = (int) api.ffiVersion.invokeExact(buf, cap);
                if (n < 0) {
                    throw error(n, "polycall_ffi_version");
                }
                if (n < cap) {
                    return buf.getString(0);
                }
                cap = n + 1;
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code polycall_get_version()} (the 1.0 API, kept by the library). */
    public static String coreVersion() {
        try {
            MemorySegment p = (MemorySegment) api().getVersion.invokeExact();
            return p.equals(MemorySegment.NULL) ? "" : p.reinterpret(Integer.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code polycall_strerror(status)}: a static, never-null name and description. */
    public static String strerror(int status) {
        try {
            MemorySegment p = (MemorySegment) api().strerror.invokeExact(status);
            return p.equals(MemorySegment.NULL) ? "" : p.reinterpret(Integer.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code polycall_last_error()}: this thread's detail for its most recent failed call. */
    public static String lastError() {
        NativeApi api = api();
        try (Arena arena = Arena.ofConfined()) {
            long cap = 1024;
            while (true) {
                MemorySegment buf = arena.allocate(cap);
                int n = (int) api.lastError.invokeExact(buf, cap);
                if (n < cap) {
                    return buf.getString(0);
                }
                cap = (long) n + 1;
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The binding's documented entry point: {@code polycall_ffi_run_config(path, 1)}.
     * Validates the configuration for running with this build (unknown keys
     * and unsupported settings such as {@code tls_enabled=true} are errors).
     */
    public static void runConfig(String configPath) {
        runConfig(configPath, true);
    }

    /**
     * {@code polycall_ffi_run_config(path, strict ? 1 : 0)}; throws
     * {@link PolycallException} on failure. Never starts a service.
     */
    public static void runConfig(String configPath, boolean strict) {
        int st = runConfigStatus(configPath, strict);
        if (st != Status.OK) {
            throw error(st, "run_config(" + configPath + ")");
        }
    }

    /** {@code polycall_ffi_run_config} returning the unchanged status code. */
    public static int runConfigStatus(String configPath, boolean strict) {
        NativeApi api = api();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = cstr(arena, configPath, "configPath", false);
            return (int) api.runConfig.invokeExact(path, strict ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code polycall_ffi_describe()}: JSON description of a configuration file. */
    public static String describe(String configPath) {
        NativeApi api = api();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = cstr(arena, configPath, "configPath", false);
            int cap = 64 * 1024;
            while (true) {
                MemorySegment buf = arena.allocate(cap);
                int n = (int) api.describe.invokeExact(path, buf, cap);
                if (n < 0) {
                    throw error(n, "describe(" + configPath + ")");
                }
                if (n < cap) {
                    return buf.getString(0);
                }
                cap = n + 1;
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * One {@code polycall_rpc} v1 round trip ({@code polycall_call}) to a
     * running runtime ({@code polycall start} / {@code polycall daemon start}).
     *
     * @param endpoint   "host:port"
     * @param inputJson  JSON input, or null for {@code null}
     * @param timeoutMs  1..600000
     * @return the operation's output JSON
     * @throws PolycallException on failure; for remote errors
     *         {@link PolycallException#remoteError()} holds the error object
     */
    public static String call(String endpoint, String service, String operation,
                              String inputJson, long timeoutMs) {
        NativeApi api = api();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ep = cstr(arena, endpoint, "endpoint", false);
            MemorySegment svc = cstr(arena, service, "service", false);
            MemorySegment op = cstr(arena, operation, "operation", false);
            MemorySegment in = cstr(arena, inputJson, "inputJson", true);
            int timeout = uint32(timeoutMs, "timeoutMs");
            long cap = CALL_MAX_OUTPUT + 1L;
            MemorySegment out = arena.allocate(cap);
            MemorySegment outLen = arena.allocate(JAVA_LONG);
            int st = (int) api.call.invokeExact(ep, svc, op, in, timeout, out, cap, outLen);
            if (st == Status.OK) {
                return out.getString(0);
            }
            long len = outLen.get(JAVA_LONG, 0);
            String remote = null;
            long required = -1;
            if (st == Status.E_TOO_LARGE) {
                required = len;
            } else if (len > 0) {
                remote = out.getString(0);
            }
            throw error(st, service + "." + operation + " at " + endpoint, remote, required);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- helpers shared with Peer ------------------------------------------------

    /** Build the exception for a failed call; reads polycall_last_error first. */
    static PolycallException error(int status, String context) {
        return error(status, context, null, -1);
    }

    static PolycallException error(int status, String context, String remote, long required) {
        String detail = lastError();
        return new PolycallException(status, strerror(status), detail, context, remote, required);
    }

    /** A binding-side argument error reported with the library's status vocabulary. */
    static PolycallException argumentError(String detail) {
        return new PolycallException(Status.E_INVALID_ARGUMENT, strerror(Status.E_INVALID_ARGUMENT),
                detail, "binding", null, -1);
    }

    static void check(int status, String context) {
        if (status != Status.OK) {
            throw error(status, context);
        }
    }

    /** NUL-terminated UTF-8 copy of {@code s}; NULL when allowed and {@code s} is null. */
    static MemorySegment cstr(Arena arena, String s, String name, boolean nullable) {
        if (s == null) {
            if (nullable) {
                return MemorySegment.NULL;
            }
            throw argumentError(name + " is null");
        }
        if (s.indexOf('\0') >= 0) {
            throw argumentError(name + " contains a NUL character");
        }
        return arena.allocateFrom(s, StandardCharsets.UTF_8);
    }

    /** Validate a uint32_t millisecond value and return its bit pattern. */
    static int uint32(long value, String name) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw argumentError(name + " must be 0.." + 0xFFFF_FFFFL + ", got " + value);
        }
        return (int) value;
    }

    /** Read a NUL-terminated string from a library-filled buffer. */
    static String readString(MemorySegment buf) {
        return buf.getString(0, StandardCharsets.UTF_8);
    }

    /** Fill a text-returning call into a growing buffer (snprintf rules with out_len). */
    interface TextCall {
        int invoke(MemorySegment buf, long cap, MemorySegment outLen) throws Throwable;
    }

    static String text(String context, TextCall call) {
        try (Arena arena = Arena.ofConfined()) {
            long cap = 4096;
            MemorySegment outLen = arena.allocate(JAVA_LONG);
            while (true) {
                MemorySegment buf = arena.allocate(cap);
                int st = call.invoke(buf, cap, outLen);
                if (st == Status.OK) {
                    return readString(buf);
                }
                long need = outLen.get(JAVA_LONG, 0);
                if (st == Status.E_TOO_LARGE && need >= cap) {
                    cap = need + 1;
                    continue;
                }
                throw error(st, context);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException("unexpected checked exception from a native call", t);
    }
}
