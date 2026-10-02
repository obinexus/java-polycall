package org.obinexus.polycall;

/**
 * The Polycall library could not be used: it was not found, it lacks a
 * Binding ABI v1 symbol (an older 1.0 core), or it reports a different
 * {@code polycall_ffi_abi_version()}. Raised instead of crashing on first use.
 */
public class PolycallLoadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String library;
    private final String reason;

    public PolycallLoadException(String library, String reason) {
        this(library, reason, null);
    }

    public PolycallLoadException(String library, String reason, Throwable cause) {
        super("polycall: cannot use library '" + library + "': " + reason, cause);
        this.library = library;
        this.reason = reason;
    }

    /** The library path or name that was tried. */
    public String library() {
        return library;
    }

    /** Why it could not be used. */
    public String reason() {
        return reason;
    }
}
