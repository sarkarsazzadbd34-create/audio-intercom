package com.example.audiotx

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

object PairingManager {
    private val json = Json { encodeDefaults = true }

    /** 6-digit human-typeable code, also embedded in the QR payload. */
    fun generateCode(): String = (100000 + Random.nextInt(900000)).toString()

    fun buildPayload(
        mode: String,
        code: String,
        host: String? = null,
        port: Int? = null,
        signalingUrl: String? = null
    ): PairingPayload = PairingPayload(mode, code, host, port, signalingUrl)

    fun toQrBitmap(payload: PairingPayload, sizePx: Int = 800): Bitmap {
        val text = json.encodeToString(payload)
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    fun toJson(payload: PairingPayload): String = json.encodeToString(payload)
}
