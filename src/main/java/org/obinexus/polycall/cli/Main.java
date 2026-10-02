package org.obinexus.polycall.cli;

import org.obinexus.polycall.Peer;
import org.obinexus.polycall.PeerMessage;
import org.obinexus.polycall.Polycall;
import org.obinexus.polycall.PolycallException;
import org.obinexus.polycall.PolycallLoadException;
import org.obinexus.polycall.Status;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * {@code java-polycall} command line: a thin front end over the binding.
 *
 * <pre>
 * java-polycall version
 * java-polycall validate [--lenient] FILE
 * java-polycall describe FILE
 * java-polycall call SERVICE OPERATION --endpoint H:P [--input JSON] [--timeout-ms N]
 * java-polycall peer echo --node-id ID [--endpoint H:P] [--endpoint-file F]
 *                         [--peer ID=H:P ...] [--count N] [--idle-timeout-ms N]
 *                         [--auth-token-env NAME]
 * </pre>
 *
 * Exit codes follow the polycall CLI: 0 ok, 1 failure, 2 usage / invalid
 * argument, 3 configuration, 4 not found / unsupported, 5 transport,
 * 6 deadline, 7 authentication, 8 library cannot be loaded.
 */
public final class Main {
    private Main() {
    }

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
                usage(out);
                return 0;
            }
            switch (args[0]) {
                case "version":
                    out.println("java-polycall: libpolycall " + Polycall.version()
                            + " (binding ABI " + Polycall.abiVersion() + ") loaded from "
                            + Polycall.libraryName());
                    return 0;
                case "validate":
                    return validate(args, out);
                case "describe":
                    if (args.length != 2) {
                        throw new UsageException("usage: describe FILE");
                    }
                    out.println(Polycall.describe(args[1]));
                    return 0;
                case "call":
                    return call(args, out);
                case "peer":
                    if (args.length >= 2 && args[1].equals("echo")) {
                        return echo(args, out, err);
                    }
                    throw new UsageException("usage: peer echo ...");
                default:
                    throw new UsageException("unknown command '" + args[0] + "'");
            }
        } catch (UsageException e) {
            err.println("java-polycall: " + e.getMessage());
            usage(err);
            return 2;
        } catch (PolycallLoadException e) {
            err.println(e.getMessage());
            return 8;
        } catch (PolycallException e) {
            err.println("java-polycall: " + e.getMessage());
            if (e.remoteError() != null) {
                err.println(e.remoteError());
            }
            return exitCode(e.status());
        }
    }

    static int exitCode(int status) {
        return switch (status) {
            case Status.OK -> 0;
            case Status.E_INVALID_ARGUMENT, Status.E_TOO_LARGE -> 2;
            case Status.E_CONFIG -> 3;
            case Status.E_NOT_FOUND, Status.E_UNSUPPORTED -> 4;
            case Status.E_TRANSPORT -> 5;
            case Status.E_TIMEOUT -> 6;
            case Status.E_AUTH -> 7;
            default -> 1;
        };
    }

    private static void usage(PrintStream s) {
        s.println("usage: java-polycall version");
        s.println("       java-polycall validate [--lenient] FILE");
        s.println("       java-polycall describe FILE");
        s.println("       java-polycall call SERVICE OPERATION --endpoint H:P [--input JSON] [--timeout-ms N]");
        s.println("       java-polycall peer echo --node-id ID [--endpoint H:P] [--endpoint-file F]");
        s.println("                               [--peer ID=H:P ...] [--count N] [--idle-timeout-ms N]");
        s.println("                               [--auth-token-env NAME]");
        s.println("The library is found via POLYCALL_LIBRARY, then the platform name.");
    }

    private static int validate(String[] args, PrintStream out) {
        boolean strict = true;
        String file = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--lenient")) {
                strict = false;
            } else if (file == null && !args[i].startsWith("--")) {
                file = args[i];
            } else {
                throw new UsageException("usage: validate [--lenient] FILE");
            }
        }
        if (file == null) {
            throw new UsageException("usage: validate [--lenient] FILE");
        }
        Polycall.runConfig(file, strict);
        out.println(file + " is valid" + (strict ? " for running with this build" : ""));
        return 0;
    }

    private static String value(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new UsageException(args[i] + " needs a value");
        }
        return args[i + 1];
    }

    private static long number(String[] args, int i) {
        String v = value(args, i);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new UsageException(args[i] + " must be an integer, got '" + v + "'");
        }
    }

    private static int call(String[] args, PrintStream out) {
        if (args.length < 3) {
            throw new UsageException("usage: call SERVICE OPERATION --endpoint H:P [--input JSON] [--timeout-ms N]");
        }
        String endpoint = null;
        String input = null;
        long timeout = 5000;
        for (int i = 3; i < args.length; i += 2) {
            switch (args[i]) {
                case "--endpoint" -> endpoint = value(args, i);
                case "--input" -> input = value(args, i);
                case "--timeout-ms" -> timeout = number(args, i);
                default -> throw new UsageException("unknown option '" + args[i] + "'");
            }
        }
        if (endpoint == null) {
            throw new UsageException("call needs --endpoint H:P");
        }
        out.println(Polycall.call(endpoint, args[1], args[2], input, timeout));
        return 0;
    }

    private static int echo(String[] args, PrintStream out, PrintStream err) {
        String nodeId = null;
        String bind = "127.0.0.1:0";
        String endpointFile = null;
        String tokenEnv = "POLYCALL_DEV_TOKEN";
        long count = 0;
        long idle = 30_000;
        List<String[]> peers = new ArrayList<>();
        for (int i = 2; i < args.length; i += 2) {
            switch (args[i]) {
                case "--node-id" -> nodeId = value(args, i);
                case "--endpoint" -> bind = value(args, i);
                case "--endpoint-file" -> endpointFile = value(args, i);
                case "--auth-token-env" -> tokenEnv = value(args, i);
                case "--count" -> count = number(args, i);
                case "--idle-timeout-ms" -> idle = number(args, i);
                case "--peer" -> {
                    String spec = value(args, i);
                    int eq = spec.indexOf('=');
                    if (eq <= 0) {
                        throw new UsageException("--peer needs ID=HOST:PORT, got '" + spec + "'");
                    }
                    peers.add(new String[] {spec.substring(0, eq), spec.substring(eq + 1)});
                }
                default -> throw new UsageException("unknown option '" + args[i] + "'");
            }
        }
        if (nodeId == null) {
            throw new UsageException("peer echo needs --node-id ID");
        }
        String token = System.getenv(tokenEnv);
        try (Peer peer = Peer.open(nodeId, bind, token == null || token.isEmpty() ? null : token)) {
            Runtime.getRuntime().addShutdownHook(new Thread(peer::close));
            for (String[] p : peers) {
                peer.register(p[0], p[1]);
            }
            String ep = peer.endpoint();
            if (endpointFile != null) {
                writeAtomically(Path.of(endpointFile), ep + "\n");
            }
            out.println("{\"event\":\"listening\",\"node_id\":" + json(nodeId) + ",\"endpoint\":" + json(ep) + "}");
            out.flush();
            long echoed = 0;
            while (count == 0 || echoed < count) {
                PeerMessage m;
                try {
                    m = peer.recv(idle);
                } catch (PolycallException e) {
                    if (e.status() == Status.E_TIMEOUT && count == 0) {
                        return 0;
                    }
                    throw e;
                }
                String replyId = "echo-" + m.messageId();
                out.println("{\"event\":\"received\",\"from\":" + json(m.sender()) + ",\"id\":"
                        + json(m.messageId()) + ",\"len\":" + m.length() + ",\"sha256\":\""
                        + sha256(m.payload()) + "\"}");
                out.flush();
                try {
                    peer.send(m.sender(), m.payload(), replyId, 10_000);
                    out.println("{\"event\":\"echoed\",\"to\":" + json(m.sender()) + ",\"id\":" + json(replyId) + "}");
                } catch (PolycallException e) {
                    err.println("java-polycall: echo to " + m.sender() + " failed: " + e.getMessage());
                    out.println("{\"event\":\"echo_failed\",\"to\":" + json(m.sender()) + ",\"status\":"
                            + json(e.statusName()) + "}");
                }
                out.flush();
                echoed++;
            }
            return 0;
        } catch (IOException e) {
            err.println("java-polycall: cannot write the endpoint file: " + e.getMessage());
            return 1;
        }
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        Path abs = file.toAbsolutePath();
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
