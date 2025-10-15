import android.util.Log
import com.squareup.stoic.helpers.*

// This is a demo stoic plugin
// You can make your own by copying the plugin_helloworld directory and adding a new entry
// in settings.gradle
//
// Each plugin module name must begin with the prefix `plugin_`
//
// Your plugin-name is everything after `plugin_`. For example the plugin-name
// for the module plugin_helloworld is helloworld.
// You can invoke your plugin with `stoic <pkg> <plugin-name>`
// So you can invoke this plugin with `stoic <pkg> helloworld`
//
// Stoic looks for a default class named MainKt with a method named `main`.
// The signature must match the one here.
// You can write to stdout/stderr (or read from stdin - in theory - I haven't tested that) with
// System.in/out/err. When stoic loads they are setup to be thread-locals.
//
// The return value of you pluginMain will be the exit code of the stoic command
// (assuming you don't crash - try not to!)
fun main(args: Array<String>) {
  Log.i("helloworld", "Hello logcat")
  println("Hello world ${args.toList()}")
}
