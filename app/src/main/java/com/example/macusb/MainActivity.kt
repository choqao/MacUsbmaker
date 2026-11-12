package com.example.macusb

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var usb: UsbManager
    private val devices = mutableStateListOf<UsbDevice>()
    private var selected by mutableStateOf<UsbDevice?>(null)
    private var busy by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private val logLines = mutableStateListOf<String>()

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == ACTION_PERM) {
                val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                log(if (ok) "USB permission granted - tap Flash again." else "USB permission denied.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(UsbManager::class.java)
        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(ACTION_PERM), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)

        refreshDevices()
        setContent { MaterialTheme { Surface { Screen() } } }
    }

    override fun onResume() { super.onResume(); refreshDevices() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); refreshDevices() }
    override fun onDestroy() { unregisterReceiver(permReceiver); super.onDestroy() }

    private fun refreshDevices() {
        devices.clear()
        devices.addAll(usb.deviceList.values.filter { d ->
            (0 until d.interfaceCount).any {
                val f = d.getInterface(it)
                f.interfaceClass == 8 && f.interfaceSubclass == 6 && f.interfaceProtocol == 80
            }
        })
        if (selected !in devices) selected = devices.firstOrNull()
    }

    private fun log(s: String) = runOnUiThread { logLines.add(s) }

    private fun requestUsbPermission(d: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PERM).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usb.requestPermission(d, pi)
    }

    private fun sizeOf(uri: Uri): Long =
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: uri.lastPathSegment.orEmpty()

    private fun runJob(block: () -> Unit) {
        if (busy) return
        busy = true; progress = 0f
        KeepAliveService.start(this)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                log("ERROR: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                KeepAliveService.stop(this@MainActivity)
                busy = false
            }
        }
    }

    private fun startRecovery(tree: Uri, target: MacOsTarget, efi: Uri?) = runJob {
        RecoveryBuilder(this).build(tree, target, efi, ::log) { progress = it }
    }

    private fun startFlash(dev: UsbDevice, img: Uri) {
        if (!usb.hasPermission(dev)) { requestUsbPermission(dev); return }
        runJob {
            val total = sizeOf(img)
            if (total <= 0) throw IllegalStateException("Cannot determine image size")
            UsbScsiDevice(usb, dev).use { scsi ->
                scsi.open()
                log("Drive: ${scsi.capacityBytes / 1_000_000} MB, ${scsi.blockSize} B sectors")
                val input = contentResolver.openInputStream(img) ?: error("Cannot open image")
                input.use {
                    scsi.flashImage(it, total) { w, t -> progress = w.toFloat() / t }
                }
            }
            log("Flash complete. Safely remove the drive.")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var tab by remember { mutableIntStateOf(0) }
        var target by remember { mutableStateOf(MAC_OS_TARGETS[2]) }
        var customBoard by remember { mutableStateOf("") }
        var treeUri by remember { mutableStateOf<Uri?>(null) }
        var efiUri by remember { mutableStateOf<Uri?>(null) }
        var imgUri by remember { mutableStateOf<Uri?>(null) }
        var menu by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf(false) }

        val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { u ->
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                treeUri = u
            }
        }
        val pickEfi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { efiUri = it }
        val pickImg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { imgUri = it }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Mac USB Builder", style = MaterialTheme.typography.headlineSmall)

            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("macOS Recovery") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Flash .img") })
            }

            if (tab == 0) {
                Text("Drive must already be FAT32. Pick its root folder, then build.")
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(target.name) }
                    DropdownMenu(menu, { menu = false }) {
                        MAC_OS_TARGETS.forEach {
                            DropdownMenuItem(text = { Text(it.name) }, onClick = { target = it; menu = false })
                        }
                    }
                }
                OutlinedTextField(
                    customBoard, { customBoard = it }, label = { Text("Custom board ID (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Button(onClick = { pickTree.launch(null) }) { Text(treeUri?.let { "Drive: ${it.lastPathSegment}" } ?: "Select USB drive folder") }
                Button(onClick = { pickEfi.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text(efiUri?.let { "EFI: ${nameOf(it)}" } ?: "Select EFI .zip (optional)")
                }
                Button(
                    enabled = !busy && treeUri != null,
                    onClick = {
                        val t = if (customBoard.isNotBlank()) MacOsTarget("Custom", customBoard.trim()) else target
                        startRecovery(treeUri!!, t, efiUri)
                    }
                ) { Text("Download & build") }
            } else {
                Text("Detected USB drives:")
                if (devices.isEmpty()) Text("None. Plug the drive in (hub must be powered).")
                devices.forEach { d ->
                    Row {
                        RadioButton(selected == d, { selected = d })
                        Text(d.productName ?: d.deviceName, Modifier.padding(top = 12.dp))
                    }
                }
                OutlinedButton(onClick = { refreshDevices() }) { Text("Rescan") }
                Button(onClick = { pickImg.launch(arrayOf("*/*")) }) {
                    Text(imgUri?.let { "Image: ${nameOf(it)}" } ?: "Select .img file")
                }
                Button(enabled = !busy && selected != null && imgUri != null, onClick = { confirm = true }) {
                    Text("Flash (ERASES drive)")
                }
            }

            if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            logLines.takeLast(40).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Erase this drive?") },
                text = { Text("Everything on ${selected?.productName ?: "the selected drive"} will be overwritten.") },
                confirmButton = {
                    TextButton(onClick = { confirm = false; startFlash(selected!!, imgUri!!) }) { Text("Erase & flash") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
            )
        }
    }

    private companion object {
        const val ACTION_PERM = "com.example.macusb.USB_PERMISSION"
    }
}
