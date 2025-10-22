/**
 * This package provides safe replacements for operations that don't make sense inside of a Stoic
 * plugin. For example, if you call Kotlin's println, the output will go to logcat. The println here
 * will send its output to the plugin's stdout.
 */
package com.squareup.stoic.helpers

import android.os.Looper
import com.squareup.stoic.threadlocals.stoic
import java.util.concurrent.Executor

fun println(x: Any?) = stoic.stdout.println(x)

fun print(x: Any?) = stoic.stdout.print(x)

fun eprintln(x: Any?) = stoic.stderr.println(x)

fun eprint(x: Any?) = stoic.stderr.print(x)

/** Exit the plugin with the given exit code. */
fun exit(code: Int) = stoic.exitPlugin(code)

fun exitPlugin(code: Int) = stoic.exitPlugin(code)

fun exitProcess(code: Int) = stoic.exitPlugin(code)

/**
 * Create a new thread, calling kotlin.concurrent.thread under the covers, but connecting the stoic
 * thread-local with that thread so any code run on it will be connected to the current plugin
 */
fun thread(runnable: Runnable) = stoic.thread(runnable = runnable)

fun runOnMainLooper(runnable: Runnable) = stoic.runOnMainLooper(runnable = runnable)

fun runOnLooper(looper: Looper, runnable: Runnable) =
  stoic.runOnLooper(looper = looper, runnable = runnable)

fun runOnExecutor(executor: Executor, runnable: Runnable) =
  stoic.runOnExecutor(executor = executor, runnable = runnable)
