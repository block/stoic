# Stoic Architecture

At its core, Stoic is flexible interprocess communication (IPC) and remote
procedure calls (RPC). Stoic is a debugging tool. It trades off security in
favor of flexibility. Don't run it in production.

Instead of requiring pre-established IPC/RPC servers, Stoic uses JVMTI to
inject code and establish a server in existing debuggable processes. Instead of
calling existing functions like traditional IPC/RPC, Stoic can send code over
to be run.

Functions are not limited to single input / single output. Stoic provides
multiplexed bidirectional communication. Stoic provides traditional
command-line-interface (CLI) style stdin/stdout/stderr

Stoic provides in-process access to debugging APIs (JVMTI) so you can do things
like get callbacks or iterate over objects in the heap.

```
                  Process / Machine
                      boundary
+------------+           |           +--------------+
|            |           |           |  target      |
|            |           |           |              |
| +--------+ | --------- | --------> | +--------+   |
| | client | | <-------- | --------- | | server |   |
| +--------+ |           |           | +--------+   |
|            |           |           |              |
+------------+           |           +--------------+
```

The stoic "client" is the thing that kicks off the RPC. The stoic "target" is the
thing the RPC runs within. In order to provide RPC functionality, Stoic depends on:
1. An injection mechanism - JVMTI
2. A bidirectional transport - Unix Domain Sockets

## Steps in running a plugin

With the aforementioned dependencies in place, Stoic will:
1. Inject its server inside the target
2. The target will then notify the client that it's ready for connection
3. The client will then connect to the server and send it a StartPlugin request
   containing the name and timestamp of the code to be run.
4. If the plugin isn't available, the server will respond with an error.
5. The client will send a LoadPlugin request, along with the contents of the
   plugin apk, and then resend the StartPlugin request
6. The client and server will exchange multiplexed IO - i.e.
   stdin/stdout/stderr or some mutually agreed upon protocol.
7. When the plugin finishes (either normally or abnormally) it sends a
   PluginFinished packet and the connection terminates.
8. The Stoic server will continue to service connection requests in the
    target, making future connections faster.

## Injection

Stoic currently injects code into Android apps via JVMTI, using the
`attach-agent` mechanism documented in
https://source.android.com/docs/core/runtime/art-ti. Though Android heavily
restricts communication with package processes, Stoic can usually work even on
non-rooted devices through careful use of `run-as`. For example, Stoic copies
its jvmti agent over to the package directory with:
```
cat stoic-jvmti.so | run-as com.example sh -c 'cat > stoic/stoic-jvmti.so'
```

## Bidirectional communication

Stoic establishes bidirectional communication via Unix Domain Sockets.
Android will not allow the shell user to directly connect to a Unix Domain
Socket owned by a package (or vice versa), but you can still use `adb forward`
to forward a port to the unix domain socket. This works without any `run-as`
tricks. This way we can connect directly to the server without going through
`adb shell`.

```
adb forward tcp:0 localabstract:/stoic/...
```
