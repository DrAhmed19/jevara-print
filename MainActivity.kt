package com.jevara.print

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val btAddrs = mutableListOf<String>()
    private val usbNames = mutableListOf<String>()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Printer.prefs(this)

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 30, 0, 8) }

        // نوع الاتصال
        root.addView(label("نوع الاتصال"))
        val types = listOf("bt" to "بلوتوث", "usb" to "USB", "wifi" to "واي فاي")
        val rgType = RadioGroup(this)
        types.forEachIndexed { idx, t -> rgType.addView(RadioButton(this).apply { text = t.second; id = idx + 1 }) }
        rgType.check(types.indexOfFirst { it.first == p.getString("type", "bt") }.coerceAtLeast(0) + 1)
        root.addView(rgType)

        // بلوتوث
        root.addView(label("طابعة البلوتوث (لازم تكون مقترنة من إعدادات الجهاز)"))
        val spBt = Spinner(this)
        val btNames = mutableListOf<String>()
        try {
            val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ad?.bondedDevices?.forEach { btNames += "${it.name} (${it.address})"; btAddrs += it.address }
        } catch (_: SecurityException) {}
        spBt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, btNames)
        btAddrs.indexOf(p.getString("bt", "")).takeIf { it >= 0 }?.let { spBt.setSelection(it) }
        root.addView(spBt)

        // USB
        root.addView(label("طابعة USB"))
        val spUsb = Spinner(this)
        val usbLabels = mutableListOf<String>()
        (getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.forEach {
            usbLabels += "${it.productName ?: "USB"} (${it.vendorId}:${it.productId})"; usbNames += it.deviceName
        }
        spUsb.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, usbLabels)
        usbNames.indexOf(p.getString("usb", "")).takeIf { it >= 0 }?.let { spUsb.setSelection(it) }
        root.addView(spUsb)

        // واي فاي
        root.addView(label("واي فاي: IP والمنفذ"))
        val etIp = EditText(this).apply { hint = "192.168.1.100"; setText(p.getString("ip", "")) }
        val etPort = EditText(this).apply {
            hint = "9100"; inputType = InputType.TYPE_CLASS_NUMBER; setText(p.getInt("port", 9100).toString())
        }
        root.addView(etIp); root.addView(etPort)

        // عرض الورق
        root.addView(label("عرض الورق"))
        val rgW = RadioGroup(this)
        rgW.addView(RadioButton(this).apply { text = "58 ملم"; id = 1 })
        rgW.addView(RadioButton(this).apply { text = "80 ملم"; id = 2 })
        rgW.check(if (p.getInt("width", 384) >= 576) 2 else 1)
        root.addView(rgW)

        fun save() {
            p.edit().apply {
                putString("type", types[rgType.checkedRadioButtonId - 1].first)
                btAddrs.getOrNull(spBt.selectedItemPosition)?.let { putString("bt", it) }
                usbNames.getOrNull(spUsb.selectedItemPosition)?.let { putString("usb", it) }
                putString("ip", etIp.text.toString().trim())
                putInt("port", etPort.text.toString().toIntOrNull() ?: 9100)
                putInt("width", if (rgW.checkedRadioButtonId == 2) 576 else 384)
            }.apply()
        }

        root.addView(Button(this).apply {
            text = "حفظ"
            setOnClickListener { save(); Toast.makeText(context, "انحفظ", Toast.LENGTH_SHORT).show() }
        })
        root.addView(Button(this).apply {
            text = "طباعة تجريبية"
            setOnClickListener {
                save()
                val app = applicationContext
                val w = p.getInt("width", 384)
                Thread {
                    val msg = try {
                        val t = "مكتب جيفارا\nطباعة تجريبية ✓\nSample Print 12345\n"
                        Printer.send(app, Printer.rasterBytes(Printer.textBitmap(t, w), w))
                        "تمت الطباعة"
                    } catch (e: Exception) { e.message ?: "فشلت الطباعة" }
                    runOnUiThread { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
                }.start()
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(code, perms, res)
        recreate() // يعيد تحميل قائمة الطابعات بعد منح الصلاحية
    }
}
