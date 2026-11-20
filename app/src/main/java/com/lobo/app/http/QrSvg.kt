package com.lobo.app.http

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 用 zxing 生成二维码 SVG（纯 Kotlin，可被 JVM 单测覆盖，也让网页端直接 <img src="/api/qr.svg">）。 */
object QrSvg {

    fun generate(
        data: String,
        foreground: String = "#201B17",
        background: String = "#FFFFFF",
        outputSize: Int = 480,
        margin: Int = 1
    ): String {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, outputSize, outputSize, hints)
        val width = matrix.width
        val height = matrix.height
        val builder = StringBuilder(width * height / 6 + 256)
        builder.append("<svg xmlns=\"http://www.w3.org/2000/svg\"")
        builder.append(" viewBox=\"0 0 ").append(width).append(' ').append(height).append('"')
        builder.append(" width=\"").append(width).append("\" height=\"").append(height).append('"')
        builder.append(" shape-rendering=\"crispEdges\">")
        builder.append("<rect width=\"100%\" height=\"100%\" fill=\"").append(background).append("\"/>")
        builder.append("<path fill=\"").append(foreground).append("\" d=\"")
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if (!matrix.get(x, y)) {
                    x++
                    continue
                }
                val start = x
                while (x < width && matrix.get(x, y)) x++
                builder.append('M').append(start).append(' ').append(y)
                builder.append('h').append(x - start).append("v1H").append(start).append('z')
            }
        }
        builder.append("\"/></svg>")
        return builder.toString()
    }
}
