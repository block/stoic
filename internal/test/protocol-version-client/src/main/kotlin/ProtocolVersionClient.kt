package com.squareup.stoic.test

import com.squareup.stoic.bridge.StoicProperties
import com.squareup.stoic.common.Failed
import com.squareup.stoic.common.MessageReader
import com.squareup.stoic.common.MessageWriter
import com.squareup.stoic.common.ProtocolError
import com.squareup.stoic.common.Succeeded
import com.squareup.stoic.common.VerifyProtocolVersion
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.system.exitProcess

const val EXIT_ACCEPT = 0
const val EXIT_REJECT = 5
const val EXIT_ERROR = 10

/**
 * Test client that sends a VerifyProtocolVersion message with a specified protocol version.
 *
 * Usage: ProtocolVersionClient <host> <port> <protocol-version>
 */
fun main(args: Array<String>) {

  if (args.size != 3) {
    System.err.println("Usage: ProtocolVersionClient <host> <port> <protocol-version>")
    exitProcess(EXIT_ERROR)
  }

  val host = args[0]
  val port =
    args[1].toIntOrNull()
      ?: run {
        System.err.println("Invalid port: ${args[1]}")
        exitProcess(EXIT_ERROR)
      }
  val protocolVersion =
    args[2].toIntOrNull()
      ?: run {
        System.err.println("Invalid protocol version: ${args[2]}")
        exitProcess(EXIT_ERROR)
      }

  try {
    System.err.println("Connecting to $host:$port...")
    Socket(host, port).use { socket ->
      socket.soTimeout = 10000 // 10 second timeout
      socket.tcpNoDelay = true // Disable Nagle's algorithm - send immediately
      socket.setSoLinger(true, 5) // Wait up to 5 seconds when closing to ensure data is sent

      System.err.println("Connected! Creating streams...")
      val writer = MessageWriter(DataOutputStream(socket.getOutputStream()))
      val reader = MessageReader(DataInputStream(socket.getInputStream()))

      // Mark stdin as open for writing
      writer.openStdinForWriting()
      System.err.println("Opened stdin for writing")

      // Send VerifyProtocolVersion with the specified protocol version
      System.err.println("Sending VerifyProtocolVersion with protocol version $protocolVersion...")
      val versionRequestId =
        writer.writeRequest(
          VerifyProtocolVersion(protocolVersion, StoicProperties.STOIC_VERSION_NAME)
        )
      System.err.println("Sent VerifyProtocolVersion with request ID: $versionRequestId")

      // Read VerifyProtocolVersion response
      System.err.println("Waiting for VerifyProtocolVersion response...")
      val versionResponse = reader.consumeNext()
      System.err.println("Received response!")

      if (versionResponse.requestId != versionRequestId) {
        System.err.println("ERROR: Response request ID mismatch")
        exitProcess(EXIT_ERROR)
      }

      when (val payload = versionResponse.payload) {
        is Succeeded -> {
          System.err.println("SUCCESS: Server accepted protocol version $protocolVersion")
          System.err.println("Message: ${payload.message}")
          exitProcess(EXIT_ACCEPT)
        }
        is Failed -> {
          System.err.println("REJECTED: Server rejected protocol version $protocolVersion")
          System.err.println("Message: ${payload.message}")

          // After a version rejection, the server throws an exception which results in a
          // ProtocolError message being sent before the connection closes. Read and verify it.
          System.err.println("Expecting ProtocolError message...")
          val errorMessage = reader.consumeNext()
          val errorPayload = errorMessage.payload
          if (errorPayload is ProtocolError) {
            System.err.println("Received expected ProtocolError")
            exitProcess(EXIT_REJECT)
          } else {
            System.err.println(
              "ERROR: Expected ProtocolError but got ${errorPayload::class.simpleName}"
            )
            exitProcess(EXIT_ERROR)
          }
        }
        else -> {
          System.err.println("ERROR: Unexpected response type: ${payload::class.simpleName}")
          exitProcess(EXIT_ERROR)
        }
      }
    }
  } catch (e: Exception) {
    System.err.println("ERROR: ${e.message}")
    e.printStackTrace()
    exitProcess(EXIT_ERROR)
  }
}
