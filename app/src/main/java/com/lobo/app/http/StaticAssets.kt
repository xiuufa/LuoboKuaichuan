package com.lobo.app.http

import android.content.Context
import com.lobo.app.util.MimeTypes
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.io.IOException

/** 提供 assets/web 下的静态网页资源。 */
object StaticAssets {

    private val SAFE_PATH = Regex("[A-Za-z0-9_\\-./]+")

    fun serve(context: Context, rawPath: String): Response? {
        val relative = normalize(rawPath) ?: return null
        val bytes = try {
            context.assets.open("web/$relative").use { it.readBytes() }
        } catch (_: IOException) {
            return null
        }
        val mime = MimeTypes.forFileName(relative)
        val response = NanoHTTPD.newFixedLengthResponse(
            Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        response.addHeader(
            "Cache-Control",
            if (relative.startsWith("vendor/")) "public, max-age=31536000" else "no-cache"
        )
        return HttpUtil.withCors(response)
    }

    private fun normalize(rawPath: String): String? {
        var path = rawPath.trim()
        while (path.startsWith("/")) path = path.substring(1)
        if (path.isEmpty()) return "index.html"
        if (!SAFE_PATH.matches(path)) return null
        if (path.endsWith("/")) return null
        if (path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return path
    }
}
