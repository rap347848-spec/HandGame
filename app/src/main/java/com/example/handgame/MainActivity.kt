package com.example.handgame

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val c = Controller()
    private var pending: (() -> Unit)? = null
    private var onDenied: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = pending; pending = null
        val denied = onDenied; onDenied = null
        if (it.values.all { ok -> ok }) next?.invoke()
        else if (denied != null) denied()      // المضيف يكمل بالواي فاي حتى بدون صلاحية البلوتوث
        else c.msg = "الصلاحيات مطلوبة للبلوتوث"
    }
    private val discLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        c.beginListening()      // حتى لو رفض إظهار البلوتوث، تبقى الاستضافة عبر الواي فاي
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // اسم اللاعب يُحفظ على الجوال ويمكن تغييره من شاشة البداية
        val prefs = getSharedPreferences("handremmy", MODE_PRIVATE)
        c.rename(prefs.getString("name", "لاعب") ?: "لاعب")
        c.onName = { prefs.edit().putString("name", it).apply() }
        c.winsHalf = prefs.getInt("winsHalf", 0)
        c.onRecord = { prefs.edit().putInt("winsHalf", it).apply() }
        c.loadH2h(prefs.getString("h2h", "") ?: "")
        c.onH2h = { prefs.edit().putString("h2h", it).apply() }
        setContent { App(c, onHost = ::host, onJoin = ::join) }
    }

    private fun ensure(denied: (() -> Unit)? = null, next: () -> Unit) {
        val need = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyList()
        val miss = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isEmpty()) next() else { pending = next; onDenied = denied; permLauncher.launch(miss.toTypedArray()) }
    }

    private fun host() {
        if (BluetoothAdapter.getDefaultAdapter() == null) { c.beginListening(); return }   // جهاز بلا بلوتوث: واي فاي فقط
        ensure(denied = { c.beginListening() }) {
            // يطلب تفعيل البلوتوث + جعل الجهاز مرئياً لمدة 5 دقائق (والواي فاي يعمل في كل الأحوال)
            try {
                discLauncher.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                )
            } catch (_: Exception) { c.beginListening() }
        }
    }

    private fun join() = ensure { c.openJoin() }
}
