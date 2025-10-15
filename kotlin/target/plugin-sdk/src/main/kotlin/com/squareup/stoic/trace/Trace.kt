package com.squareup.stoic.trace

import com.squareup.stoic.trace.Rule.RuleLeaf
import com.squareup.stoic.trace.Rule.RuleMap
import com.squareup.stoic.Stack
import com.squareup.stoic.helpers.eprintln
import com.squareup.stoic.helpers.println
import com.squareup.stoic.jvmti.JvmtiClass
import com.squareup.stoic.jvmti.JvmtiField
import com.squareup.stoic.jvmti.JvmtiMethod
import com.squareup.stoic.jvmti.LocalVariable
import com.squareup.stoic.jvmti.MethodExitRequest
import com.squareup.stoic.jvmti.StackFrame
import com.squareup.stoic.jvmti.magics.c
import com.squareup.stoic.jvmti.magics.f
import com.squareup.stoic.threadlocals.jvmti
import com.squareup.stoic.trace.ResultTree.ResultLeaf
import com.squareup.stoic.trace.ResultTree.ResultNode
import java.lang.reflect.Modifier

val Include = RuleLeaf { type ->
  if (type is JvmtiMethod) {
    SimpleMethodEvaluator(type)
  } else {
    val clazz = (type as JvmtiClass).clazz
    val toStringClasses = listOf(
      Boolean::class.java, Byte::class.java, Short::class.java, Int::class.java, Long::class.java,
      Float::class.java, Double::class.java, String::class.java
    )
    if (toStringClasses.any { clazz.isAssignableFrom(it) }) {
      ToStringEvaluator()
    } else {
      SimpleValueEvaluator(type)
    }
  }
}

val IncludeEach = rules(default = Include)

val Omit = RuleLeaf { _ -> null }

val OmitThis = rules(default = Include, "this" to Omit)

fun traceExpect(expected: String, vararg rules: Pair<String, Rule>, runnable: Runnable) {
  val sb = StringBuilder()
  trace(*rules) { name, tree ->
    sb.append(methodTreeToString(name, tree))
  }

  runnable.run()

  val result = sb.toString()
  check(result.trim() == expected.trim()) { "\nexpected:\n$expected\n\nactual:\n$result\n" }
}

fun rules(default: Rule?, vararg pairs: Pair<String?, Rule?>): Rule {
  return RuleMap(default = default, pairs.toList())
}

fun rules(vararg pairs: Pair<String?, Rule?>): Rule {
  return rules(null, *pairs)
}

fun stringifyUntyped(evaluator: ValueEvaluator): RuleLeaf {
  return RuleLeaf { evaluator }
}

fun <T> stringify(clazz: Class<T>, stringifier: (T) -> String): RuleLeaf {
  return RuleLeaf { type ->
    val jvmtiClass = type as JvmtiClass
    if (!clazz.isAssignableFrom(jvmtiClass.clazz)) {
      throw IllegalArgumentException("${jvmtiClass.simpleName} is not compatible with $clazz")
    }

    object: ValueEvaluator() {
      override fun apply(obj: Any?): ResultTree {
        return ResultLeaf(stringifier(obj as T))
      }
    }
  }
}

sealed class Rule {
  class RuleMap(private val default: Rule?, private val rules: List<Pair<String?, Rule?>>): Rule() {
    /**
     * Gather members.
     *
     * Given a map of name/field, apply com.squareup.stoic.trace.rules to produce a list of name/field/com.squareup.stoic.trace.Rule triples
     * Optionally, populate a list of unmatched com.squareup.stoic.trace.rules
     */
    fun <T> gather(
      members: Map<String?, List<T>>,
      unmatched: MutableList<String?>?
    ): List<Triple<String?, T, Rule>> {
      val result = mutableListOf<Triple<String?, T, Rule>>()
      val matched = mutableSetOf<String?>()
      for (rule in rules) {

        val memberValues = members[rule.first]
        if (memberValues != null) {
          for (memberValue in memberValues) {
            matched.add(rule.first)
            if (rule.second != null) {
              result.add(Triple(rule.first, memberValue, rule.second!!))
            }
          }
        } else {
          unmatched?.add(rule.first)
        }
      }

      if (default != null) {
        // If there is a default, then we apply it to each non-matched key
        members.filter { it.key !in matched }.forEach { (key, values) ->
          for (value in values) {
            result.add(Triple(key, value, default))
          }
        }
      }

      return result
    }
  }

  // JvmtiClass/JvmtiMethod -> com.squareup.stoic.trace.MethodEvaluator/com.squareup.stoic.trace.ValueEvaluator
  class RuleLeaf(val typeToEvaluator: (type: Any) -> Any?): Rule()
}

abstract class MethodEvaluator(val method: JvmtiMethod) {
  abstract fun apply(frame: StackFrame): ResultTree
}

class SimpleMethodEvaluator(method: JvmtiMethod): MethodEvaluator(method) {
  private val result = ResultLeaf("...")

  override fun apply(frame: StackFrame): ResultTree {
    return result
  }
}

class MappedMethodEvaluator(
  method: JvmtiMethod,
  private val localVars: List<Pair<LocalVariable<*>, ValueEvaluator>>
): MethodEvaluator(method) {
  override fun apply(frame: StackFrame): ResultTree {
    return ResultNode(
      method.simpleQualifiedName,
      localVars.map { (localVar, objEval) ->
        val value = frame.get(localVar)
        Pair(localVar.name, objEval.apply(value))
      })
  }
}

abstract class ValueEvaluator {
  abstract fun apply(obj: Any?): ResultTree
}

class MappedObjEvaluator(
  private val fields: List<Pair<JvmtiField, ValueEvaluator>>
): ValueEvaluator() {
  override fun apply(obj: Any?): ResultTree {
    return if (obj == null) {
      ResultLeaf("null")
    } else {
      ResultNode(
        JvmtiClass[obj.javaClass].simpleName,
        fields.map { (field, objEval) ->
          val value = field.get(obj)
          val subtree = objEval.apply(value)
          Pair(field.name, subtree)
        }
      )
    }
  }
}

class SimpleValueEvaluator(private val jvmtiClass: JvmtiClass): ValueEvaluator() {
  override fun apply(obj: Any?): ResultTree {
    return if (obj == null) {
      ResultLeaf("null")
    } else {
      check(jvmtiClass.clazz.isAssignableFrom(obj.javaClass))
      val name = jvmtiClass.clazz.name.substringAfterLast('.')
      ResultLeaf("$name@@${Integer.toHexString(System.identityHashCode(obj))}")
    }
  }
}

class ToStringEvaluator: ValueEvaluator() {
  override fun apply(obj: Any?): ResultTree {
    return ResultLeaf("$obj")
  }
}


//class TypeEvaluator(val subrules: List<Pair<JvmtiField, TypeEvaluator>>) {
//  fun apply(obj: Any?): com.squareup.stoic.trace.ResultTree {
//    if (obj == null) {
//      return com.squareup.stoic.trace.ResultTree("null")
//    } else {
//      return com.squareup.stoic.trace.ResultTree(
//        subrules.map { (field, subrule) -> Pair(field.name, subrule.apply(field.get(obj))) })
//    }
//  }
//}

//class com.squareup.stoic.trace.MethodEvaluator(
//  val name: String,
//  val jvmtiMethod: JvmtiMethod,
//  private val subrules: List<Pair<LocalVariable<*>, TypeEvaluator>>
//) {
//  fun apply(frame: StackFrame): com.squareup.stoic.trace.ResultTree {
//    return com.squareup.stoic.trace.ResultTree(
//      "${jvmtiMethod.clazz.simpleName}.${jvmtiMethod.name}",
//      subrules.map { (localVar, subrule) ->
//           com.squareup.stoic.trace.ResultTree(localVar.name!!, subrule.apply(frame.get(localVar)))
//          })
//  }
//}

// key/value pairs, where the value is either a String or a com.squareup.stoic.trace.ResultTree
sealed class ResultTree {
  class ResultNode(val value: String, val children: List<Pair<String?, ResultTree>> = listOf()): ResultTree()
  class ResultLeaf(val value: String): ResultTree()
}


// resolve a top-level rule into a list of MethodEvaluators
// the key is assumed to be a class sig
// TODO: allow the key to be a method-name or method-sig as well
// TODO: allow class to be specified in foo.bar.Baz format too
//
// For each class we find its methods, and then find the corresponding methods in its subclasses
// We resolve the rule names against the local variable names
fun resolveClass(key: String, rule: Rule): List<MethodEvaluator> {
  val jvmtiClass = JvmtiClass.bySig(key)
  val subclasses = jvmti.subclasses(jvmtiClass.clazz).map { JvmtiClass[it] }
  return when (rule) {
    is RuleLeaf -> resolveClassLeaf(jvmtiClass, subclasses, rule)
    is RuleMap -> resolveClassMap(jvmtiClass, subclasses, rule.gather(
      jvmtiClass.declaredMethods.groupBy { it.name },
      null)
    )
  }
}

fun resolveClassLeaf(
  rootClass: JvmtiClass,
  subclasses: List<JvmtiClass>,
  rule: RuleLeaf
): List<MethodEvaluator> {
  return rootClass.declaredMethods.flatMap { jvmtiMethod ->
    resolveMethodLeaf(jvmtiMethod, subclasses, rule)
  }
}

fun resolveClassMap(
  rootClass: JvmtiClass,
  subclasses: List<JvmtiClass>,
  rules: List<Triple<String?, JvmtiMethod, Rule?>>
): List<MethodEvaluator> {
  return rules.flatMap { (methodName, method, rule) ->
    if (rule != null) {
      resolveMethodMap(methodName!!, method, subclasses, rule)
    } else {
      listOf()
    }
  }
}

fun resolveTypeRule(jvmtiClass: JvmtiClass, rule: Rule): ValueEvaluator? {
  return when (rule) {
    is RuleMap -> {
      val fieldPairs = rule.gather(
        jvmtiClass.declaredFields
          .filter { !Modifier.isStatic(it.modifiers) }
          .groupBy { it.name },
        null
      )
        .mapNotNull { (_, field, subrule) ->
          val subtype = JvmtiClass.bySig(field.signature)
          resolveTypeRule(subtype, subrule)?.let { Pair(field, it) }
        }

      MappedObjEvaluator(fieldPairs)
    }
    is RuleLeaf -> {
      rule.typeToEvaluator(jvmtiClass) as ValueEvaluator?
    }
  }
}

fun resolveMethodMap(
  name: String,
  jvmtiMethod: JvmtiMethod,
  subclasses: List<JvmtiClass>,
  rule: Rule
): List<MethodEvaluator> {
  return subclasses.mapNotNull { jvmtiClass ->
    try {
      jvmtiClass.declaredMethod(jvmtiMethod.name, jvmtiMethod.signature)
    } catch (e: NoSuchMethodException) {
      // override not present
      null
    }?.let { subclassMethod ->
      // We need an evaluator for each argument
      when (rule) {
        is RuleMap -> {
          // TODO: match locals by index, since name can change between super/subclass impl
          val localVarPairs = rule.gather(
            subclassMethod.arguments.groupBy { it.name },
            null
          ).mapNotNull { (_, localVar, subrule) ->
            val subtype = JvmtiClass.bySig(localVar.signature)
            resolveTypeRule(subtype, subrule)?.let { Pair(localVar, it) }
          }
          MappedMethodEvaluator(subclassMethod, localVarPairs)
        }

        is RuleLeaf -> {
          val localVarPairs = subclassMethod.arguments.map {
            val valueEval = rule.typeToEvaluator(JvmtiClass.bySig(it.signature)) as ValueEvaluator
            Pair(it, valueEval)
          }
          MappedMethodEvaluator(subclassMethod, localVarPairs)
        }
      }
    }
  }
}

fun resolveMethodLeaf(
  jvmtiMethod: JvmtiMethod,
  subclasses: List<JvmtiClass>,
  rule: RuleLeaf
): List<MethodEvaluator> {
  return subclasses.mapNotNull { jvmtiClass ->
    try {
      val subclassMethod = jvmtiClass.declaredMethod(jvmtiMethod.name, jvmtiMethod.signature)
      rule.typeToEvaluator(subclassMethod) as MethodEvaluator
    } catch (e: NoSuchMethodException) {
      // override not present
      null
    }
  }
}



// Structured com.squareup.stoic.trace.trace declaration. This is a complex API, but you can get started by following
// examples rather than reading the entire documentation.
//
// e.g.
// com.squareup.stoic.trace.trace(
//   "android/view/View" to com.squareup.stoic.trace.getInclude,
// )
//
// com.squareup.stoic.trace.trace(
//   "android/view/View" to com.squareup.stoic.trace.getIncludeEach,
// )
//
// com.squareup.stoic.trace.trace(
//   "android/view/View" to ruleMap(
//     "getChildCount" to com.squareup.stoic.trace.getIncludeEach,
//   )
// )

// com.squareup.stoic.trace.trace(
//   "android/view/View" to ruleMap(
//     default = com.squareup.stoic.trace.getIncludeEach,
//     "getChildCount" to com.squareup.stoic.trace.getOmit,
//   )
// )
//
// We take in a list of com.squareup.stoic.trace.rules describing what to com.squareup.stoic.trace.trace. We type-check them to produce a list of
// evaluators, describing how to capture state at each breakpoint. When the evaluators run, they
// produce a com.squareup.stoic.trace.ResultTree, which is passed to the `consume` parameter.
//
// We apply breakpoints to subclasses, so you can list an interface and capture whenever a method in
// one of its implementations is called.
// TODO: provide an option to not apply breakpoints to subclasses
// The LocalVariables of overrides are not the same. To make matters worse abstract/interface
// functions don't have local variable names. So we need to provide complex logic to match names up
// to argument indices, and then derive the LocalVariable based on the argument index for each
// override.
//
fun trace(vararg rules: Pair<String, Rule>, consume: (String, ResultTree) -> Unit) {
  // TODO: support method sigs too
  val resolvedRules = rules.flatMap { (key, rule) -> resolveClass(key, rule) }
  resolvedRules.forEach { methodEvaluator ->
    val jvmtiMethod = methodEvaluator.method
    val startLocation = jvmtiMethod.startLocation

    // Check location to make sure the method isn't abstract
    if (startLocation.jlocation >= 0) {
      jvmti.breakpoint(startLocation) { frame ->
        val resultTree = methodEvaluator.apply(frame)
        consume(jvmtiMethod.simpleQualifiedName, resultTree)
      }
    }
  }
}

fun printMethodTree(name: String, resultTree: ResultTree) {
  println(methodTreeToString(name, resultTree))
}

fun methodTreeToString(name: String, tree: ResultTree): String {
  return when (tree) {
    is ResultNode -> {
      if (tree.children.isNotEmpty()) {
        val indent = "  "
        val sb = StringBuilder()
        sb.append("$name(\n")
        tree.children.forEach {
          sb.append(indent)
          resultTreeToString(it.first, it.second, indent, sb)
          sb.append(",\n")
        }
        sb.append(")\n")
        sb.toString()
      } else {
        "$name()"
      }
    }
    is ResultLeaf -> {
      return "$name(${tree.value})"
    }
  }
}

fun resultTreeToString(name: String?, tree: ResultTree, indent: String, sb: StringBuilder) {
  when (tree) {
    is ResultLeaf -> sb.append("$name = ${tree.value}")
    is ResultNode -> {
      val newIndent = "$indent  "
      sb.append("$name = ${tree.value} {\n")
      tree.children.forEach { (name, subtree) ->
        sb.append(newIndent)
        resultTreeToString(name, subtree, newIndent, sb)
        sb.append(",\n")
      }
      sb.append("$indent}")
    }
  }
}

//fun traceTopo(traceTopo: Map<JvmtiClass, Map<String, Stringifier<*>>>, dedupe: Boolean) {
//  val dedupeCache = LruCache<String, Unit>(8192)
//
//  traceTopo.forEach { (clazz, classSpec) ->
//    clazz.declaredMethods.forEach { jvmtiMethod ->
//      val stringifier = classSpec[jvmtiMethod.name] ?: StringifyDefault
//      if (stringifier != StringifyOmit && jvmtiMethod.startLocation.jlocation >= 0) {
//        jvmti.breakpoint(jvmtiMethod.startLocation) {
//          val earlyStr = stringifier.stringify(FrameMembers(jvmtiMethod, it), "")
//          if (earlyStr == "") {
//            // do nothing
//          } else {
//            val str = "${Thread.currentThread().id} $earlyStr"
//            if (!dedupe || !dedupeCache.containsKey(str)) {
//              dedupeCache[str] = Unit
//              println(str)
//            } else {
//              val alt = StringifyDefault.stringify(FrameMembers(jvmtiMethod, it), "")
//              println("$alt (deduped)")
//            }
//          }
//        }
//      }
//    }
//  }
//
//}
//
//typealias SpecialToString = (Any?) -> String
//
//// Map of (class-name, method-name, argument-name) to SpecialToString
//// This describes special com.squareup.stoic.trace.rules for stringifying certain arguments
//val specialToStrings: Map<Triple<String, String, String>, SpecialToString> = mapOf(
//  Triple(
//      "android.view.ViewRootImpl\$AccessibilityInteractionConnection",
//      "performAccessibilityAction",
//      "action") to { value: Any? ->
//    com.squareup.stoic.trace.getConstantNameByValue(
//      AccessibilityNodeInfo::class.java,
//      "ACTION_",
//      value as Int,
//      true)
//  },
//  Triple(
//    "android.view.ViewRootImpl\$AccessibilityInteractionConnection",
//    "performAccessibilityAction",
//    "flags") to { value: Any? ->
//    com.squareup.stoic.trace.getFlagNames(
//      AccessibilityNodeInfo::class.java,
//      "FLAG_",
//      value as Int,
//      true)
//  },
//  Triple(
//    "android.view.ViewRootImpl\$AccessibilityInteractionConnection",
//    "findFocus",
//    "flags") to { value: Any? ->
//    com.squareup.stoic.trace.getFlagNames(
//      AccessibilityNodeInfo::class.java,
//      "FLAG_",
//      value as Int,
//      true)
//  },
//  Triple(
//    "android.view.ViewRootImpl\$AccessibilityInteractionConnection",
//    "findAccessibilityNodeInfoByAccessibilityId",
//    "flags") to { value: Any? ->
//    com.squareup.stoic.trace.getFlagNames(
//      AccessibilityNodeInfo::class.java,
//      "FLAG_",
//      value as Int,
//      true)
//  },
//)

fun getConstantNameByValue(constantsClass: Class<*>, fieldPrefix: String, value: Int, includePrefix: Boolean = false): String {
  // TODO: cache this map
  val valueToNameMap = JvmtiClass[constantsClass].declaredFields
    .filter { it.signature == "I" }
    .filter { it.modifiers and Modifier.STATIC != 0}
    .filter { it.modifiers and Modifier.FINAL != 0}
    .filter { it.name.startsWith(fieldPrefix) }
    .associate { it.get(null) to it.name }

  val result = valueToNameMap[value] ?: return "Unknown constant value: $value"
  return if (includePrefix) { result } else { result.drop(fieldPrefix.length) }
}

fun getFlagNames(constantsClass: Class<*>, fieldPrefix: String, value: Int, includePrefix: Boolean = false): String {
  // TODO: cache this map
  val valueToNameMap = JvmtiClass[constantsClass].declaredFields
    .filter { it.signature == "I" }
    .filter { it.modifiers and Modifier.STATIC != 0}
    .filter { it.modifiers and Modifier.FINAL != 0}
    .filter { it.name.startsWith(fieldPrefix) }
    .associate { it.get(null) to it.name }

  val flagNames = mutableListOf<String>()
  for (flagPos in 0..31) {
    val flag = 1 shl flagPos
    if (flag and value != 0) {
      flagNames.add(valueToNameMap[flag] ?: "0x${flag.toString(16)}")
    }
  }

  return if (flagNames.isEmpty()) {
    "0"
  } else {
    flagNames.joinToString(" | ")
  }
}

fun flexibleToList(any: Any): List<Any?> {
  return when (any) {
    is Array<*> -> any.toList()
    is BooleanArray -> any.toList()
    is ByteArray -> any.toList()
    is CharArray -> any.toList()
    is ShortArray -> any.toList()
    is IntArray -> any.toList()
    is LongArray -> any.toList()
    is FloatArray -> any.toList()
    is DoubleArray -> any.toList()
    is List<*> -> any
    else -> {
      if (c["android.util.LongArray"].isInstance(any)) {
        (any.f["mValues"] as LongArray).toList().slice(0..<(any.f["mSize"] as Int))
      } else {
        throw IllegalArgumentException("${any.javaClass}")
      }
    }
  }
}

fun identityString(obj: Any?): String {
  if (obj == null) {
    return "null"
  }

  val hash = Integer.toHexString(System.identityHashCode(obj))
  return obj.javaClass.name.substringAfterLast('.') + "@@" + hash
}

fun traceMethodUntilExit(bpMethod: JvmtiMethod) {
  jvmti.breakpoint(bpMethod.startLocation) { breakpointFrame ->
    traceUntilExit(breakpointFrame)
  }
}

fun traceUntilExit(breakpointFrame: StackFrame) {
  val bpMethod = breakpointFrame.location.method
  println("-> called ${bpMethod.name}(...)")

  val entryRequest = jvmti.methodEntries { frame ->
    val method = frame.location.method
    val level = frame.height - breakpointFrame.height
    val indent = "  ".repeat(level)
    //println("$indent-> ${method.clazz.name}.${method.name} (...)")

  }
  var exitRequest: MethodExitRequest? = null
  exitRequest = jvmti.methodExits { frame, value, wasPoppedByException ->
    val method = frame.location.method
    if (frame.height <= breakpointFrame.height) {
      if (frame.height == breakpointFrame.height) {
        check(bpMethod.methodId == frame.location.method.methodId)
        println("<- exiting from ${bpMethod.name} (${java.lang.Long.toHexString(value as Long)})")
      } else {
        println("<- exiting from ${bpMethod.name} (wasPoppedByException? $wasPoppedByException)")
      }
      entryRequest.close()
      exitRequest!!.close()
      println(Stack(frame.stackTrace).stackTraceToString())
    } else {
      val level = frame.height - breakpointFrame.height
      val indent = "  ".repeat(level)
      //println("$indent<- ${method.clazz.name}.${method.name} (...)")
    }
  }

}

fun Any?.toKotlinRepr(): String = when (this) {
  null -> "null"
  is CharSequence -> {
    val unquoted = this.toString()
      .replace("\\", "\\\\")
      .replace("\n", "\\n")
      .replace("\t", "\\t")
      .replace("\b", "\\b")
      .replace("\r", "\\r")
      .replace("\"", "\\\"")
      .replace("\$", "\\\$")
    "\"" + unquoted + "\""
  }
  is List<*> -> this.toKotlinListRepr()  // Separate function for lists
  else -> this.toString()
}

fun List<*>.toKotlinListRepr(): String {
  val reprElements = this.joinToString(separator = ", ") { it.toKotlinRepr() }
  return "listOf($reprElements)"
}

fun inspect(clazz: Class<*>) {
  // Note: technically I should walk up the superclasses to find other declared methods
  eprintln("Inspecting ${clazz.name}")
  val staticMethods = clazz.declaredMethods.asList().sortedBy { it.name }
  val staticFields = clazz.declaredFields.asList().sortedBy { it.name }
  val methods = (clazz.declaredMethods.asList() + clazz.methods.asList()).distinct().sortedBy { it.name }
  val fields = (clazz.declaredFields.asList() + clazz.fields.asList()).distinct().sortedBy { it.name }
  staticFields.forEach{eprintln("static ${it.name}: ${it.type}") }
  staticMethods.forEach{eprintln("static ${it.name}: (${it.parameterTypes.asList()}): ${it.returnType}") }
  fields.forEach{eprintln("${it.name}: ${it.type}") }
  methods.forEach{eprintln("${it.name}: (${it.parameterTypes.asList()}): ${it.returnType}") }
  eprintln("Done")
}

fun <T> highlander(array: Array<T>): T {
  if (array.size != 1) {
    throw IllegalArgumentException("There can be only one! ${array.size}")
  }

  return array.first()
}

fun <T> highlander(collection: Collection<T>): T {
  if (collection.size != 1) {
    throw IllegalArgumentException("There can be only one! $collection")
  }

  return collection.first()
}

