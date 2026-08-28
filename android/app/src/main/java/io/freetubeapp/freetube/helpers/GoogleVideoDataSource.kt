package io.freetubeapp.freetube.helpers

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.nio.charset.StandardCharsets

/**
 * YouTube `googlevideo.com/videoplayback` rejects ordinary ranged GETs.
 * Shaka sends POST `{ 15: 0 }` with `alr=yes` and `range=` in the query;
 * a successful POST often returns another URL as the body (`http…`).
 */
@UnstableApi
class GoogleVideoDataSource(
  private val upstream: DefaultHttpDataSource
) : DataSource {
  private var leftover: ByteArray? = null
  private var leftoverPos = 0

  override fun addTransferListener(transferListener: TransferListener) {
    upstream.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    leftover = null
    leftoverPos = 0
    var spec = rewrite(dataSpec)
    var length = upstream.open(spec)
    val redirect = consumeIfRedirectBody()
    if (redirect != null) {
      Log.d(TAG, "googlevideo POST redirected to ${redirect.take(80)}")
      upstream.close()
      leftover = null
      leftoverPos = 0
      spec = rewrite(
        dataSpec.buildUpon()
          .setUri(Uri.parse(redirect))
          .setPosition(0)
          .setLength(C.LENGTH_UNSET.toLong())
          .build()
      )
      length = upstream.open(spec)
    }
    return length
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val prefix = leftover
    if (prefix != null && leftoverPos < prefix.size) {
      val n = minOf(length, prefix.size - leftoverPos)
      System.arraycopy(prefix, leftoverPos, buffer, offset, n)
      leftoverPos += n
      if (leftoverPos >= prefix.size) {
        leftover = null
      }
      return n
    }
    return upstream.read(buffer, offset, length)
  }

  override fun getUri(): Uri? = upstream.uri

  override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

  override fun close() {
    leftover = null
    leftoverPos = 0
    upstream.close()
  }

  private fun rewrite(dataSpec: DataSpec): DataSpec {
    val uri = dataSpec.uri
    val host = uri.host ?: return dataSpec
    if (!host.endsWith("googlevideo.com") || uri.path != "/videoplayback") {
      return dataSpec
    }

    val builder = uriWithoutKeys(uri, setOf("range", "alr")).buildUpon()
    if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
      val start = dataSpec.position
      val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
        ""
      } else {
        "${start + dataSpec.length - 1}"
      }
      builder.appendQueryParameter("range", "$start-$end")
    }
    builder.appendQueryParameter("alr", "yes")

    val headers = dataSpec.httpRequestHeaders.filterKeys { !it.equals("Range", ignoreCase = true) }
    return dataSpec.buildUpon()
      .setUri(builder.build())
      .setHttpMethod(DataSpec.HTTP_METHOD_POST)
      .setHttpBody(byteArrayOf(0x78, 0x00))
      .setHttpRequestHeaders(headers)
      .setPosition(0)
      .setLength(C.LENGTH_UNSET.toLong())
      .build()
  }

  private fun consumeIfRedirectBody(): String? {
    val buf = ByteArray(2048)
    val n = upstream.read(buf, 0, buf.size)
    if (n <= 0) {
      return null
    }
    val isHttp = n >= 4 &&
      buf[0] == 'h'.code.toByte() &&
      buf[1] == 't'.code.toByte() &&
      buf[2] == 't'.code.toByte() &&
      buf[3] == 'p'.code.toByte()
    if (!isHttp) {
      leftover = buf.copyOf(n)
      leftoverPos = 0
      return null
    }
    val text = StringBuilder(String(buf, 0, n, StandardCharsets.UTF_8))
    val extra = ByteArray(2048)
    while (true) {
      val read = upstream.read(extra, 0, extra.size)
      if (read <= 0) {
        break
      }
      text.append(String(extra, 0, read, StandardCharsets.UTF_8))
    }
    val url = text.toString().trim()
    return url.takeIf { it.startsWith("http") }
  }

  private fun uriWithoutKeys(uri: Uri, keys: Set<String>): Uri {
    val builder = uri.buildUpon().clearQuery()
    for (name in uri.queryParameterNames) {
      if (name in keys) {
        continue
      }
      for (value in uri.getQueryParameters(name)) {
        builder.appendQueryParameter(name, value)
      }
    }
    return builder.build()
  }

  class Factory(private val userAgent: String) : DataSource.Factory {
    override fun createDataSource(): DataSource {
      val http = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(
          mapOf(
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/"
          )
        )
        .createDataSource()
      return GoogleVideoDataSource(http)
    }
  }

  companion object {
    private const val TAG = "FreeTubePip"
  }
}
