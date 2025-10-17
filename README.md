# Stoic <img src="./assets/logo.svg" alt="App logo" width="25">

> *"Σκόπει εἰς σεαυτόν. Ἐντὸς σοῦ πηγὴ ἀγαθοῦ ἐστιν, ἡ ἀεὶ ἐκβρύειν ἑτοίμη, ἐὰν ἀεὶ σκάπτῃς."*
>
> "Look within. Within is the fountain of good, and it will ever bubble up, if you will ever dig."

*- Marcus Aurelius (121-180 AD), Roman Emperor and Stoic philosopher*

> *"Ignis aurum probat, miseria fortes."*
>
> "Fire tests gold and adversity tests the brave."

*- Seneca the Younger (c. 4 BC - AD 65), Roman statesman and Stoic philosopher*

Run code inside Android processes from your laptop. No APK modifications needed for debuggable apps.

Stoic opens a communication channel into your app. Write plugins that hook methods, inspect heap objects, or call internal APIs—all from the command line.

First attach takes 2-3 seconds. After that, under a second.

## Quick Start

```bash
brew install block/tap/stoic
stoic helloworld
```

This runs against a demo app. For your app: `stoic --pkg com.yourapp helloworld`

Works on API 26+ (Android 8.0+) with debuggable apps. For non-debuggable apps,
add the
[app-sdk](https://mvnrepository.com/artifact/com.squareup.stoic/app-sdk).

## Bundled Plugins

- [appexitinfo](docs/APPEXITINFO.md) - query ApplicationExitInfo API
- breakpoint - hook method calls, print args/returns/stacktraces
- crasher - test crash handling

## Writing Plugins

Plugins are Java/Kotlin main functions. Use `com.squareup.stoic.jvmti` for debugger features:

```kotlin
// Hook a method
InputEventReceiver.dispatchInputEvent.forEachInvocation {
  println("dispatchInputEvent called")
}

// Scan the heap
for (bitmap in jvmti.instances(Bitmap::class.java)) {
  println("$bitmap: ${bitmap.allocationByteCount} bytes")
}
```

Create your own plugin:
```bash
stoic plugin --new scratch
stoic scratch
```

Alternatively, use the
[plugin-sdk](https://mvnrepository.com/artifact/com.squareup.stoic/plugin-sdk)
to register plugins inside your app.

## How It Works

- Debuggable apps: Stoic attaches a JVMTI agent to your process, which starts a server
- Non-debuggable apps: The app-sdk registers a BroadcastReceiver, which starts a server
- Server connection happens via unix domain sockets - no internet permission needed
- Stoic sends plugin APKs to the server, which then loads them via DexClassLoader

See [ARCHITECTURE.md](docs/ARCHITECTURE.md).
