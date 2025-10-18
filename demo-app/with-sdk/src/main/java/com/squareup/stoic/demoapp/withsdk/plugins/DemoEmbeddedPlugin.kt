package com.squareup.stoic.demoapp.withsdk.plugins

import android.util.Log
import com.squareup.stoic.helpers.*
import com.squareup.stoic.plugin.StoicPlugin

/**
 * Example embedded plugin that demonstrates how to create plugins within your app.
 *
 * This plugin can be invoked with:
 *   stoic com.squareup.stoic.demoapp.withsdk demo-embedded [args...]
 */
class DemoEmbeddedPlugin : StoicPlugin {
    override fun run(args: List<String>): Int {
        Log.i("DemoEmbeddedPlugin", "Running with args: $args")

        println("Hello from DemoEmbeddedPlugin!")
        println("Arguments received: ${args.joinToString(", ")}")
        println("This plugin is embedded in the app.")

        // Example: handle some simple commands
        when (args.firstOrNull()) {
            "hello" -> {
                println("Hello back!")
                return 0
            }
            "echo" -> {
                println(args.drop(1).joinToString(" "))
                return 0
            }
            "fail" -> {
                eprintln("Simulating failure")
                return 1
            }
            else -> {
                println("Available commands:")
                println("  hello - Print a greeting")
                println("  echo <message> - Echo a message")
                println("  fail - Return error code")
                return 0
            }
        }
    }
}
