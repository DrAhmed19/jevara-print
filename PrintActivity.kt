package com.jevara.print

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast

/** تستلم أوامر الطباعة من المتصفح/التطبيقات (rawbt: أو مشاركة نص/صورة) وتطبع بصمت */
class PrintActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val i = intent
        Thread {
            val msg = try {
                Printer.send(app, buildData(i))
                "تمت الطباعة ✓"
            } catch (e: Exception) {
                e.message ?: "فشلت الطباعة"
            }
            runOnUiThread {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun buildData(i: Intent): ByteArray {
        val w = Printer.prefs(this).getInt("width", 384)
        val d = i.data

        if (d != null && d.scheme == "rawbt") {
            val s = d.schemeSpecificPart ?: ""
            return if (s.startsWith("base64,")) {
                Base64.decode(s.substring(7).replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            } else {
                Printer.rasterBytes(Printer.textBitmap(s, w), w)
            }
        }

        if (i.action == Intent.ACTION_SEND) {
            if ((i.type ?: "").startsWith("image/")) {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                val bmp = uri?.let { contentResolver.openInputStream(it)?.use(BitmapFactory::decodeStream) }
                    ?: error("ما كدرت أقرأ الصورة")
                return Printer.rasterBytes(bmp, w)
            }
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: error("ماكو نص للطباعة")
            return Printer.rasterBytes(Printer.textBitmap(t, w), w)
        }
        error("أمر طباعة غير معروف")
    }
}
