# Architecture

Stoic is IPC and RPC for debugging. It trades security for flexibility. Don't run it in production.

Instead of requiring a pre-established server, Stoic injects a server into
running processes via JVMTI (you can use the app-sdk for non-debuggable apps).
Instead of calling predefined functions, you send code to execute.

Communication is bidirectional and multiplexed—stdin/stdout/stderr work
normally. You get access to JVMTI debugging APIs.

```
     Laptop          |        Android Process
  +---------+        |        +---------+
  |  host   | <------|------> | target  |
  +---------+        |        +---------+
```

Your laptop is a client that talks to the server (injected into the app).

## Running a Plugin

1. Inject server into target process
2. Server signals ready
3. Host sends StartPlugin request (plugin name + timestamp)
4. If plugin missing, host sends LoadPlugin with APK contents
5. Multiplexed I/O flows (stdin/stdout/stderr)
6. Plugin finishes, sends PluginFinished
7. Server stays alive for faster subsequent runs

## Injection

Uses JVMTI's `attach-agent` ([docs](https://source.android.com/docs/core/runtime/art-ti)). Works on non-rooted devices via `run-as`.

## Communication

Unix domain sockets. Android won't let shell and package users talk directly,
but `adb forward` allows our laptop to talk directly to the package:

```bash
adb forward tcp:0 localabstract:/stoic/...
```
