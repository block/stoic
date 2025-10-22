package com.squareup.stoic.target.runtime

import java.io.DataInputStream
import java.io.DataOutputStream

class MultiplexedReader(private val stream: DataInputStream) {
  fun read(): Pair<Int, ByteArray> {
    val id = stream.readInt()
    val payloadSize = stream.readInt()
    val payload = ByteArray(payloadSize)
    stream.readFully(payload)

    return Pair(id, payload)
  }
}

class MultiplexedWriter(private val stream: DataOutputStream) {
  fun write(id: Int, buffer: ByteArray) {
    write(id, buffer, 0, buffer.size)
  }

  fun write(id: Int, buffer: ByteArray, offset: Int, length: Int) {
    stream.writeInt(id)
    stream.writeInt(length)
    stream.write(buffer, offset, length)
    stream.flush()
  }
}
