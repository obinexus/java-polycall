package org.obinexus.polycall;

/** Binding ABI v1 status codes (polycall.h). Append-only; values are never reused. */
public final class Status {
    private Status() {
    }

    public static final int OK = 0;
    public static final int E_INVALID_ARGUMENT = -1;
    public static final int E_NO_MEMORY = -2;
    public static final int E_INVALID_HANDLE = -3;
    public static final int E_TIMEOUT = -4;
    public static final int E_TRANSPORT = -5;
    public static final int E_PROTOCOL = -6;
    public static final int E_NOT_FOUND = -7;
    public static final int E_AUTH = -8;
    public static final int E_REMOTE = -9;
    public static final int E_TOO_LARGE = -10;
    public static final int E_BUSY = -11;
    public static final int E_CANCELLED = -12;
    public static final int E_CONFIG = -13;
    public static final int E_ADDRESS_IN_USE = -14;
    public static final int E_UNSUPPORTED = -15;
    public static final int E_PERMISSION = -16;
    public static final int E_CLOSED = -17;
    public static final int E_INTERNAL = -18;
}
