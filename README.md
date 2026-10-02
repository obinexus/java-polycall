# java-polycall

Java binding for the [Polycall](https://github.com/obinexus/polycall) core
library, **binding ABI v1** (`polycall.h`, documented in the core's
`docs/BINDING_ABI.md`).

The binding calls `polycall.dll` (Windows, MSVC build), `libpolycall.dll`
(MinGW) or `libpolycall.so.1` (Linux) directly through the Java **Foreign
Function & Memory API** (`java.lang.foreign`, final since JDK 22). There is no
C glue, no JNI library to build and no runtime dependency besides `java.base`.

## Requirements

- JDK 22 or newer (tested with Temurin 25 on Linux, Oracle JDK 25 and 27 on Windows)
- libpolycall >= 1.1.0 (binding ABI 1), 64-bit
- Maven 3.6.3+ to build

## Loading the library

1. `POLYCALL_LIBRARY` environment variable (full path), then
2. the `polycall.library` system property, then
3. the platform name through the OS loader: `polycall.dll`, `libpolycall.dll`
   (Windows, `PATH`), `libpolycall.so.1` (Linux, `LD_LIBRARY_PATH` / ld.so
   cache), `libpolycall.1.dylib` (macOS).

Every symbol is resolved up front and `polycall_ffi_abi_version()` must be 1.
A missing library, a missing symbol (a 1.0 core) or an ABI mismatch raises
`PolycallLoadException` naming the library and the problem; it never crashes.

Run the JVM with `--enable-native-access=ALL-UNNAMED` (the jar manifest sets
`Enable-Native-Access` for `java -jar`).

## API (`org.obinexus.polycall`)

```java
Polycall.abiVersion();                 // 1
Polycall.version();                    // "1.1.0"
Polycall.runConfig("java-polycallrc"); // polycall_ffi_run_config(path, 1)
Polycall.runConfig(path, false);       // validate only (unknown keys are warnings)
Polycall.describe(path);               // JSON description
String out = Polycall.call("127.0.0.1:7000", "inventory", "get",
                           "{\"item_id\":\"widget-a\"}", 2000);

try (Peer a = Peer.open("alpha", "127.0.0.1:0", token);
     Peer b = Peer.open("beta", "127.0.0.1:0", token)) {
    a.register("beta", b.endpoint());
    a.send("beta", bytes, "msg-1", 5000);      // exactly one delivery attempt
    PeerMessage m = b.recv(5000);               // m.sender(), m.messageId(), m.payload()
}
```

`Peer` covers open / close / endpoint / nodeId / register / unregister / list /
ping / send / recv / tryRecv / cancel / health. It is thread-safe; `cancel()`
and `close()` wake a blocked `recv` (`E_CANCELLED` / `E_CLOSED`); `close()` is
idempotent and a closed peer's stale handle is reported by the library as
`E_INVALID_HANDLE`.

Failures raise `PolycallException` with `status()` (negative `POLYCALL_E_*`,
see `Status`), `statusName()` (from `polycall_strerror`), `detail()` (from
`polycall_last_error`), `remoteError()` for runtime error objects and
`requiredSize()` for too-small buffers.

## Command line

`java -jar target/java-polycall-1.0.0.jar` (or the `org.obinexus.polycall.cli.Main` class):

```
version
validate [--lenient] FILE
describe FILE
call SERVICE OPERATION --endpoint H:P [--input JSON] [--timeout-ms N]
peer echo --node-id ID [--endpoint H:P] [--endpoint-file F] [--peer ID=H:P ...]
          [--count N] [--idle-timeout-ms N] [--auth-token-env NAME]
```

`peer echo` is the cross-binding interop agent: it sends every message it
receives back to its sender with id `echo-<id>`. The shared token is read from
`POLYCALL_DEV_TOKEN` (never from argv).

## Tests

All tests run against the real installed library and the real `polycall` CLI:

```sh
POLYCALL_LIBRARY=/opt/polycall/lib/libpolycall.so.1 \
POLYCALL_CLI=/opt/polycall/bin/polycall sh scripts/test.sh   # or: mvn test
```

They cover the BINDING_ABI.md checklist: version/ABI, `run_config` (valid,
missing, invalid, strict, TLS), `call` against `polycall start` and
`polycall daemon start` (success, unknown operation, deadline, invalid input,
no runtime), two nodes exchanging payloads both ways (empty, UTF-8, binary
with NUL, 1 MiB, 1 MiB + 1), registry ownership, duplicates, auth failure,
dead peer, receive timeout, too-small buffer, cancel/close wake-ups, double
close / invalid handles, concurrent senders, and interop with the C CLI
(`polycall peer serve/send/recv/health/register`). `EchoInteropTest` runs
against another binding's echo agent when `POLYCALL_INTEROP_ECHO` names its
command. Tests that cannot run (no CLI, no agent) are reported as skipped,
never as passed; `scripts/test.sh` exits 77 when the toolchain is missing.

`src/test/c/fake_polycall_abi2.c` is a clearly-labelled fake library used only
to prove that the loader refuses ABI 2.

## License

MIT, see [LICENSE](LICENSE). Copyright OBINexus Computing / Nnamdi Michael Okpala.
