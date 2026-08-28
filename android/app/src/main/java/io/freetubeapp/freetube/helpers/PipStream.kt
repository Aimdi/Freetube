package io.freetubeapp.freetube.helpers

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Byte pipe between the page's MediaRecorder (WebM/fMP4 chunks over the
 * JS bridge) and ExoPlayer's progressive loader. Single producer, single
 * consumer, bounded only by playback keeping up (chunks are ~250ms).
 */
object PipStreamBuffer {
  private val queue = LinkedBlockingQueue<ByteArray>()
  @Volatile private var closed = false
  private var current: ByteArray? = null
  private var position = 0

  @Synchronized
  fun reset() {
    queue.clear()
    closed = false
    current = null
    position = 0
  }

  fun append(bytes: ByteArray) {
    if (!closed && bytes.isNotEmpty()) {
      queue.offer(bytes)
    }
  }

  fun close() {
    closed = true
    // sentinel wakes a blocked reader
    queue.offer(ByteArray(0))
  }

  /** @return bytes copied, or -1 at end of stream */
  @Synchronized
  fun read(target: ByteArray, offset: Int, length: Int): Int {
    while (true) {
      val chunk = current
      if (chunk != null && position < chunk.size) {
        val count = minOf(length, chunk.size - position)
        System.arraycopy(chunk, position, target, offset, count)
        position += count
        if (position >= chunk.size) {
          current = null
          position = 0
        }
        return count
      }
      if (closed && queue.isEmpty()) {
        return -1
      }
      val next = try {
        queue.poll(2, TimeUnit.SECONDS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return -1
      }
      if (next == null || next.isEmpty()) {
        if (closed) {
          return -1
        }
        continue
      }
      current = next
      position = 0
    }
  }
}

/** Unseekable live stream fed by [PipStreamBuffer]. */
class PipStreamDataSource : DataSource {
  private var uri: Uri? = null

  override fun addTransferListener(transferListener: TransferListener) {}

  override fun open(dataSpec: DataSpec): Long {
    if (dataSpec.position > 0) {
      throw IOException("pip stream is not seekable")
    }
    uri = dataSpec.uri
    return C.LENGTH_UNSET.toLong()
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) {
      return 0
    }
    val count = PipStreamBuffer.read(buffer, offset, length)
    return if (count < 0) C.RESULT_END_OF_INPUT else count
  }

  override fun getUri(): Uri? = uri

  override fun close() {}
}
