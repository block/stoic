# Debugging Stoic

## Check if Stoic is running

```bash
stoic stoic-status
```

Shows protocol version, attach method (JVMTI/SDK), and available embedded plugins.

## View logs

Stoic logs to Android's logcat with tag "stoic":

```bash
adb logcat -s stoic:*
```

For verbose output, use `--verbose` or `--debug`:

```bash
stoic --verbose your-plugin
stoic --debug your-plugin
```

## Restart the app

Sometimes issues can be fixed by restarting the app and retrying:

```bash
stoic --restart your-plugin ...
```

### JVMTI attach fails

JVMTI only works with debuggable apps. For non-debuggable apps you still might
be able to attach if you have root.

```bash
stoic --attach-via=jvmti-root your-plugin
```

Alternatively, you can integrate the app-sdk into your app and attach via that
to avoid using debug APIs altogether.

```bash
stoic --attach-via=sdk your-plugin
```
