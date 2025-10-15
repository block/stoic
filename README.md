# Stoic <img src="./assets/logo.svg" alt="App logo" width="25">

> *"Σκόπει εἰς σεαυτόν. Ἐντὸς σοῦ πηγὴ ἀγαθοῦ ἐστιν, ἡ ἀεὶ ἐκβρύειν ἑτοίμη, ἐὰν ἀεὶ σκάπτῃς."*
>
> "Look within. Within is the fountain of good, and it will ever bubble up, if you will ever dig."

*- Marcus Aurelius (121-180 AD), Roman Emperor and Stoic philosopher*

> *"Ignis aurum probat, miseria fortes."*
>
> "Fire tests gold and adversity tests the brave."

*-Seneca the Younger (c. 4 BC - AD 65), Roman statesman and Stoic philosopher*




## Introduction

Stoic provides communication channels from your laptop into app processes.
It lets you look within your Android processes, understand their behavior, and
solve difficult problems.

Stoic establishes its communication channel in one of two ways:
1. On debuggable APKs, Stoic can leverage debug APIs to establish a channel
   without any modifications to the APK
2. You can compile the Stoic plugin SDK into a non-debuggable APK, export a
   ContentProvider, and Stoic will use that to establish a channel.

If the debug API method is used, then your plugin will have access to extra
capabilities - normally only available to a debugger.

You can write plugins that
1. provide command-line access to APIs normally only available inside the process
2. leverage debugger functionality (e.g. use breakpoints to hook arbitrary methods)
3. examine the internal state of a process without restarting the process

Stoic is fast. The first time you run a Stoic plugin in a process it will take 2-3
seconds to attach. Thereafter, Stoic plugins typically run in less than a second.

## Getting started

1. Install with [Homebrew](https://brew.sh/): `brew install block/tap/stoic`
2. Run your first Stoic plugin: `stoic helloworld`
3. When you don't specify a package, Stoic injects itself into `com.squareup.stoic.demoapp.withoutsdk`
   by default - a simple app bundled with Stoic. Run `stoic --pkg <your-app> helloworld` to inject into your
   own app instead.

You can create your own plugins:
1. Create a new plugin: `stoic plugin --new scratch`
2. Run your plugin with: `stoic scratch`
3. Open up `~/.config/stoic/plugin/scratch` with Android Studio to modify this plugin and explore what Stoic can do.

Sometimes it's more convenient to build a plugin into your app. That way you
can call your own APIs directly, without needing reflection. You can do that
with the [plugin sdk](https://mvnrepository.com/artifact/com.squareup.stoic/plugin-sdk).

Stoic works on any API 26+ Android device / emulator, with any debuggable app (that I've tested so far).


## Bundled Plugins

Stoic bundles a few plugins:
1. [appexitinfo](https://github.com/square/stoic/blob/main/docs/APPEXITINFO.md) - command-line access to the ApplicationExitInfo API
2. breakpoint - print when methods get called, optionally with arguments/return-value/stack-trace
3. crasher - see how your app handles various types of crashes


## Authoring Plugins

Each plugin is a normal Java `main` function. You access debugger functionality via the `com.squareup.stoic.jvmti` package. e.g.
```
// get callbacks whenever any method of interest is called
val method = jvmti.virtualMachine.methodBySig("android/view/InputEventReceiver.dispatchInputEvent(ILandroid/view/InputEvent;)V")
jvmti.breakpoint(method.startLocation) { frame ->
  println("dispatchInputEvent called")
}

// iterate over each bitmap in the heap
for (bitmap in jvmti.instances(Bitmap::class.java)) {
  println("$bitmap: size=${bitmap.allocationByteCount}")
}
```

## Architecture

The primary technologies powering Stoic are
[JVMTI](https://en.wikipedia.org/wiki/Java_Virtual_Machine_Tools_Interface),
[Unix Domain Sockets](https://en.wikipedia.org/wiki/Unix_domain_socket), and
[run-as](https://cs.android.com/android/platform/superproject/main/+/main:system/core/run-as/run-as.cpp).
Stoic is written in Kotlin and uses
[Clikt](https://ajalt.github.io/clikt/) for command-line parsing and
[GraalVM](https://www.graalvm.org/) for snappy start-ups.

The first time you run Stoic on a process it will attach a jvmti agent which
will start a server inside the process. We connect to this server through a
unix domain socket, and multiplex stdin/stdout/stderr over this connection. See
https://github.com/square/stoic/blob/main/docs/ARCHITECTURE.md for more details.
