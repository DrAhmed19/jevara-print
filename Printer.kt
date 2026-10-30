package com.jevara.print

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Printer {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val ACTION_USB = "com.jevara.print.USB_PERMISSION"

    fun prefs(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** يرسل بايتات ESC/POS خام للطابعة المحفوظة بالإعدادات */
    fun send(c: Context, data: ByteArray) {
        val p = prefs(c)
        when (p.getString("type", "bt")) {
            "wifi" -> sendWifi(p.getString("ip", "") ?: "", p.getInt("port", 9100), data)
            "usb" -> sendUsb(c.applicationContext, p.getString("usb", "") ?: "", data)
            else -> sendBt(c, p.getString("bt", "") ?: "", data)
        }
    }

    private fun sendWifi(ip: String, port: Int, data: ByteArray) {
        if (ip.isBlank()) error("اكتب IP الطابعة بالإعدادات")
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 5000)
            val o = s.getOutputStream()
            o.write(data)
            o.flush()
            Thread.sleep(400)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBt(c: Context, addr: String, data: ByteArray) {
        if (addr.isBlank()) error("اختار طابعة البلوتوث بالإعدادات")
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(c, android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) error("افتح التطبيق وامنح صلاحية البلوتوث")

        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("البلوتوث مو مدعوم")
        if (!adapter.isEnabled) error("شغّل البلوتوث")
        adapter.cancelDiscovery()
        val sock = adapter.getRemoteDevice(addr).createRfcommSocketToServiceRecord(SPP)
        sock.use {
            it.connect()
            val o = it.outputStream
            var i = 0
            while (i < data.size) {
                val n = minOf(1024, data.size - i)
                o.write(data, i, n)
                i += n
                Thread.sleep(20)
            }
            o.flush()
            Thread.sleep(600)
        }
    }

    private fun sendUsb(c: Context, name: String, data: ByteArray) {
        val um = c.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = um.deviceList[name] ?: error("طابعة USB مو متصلة")

        if (!um.hasPermission(dev)) {
            val latch = CountDownLatch(1)
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) = latch.countDown()
            }
            ContextCompat.registerReceiver(c, r, IntentFilter(ACTION_USB), ContextCompat.RECEIVER_NOT_EXPORTED)
            val flag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(c, 0, Intent(ACTION_USB).setPackage(c.packageName), flag)
            um.requestPermission(dev, pi)
            latch.await(30, TimeUnit.SECONDS)
            c.unregisterReceiver(r)
            if (!um.hasPermission(dev)) error("ما انعطت صلاحية USB")
        }

        var itf: UsbInterface? = null
        var ep: UsbEndpoint? = null
        val ifaces = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .sortedByDescending { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
        loop@ for (i in ifaces) {
            for (e in 0 until i.endpointCount) {
                val x = i.getEndpoint(e)
                if (x.type == UsbConstants.USB_ENDPOINT_XFER_BULK && x.direction == UsbConstants.USB_DIR_OUT) {
                    itf = i; ep = x; break@loop
                }
            }
        }
        if (itf == null || ep == null) error("ما لقيت منفذ طباعة بالجهاز")

        val conn = um.openDevice(dev) ?: error("ما كدرت أفتح جهاز USB")
        try {
            conn.claimInterface(itf, true)
            var i = 0
            while (i < data.size) {
                val n = minOf(16384, data.size - i)
                val r = conn.bulkTransfer(ep, data.copyOfRange(i, i + n), n, 5000)
                if (r < 0) error("فشل الإرسال عبر USB")
                i += n
            }
            conn.releaseInterface(itf)
        } finally {
            conn.close()
        }
    }

    // ───────── تحويل النص/الصورة إلى ESC/POS (raster) — يدعم العربي كامل ─────────

    fun textBitmap(text: String, width: Int): Bitmap {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = if (width > 400) 30f else 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val bmp = Bitmap.createBitmap(width, layout.height + 16, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)
        layout.draw(cv)
        return bmp
    }

    fun rasterBytes(src: Bitmap, width: Int): ByteArray {
        val h = maxOf(1, (src.height * width / src.width.toFloat()).toInt())
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)
        cv.drawBitmap(src, null, Rect(0, 0, width, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(width * h)
        bmp.getPixels(px, 0, width, 0, 0, width, h)

        val bpr = width / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // init
        var y = 0
        while (y < h) {
            val n = minOf(128, h - y)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0, (bpr and 255).toByte(), (bpr shr 8).toByte(), (n and 255).toByte(), (n shr 8).toByte()))
            val row = ByteArray(bpr * n)
            for (r in 0 until n) for (x in 0 until width) {
                val p = px[(y + r) * width + x]
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (lum < 140) row[r * bpr + x / 8] = (row[r * bpr + x / 8].toInt() or (0x80 shr (x % 8))).toByte()
            }
            out.write(row)
            y += n
        }
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00)) // feed + cut
        return out.toByteArray()
    }
}
