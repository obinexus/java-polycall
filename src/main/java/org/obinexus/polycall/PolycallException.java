package org.obinexus.polycall;

/**
 * A Polycall call returned a negative status. Carries the status code, its
 * name from {@code polycall_strerror()} and the calling thread's detail
 * message from {@code polycall_last_error()}.
 */
public class PolycallException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String statusName;
    private final String statusText;
    private final String detail;
    private final String remoteError;
    private final long requiredSize;

    public PolycallException(int status, String statusText, String detail, String context,
                             String remoteError, long requiredSize) {
        super(format(status, statusText, detail, context));
        this.status = status;
        this.statusText = statusText;
        this.statusName = nameOf(statusText);
        this.detail = detail == null ? "" : detail;
        this.remoteError = remoteError;
        this.requiredSize = requiredSize;
    }

    private static String nameOf(String statusText) {
        int colon = statusText.indexOf(':');
        return colon > 0 ? statusText.substring(0, colon) : statusText;
    }

    private static String format(int status, String statusText, String detail, String context) {
        StringBuilder sb = new StringBuilder();
        if (context != null && !context.isEmpty()) {
            sb.append(context).append(": ");
        }
        sb.append(nameOf(statusText)).append(" (").append(status).append(')');
        if (detail != null && !detail.isEmpty()) {
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }

    /** The negative {@code POLYCALL_E_*} status code (see {@link Status}). */
    public int status() {
        return status;
    }

    /** The status name, e.g. {@code POLYCALL_E_TIMEOUT}. */
    public String statusName() {
        return statusName;
    }

    /** The full {@code polycall_strerror()} text. */
    public String statusText() {
        return statusText;
    }

    /** The library's detail message ({@code polycall_last_error()}); may be empty. */
    public String detail() {
        return detail;
    }

    /**
     * For {@link Polycall#call}: the remote error object
     * {@code {"code":..,"message":..}} when the runtime reported one, else null.
     */
    public String remoteError() {
        return remoteError;
    }

    /**
     * For {@link Status#E_TOO_LARGE} from a caller buffer: the size the
     * library needs (excluding the NUL), else -1.
     */
    public long requiredSize() {
        return requiredSize;
    }
}
