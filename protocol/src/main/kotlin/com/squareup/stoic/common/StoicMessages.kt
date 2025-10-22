package com.squareup.stoic.common

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val STOIC_PROTOCOL_VERSION = 5
const val STDIN = 0
const val STDOUT = 1
const val STDERR = 2

// json-encoded and base64-encoded and sent as the JVMTI attach options
@Serializable data class JvmtiAttachOptions(val stoicProtocolVersion: Int, val attachedVia: String)

enum class MessageFlags(val code: Int) {
  // Indicates the message is a request, meaning it expects a response in return
  REQUEST(0x1),

  // Indicates the message is a response to an earlier request. The request-id field identifies that
  // request.
  RESPONSE(0x2),

  // For responses, indicating whether or not there will be further messages as part of the same
  // response - must be set if request-id is -1
  COMPLETE(0x4),
}

// Message format:
// size (4 bytes) - overall size of the message, including the flags, type, and payload
// flags (4 bytes) - see MessageFlags
// request-id (4 bytes) - -1 indicates none - note: this can be used even with things that aren't
//     requests/responses - it allows identifies which things are part of a stream
// type (4 bytes) - for use by a higher level to understand how to deserialize the payload
// payload (variable) - an array of bytes (the size can be inferred from the earlier size field)

enum class PayloadType(val code: Int) {
  RAW(1),
  VERIFY_PROTOCOL_VERSION(2),
  START_PLUGIN(3),
  LOAD_PLUGIN(4),
  PLUGIN_FINISHED(5),
  SUCCEEDED(6),
  FAILED(7),
  PROTOCOL_ERROR(8),
}

enum class FailureCode(val value: Int) {
  UNSPECIFIED(0),
  PLUGIN_MISSING(1),
}

@Serializable
data class VerifyProtocolVersion(val protocolVersion: Int, val stoicVersionName: String)

@Serializable
data class StartPlugin(
  val pluginName: String?,
  val pluginSha: String?,
  val pluginArgs: List<String>,
  val minLogLevel: String,
  val env: Map<String, String>,
)

@Serializable data class LoadPlugin(val pluginName: String?, val pluginSha: String)

@Serializable data class PluginFinished(val exitCode: Int)

@Serializable data class Succeeded(val message: String)

@Serializable data class Failed(val failureCode: Int, val message: String)

@Serializable data class ProtocolError(val message: String)

class RawMessage(val flags: Int, val requestId: Int, val payloadType: Int, val payload: ByteArray)

fun DataOutputStream.writeRawMessage(
  flags: Int,
  requestId: Int,
  payloadType: Int,
  payload: ByteArray,
) {
  val size = payload.size + 12
  writeInt(size)
  writeInt(flags)
  writeInt(requestId)
  writeInt(payloadType)
  write(payload)
}

fun DataInputStream.readRawMessage(): RawMessage {
  val size = readInt()
  val flags = readInt()
  val requestId = readInt()
  val payloadType = readInt()

  val payloadSize = size - 12
  val payload = ByteArray(payloadSize).also { readFully(it) }

  return RawMessage(
    flags = flags,
    requestId = requestId,
    payloadType = payloadType,
    payload = payload,
  )
}

data class DecodedMessage<T>(val flags: Int, val requestId: Int, val payload: T) {
  val isRequest
    get() = flags and MessageFlags.REQUEST.code != 0

  val isResponse
    get() = flags and MessageFlags.RESPONSE.code != 0

  val isComplete
    get() = flags and MessageFlags.COMPLETE.code != 0
}

class MessageWriter(val dataOutputStream: DataOutputStream) {
  val openRequestIds = mutableSetOf<Int>()

  // 0/1/2 are reserved for stdin/stdout/stderr
  var nextRequestIndex = 3

  fun allocateRequestId(): Int {
    val requestId = nextRequestIndex++
    openRequestIds.add(requestId)
    return requestId
  }

  fun openStdinForWriting() {
    openRequestIds.add(STDIN)
  }

  fun openStdoutForWriting() {
    openRequestIds.add(STDOUT)
  }

  fun openStderrForWriting() {
    openRequestIds.add(STDERR)
  }

  /**
   * Write a request packet.
   *
   * @param request the request content
   * @param requestId a unique ID identifying the request (or -1 to request an ID to be allocated)
   * @param isComplete false if additional request packets will be sent for the same request ID
   * @return the request ID
   */
  @Synchronized
  fun writeRequest(request: Any, requestId: Int = -1, isComplete: Boolean = true): Int {
    val resolvedRequestId =
      if (requestId == -1) {
        allocateRequestId()
      } else {
        requestId
      }
    val completeFlag =
      if (isComplete) {
        MessageFlags.COMPLETE.code
      } else {
        0
      }
    val flags = MessageFlags.REQUEST.code or completeFlag
    logVerbose { "writing request: $request, requestId=$resolvedRequestId, flags=$flags" }
    writeMessageLocked(flags, resolvedRequestId, request)
    return resolvedRequestId
  }

  /**
   * Write a response packet to a previous request
   *
   * @param requestId the ID identifying the request to which this response corresponds
   * @param response the response content
   * @param isComplete false if additional response packets will be sent for the same request ID
   */
  @Synchronized
  fun writeResponse(requestId: Int, response: Any, isComplete: Boolean = true) {
    val completeFlag =
      if (isComplete) {
        MessageFlags.COMPLETE.code
      } else {
        0
      }
    val flags = MessageFlags.RESPONSE.code or completeFlag
    logVerbose { "writing response: $response, requestId=$requestId, flags=$flags" }
    writeMessageLocked(flags, requestId, response)
  }

  /**
   * Write an independent message packet (neither a request expecting a response nor a response to a
   * previous request)
   *
   * @param oneWay the message content
   * @param requestId the ID identifying the message (in case the message involves multiple packets)
   * @param isComplete false if additional packets will be sent for the same request ID
   */
  @Synchronized
  fun writeOneWay(oneWay: Any, requestId: Int = -1, isComplete: Boolean = true) {
    val resolvedRequestId =
      if (requestId == -1) {
        allocateRequestId()
      } else {
        requestId
      }
    val completeFlag =
      if (isComplete) {
        MessageFlags.COMPLETE.code
      } else {
        0
      }
    logVerbose { "writing one-way: $oneWay, requestId=$resolvedRequestId, flags=$completeFlag" }
    writeMessageLocked(completeFlag, resolvedRequestId, oneWay)
  }

  fun writeMessageLocked(flags: Int, requestId: Int, msg: Any) {
    val payloadType: Int
    val payload: ByteArray
    when (msg) {
      is VerifyProtocolVersion -> {
        payloadType = PayloadType.VERIFY_PROTOCOL_VERSION.code
        payload = Json.encodeToString(msg).toByteArray()
      }
      is StartPlugin -> {
        payloadType = PayloadType.START_PLUGIN.code
        payload = Json.encodeToString(msg).toByteArray()
      }
      is LoadPlugin -> {
        payloadType = PayloadType.LOAD_PLUGIN.code
        payload = Json.encodeToString(msg).toByteArray()
      }
      is PluginFinished -> {
        payloadType = PayloadType.PLUGIN_FINISHED.code
        payload = Json.encodeToString(msg).toByteArray()
      }
      is ByteArray -> {
        payloadType = PayloadType.RAW.code
        payload = msg
      }
      is Succeeded -> {
        payloadType = PayloadType.SUCCEEDED.code
        payload = Json.encodeToString(msg).toByteArray()
      }
      is Failed -> {
        payloadType = PayloadType.FAILED.code
        payload = Json.encodeToString(msg).toByteArray()
      }
      is ProtocolError -> {
        payloadType = PayloadType.PROTOCOL_ERROR.code
        payload = Json.encodeToString(msg).toByteArray()
      }
      else -> throw IllegalArgumentException()
    }
    dataOutputStream.writeRawMessage(
      flags = flags,
      requestId = requestId,
      payloadType = payloadType,
      payload = payload,
    )
  }
}

class MessageReader(val dataInputStream: DataInputStream) {
  private var nextMessage: DecodedMessage<Any>? = null

  @Synchronized
  fun peekNext(): DecodedMessage<Any> {
    if (nextMessage == null) {
      nextMessage = readNextLocked()
    }

    return nextMessage!!
  }

  @Synchronized
  fun consumeNext(): DecodedMessage<Any> {
    return peekNext().also { nextMessage = null }
  }

  private fun readNextLocked(): DecodedMessage<Any> {
    val rawMessage = dataInputStream.readRawMessage()
    val payload =
      when (rawMessage.payloadType) {
        PayloadType.RAW.code -> rawMessage.payload
        PayloadType.VERIFY_PROTOCOL_VERSION.code ->
          Json.decodeFromString<VerifyProtocolVersion>(String(rawMessage.payload))
        PayloadType.START_PLUGIN.code ->
          Json.decodeFromString<StartPlugin>(String(rawMessage.payload))
        PayloadType.LOAD_PLUGIN.code ->
          Json.decodeFromString<LoadPlugin>(String(rawMessage.payload))
        PayloadType.PLUGIN_FINISHED.code ->
          Json.decodeFromString<PluginFinished>(String(rawMessage.payload))
        PayloadType.SUCCEEDED.code -> Json.decodeFromString<Succeeded>(String(rawMessage.payload))
        PayloadType.FAILED.code -> Json.decodeFromString<Failed>(String(rawMessage.payload))
        PayloadType.PROTOCOL_ERROR.code ->
          Json.decodeFromString<ProtocolError>(String(rawMessage.payload))
        else -> throw IllegalArgumentException("Unknown payloadType: ${rawMessage.payloadType}")
      }

    val msg =
      DecodedMessage<Any>(
        flags = rawMessage.flags,
        requestId = rawMessage.requestId,
        payload = payload,
      )

    logVerbose { "read $msg" }

    return msg
  }
}
