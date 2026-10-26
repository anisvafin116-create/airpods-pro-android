package com.example.airpodscontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button as AndroidButton
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID

data class AirPodsState(
    val modelName: String = "Поиск AirPods Pro...",
    val leftBattery: Int = -1,
    val rightBattery: Int = -1,
    val caseBattery: Int = -1,
    val isLeftCharging: Boolean = false,
    val isRightCharging: Boolean = false,
    val isCaseCharging: Boolean = false,
    val isLeftInEar: Boolean = false,
    val isRightInEar: Boolean = false,
    val rssi: Int = -100,
    val isConnectedAacp: Boolean = false,
    val ancMode: Int = 2,
    val autoPlayPause: Boolean = true,
    val isServiceRunning: Boolean = false
)

object AirPodsRepository {
    private val _state = MutableStateFlow(AirPodsState())
    val state: StateFlow<AirPodsState> = _state.asStateFlow()

    fun update(context: Context, transform: (AirPodsState) -> AirPodsState) {
        _state.value = transform(_state.value)
        AirPodsWidgetProvider.updateAllWidgets(context, _state.value)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val state by AirPodsRepository.state.collectAsState()
            var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val btGranted = permissions.entries.filter { it.key != Manifest.permission.POST_NOTIFICATIONS }.all { it.value }
                if (btGranted) startAirPodsService(context, AirPodsService.ACTION_START)
            }

            LaunchedEffect(Unit) {
                val perms = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    perms.add(Manifest.permission.BLUETOOTH_SCAN)
                    perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    perms.add(Manifest.permission.BLUETOOTH)
                    perms.add(Manifest.permission.BLUETOOTH_ADMIN)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(text = state.modelName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BatteryCard("Левый", state.leftBattery, state.isLeftCharging, state.isLeftInEar)
                            BatteryCard("Кейс", state.caseBattery, state.isCaseCharging, false)
                            BatteryCard("Правый", state.rightBattery, state.isRightCharging, state.isRightInEar)
                        }

                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Шумоподавление", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    TextButton(onClick = { startAirPodsService(context, AirPodsService.ACTION_RECONNECT_AACP) }) {
                                        Text(if (state.isConnectedAacp) "Активно" else "Сокет")
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AncButton("Выкл", state.ancMode == 1, Modifier.weight(1f)) { sendAncIntent(context, 1) }
                                    AncButton("Шумодав", state.ancMode == 2, Modifier.weight(1f)) { sendAncIntent(context, 2) }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AncButton("Прозрачность", state.ancMode == 3, Modifier.weight(1f)) { sendAncIntent(context, 3) }
                                    AncButton("Адаптивный", state.ancMode == 4, Modifier.weight(1f)) { sendAncIntent(context, 4) }
                                }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Система и Виджет", color = Color.White, fontWeight = FontWeight.SemiBold)
                                if (!canDrawOverlays) {
                                    Button(
                                        onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text("Разрешить Pop-up поверх окон", color = Color.White) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { startAirPodsService(context, AirPodsService.ACTION_TEST_POPUP) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text("Тест Pop-up", color = Color.White) }

                                    Button(
                                        onClick = {
                                            val action = if (state.isServiceRunning) AirPodsService.ACTION_STOP else AirPodsService.ACTION_START
                                            startAirPodsService(context, action)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (state.isServiceRunning) Color(0xFFC62828) else Color(0xFF2E7D32)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text(if (state.isServiceRunning) "Стоп" else "Старт", color = Color.White) }
                                }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Автопауза (Датчик уха)", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Пауза при снятии наушника", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(checked = state.autoPlayPause, onCheckedChange = { enabled -> AirPodsRepository.update(context) { it.copy(autoPlayPause = enabled) } })
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Поиск наушников (Радар)", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(8.dp))
                                val distanceText = when {
                                    state.rssi > -55 -> "Рядом (< 1м)"
                                    state.rssi > -75 -> "В комнате (1-5м)"
                                    state.rssi > -95 -> "Сигнал слабый"
                                    else -> "Нет сигнала"
                                }
                                Text("Сигнал: ${state.rssi} dBm — $distanceText", color = Color(0xFF64B5F6))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startAirPodsService(context: Context, action: String) {
        val intent = Intent(context, AirPodsService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun sendAncIntent(context: Context, mode: Int) {
        val intent = Intent(context, AirPodsService::class.java).apply {
            action = AirPodsService.ACTION_SET_ANC
            putExtra(AirPodsService.EXTRA_ANC_MODE, mode)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, AirPodsService::class.java).apply {
                action = AirPodsService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}

class AirPodsWidgetProvider : AppWidgetProvider() {
    companion object {
        fun updateAllWidgets(context: Context, state: AirPodsState) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, AirPodsWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (widgetId in allWidgetIds) {
                updateSingleWidget(context, appWidgetManager, widgetId, state)
            }
        }

        private fun updateSingleWidget(context: Context, manager: AppWidgetManager, widgetId: Int, state: AirPodsState) {
            val views = RemoteViews(context.packageName, R.layout.airpods_widget)

            views.setTextViewText(R.id.widget_title, state.modelName)
            views.setTextViewText(R.id.widget_left, if (state.leftBattery >= 0) "${state.leftBattery}%${if (state.isLeftCharging) "⚡" else ""}" else "—")
            views.setTextViewText(R.id.widget_case, if (state.caseBattery >= 0) "${state.caseBattery}%${if (state.isCaseCharging) "⚡" else ""}" else "—")
            views.setTextViewText(R.id.widget_right, if (state.rightBattery >= 0) "${state.rightBattery}%${if (state.isRightCharging) "⚡" else ""}" else "—")

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val currentState = AirPodsRepository.state.value
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId, currentState)
        }
    }
}

class AirPodsService : Service() {
    companion object {
        const val CHANNEL_ID = "airpods_status_channel"
        const val NOTIFICATION_ID = 101

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SET_ANC = "ACTION_SET_ANC"
        const val ACTION_RECONNECT_AACP = "ACTION_RECONNECT_AACP"
        const val ACTION_TEST_POPUP = "ACTION_TEST_POPUP"
        const val EXTRA_ANC_MODE = "EXTRA_ANC_MODE"

        private const val APPLE_COMPANY_ID = 0x004C
        private val AACP_UUID: UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    }

    private var aacpSocket: BluetoothSocket? = null
    private var aacpOutputStream: OutputStream? = null
    private var isScanning = false
    private var lastInEarState = false
    private var lastCaseTime = 0L
    private var lastNotifTime = 0L
    private var lastSummary = ""

    private var popupView: View? = null
    private var popupLeftText: TextView? = null
    private var popupCaseText: TextView? = null
    private var popupRightText: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissPopupRunnable = Runnable { removePopup() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBleScan()
                removePopup()
                AirPodsRepository.update(this) { it.copy(isServiceRunning = false) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_ANC -> {
                val mode = intent.getIntExtra(EXTRA_ANC_MODE, 2)
                AirPodsRepository.update(this) { it.copy(ancMode = mode) }
                sendAncCommand(mode.toByte())
                updateNotificationIfNeeded(true)
                return START_STICKY
            }
            ACTION_RECONNECT_AACP -> {
                connectToAacpSocket()
                return START_STICKY
            }
            ACTION_TEST_POPUP -> {
                showCasePopup(AirPodsRepository.state.value)
                return START_STICKY
            }
        }

        try {
            val notification = buildNotification(AirPodsRepository.state.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AirPodsRepository.update(this) { it.copy(isServiceRunning = true) }
            startBleScan()
            connectToAacpSocket()
        } catch (_: Exception) {}

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) return
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = btManager.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setManufacturerData(APPLE_COMPANY_ID, byteArrayOf(0x07, 0x19), byteArrayOf(0xFF.toByte(), 0xFF.toByte())).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            btManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
            if (data.size < 27) return

            val current = AirPodsRepository.state.value
            val parsed = decodeApplePacket(data, result.rssi, current)
            AirPodsRepository.update(applicationContext) { parsed }

            val bothInEar = parsed.isLeftInEar && parsed.isRightInEar
            if (parsed.autoPlayPause && bothInEar != lastInEarState) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val keyCode = if (bothInEar) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                lastInEarState = bothInEar
            }

            val now = System.currentTimeMillis()
            if (parsed.caseBattery in 0..100 && parsed.rssi > -72) {
                if (now - lastCaseTime > 8_000L) {
                    mainHandler.post { showCasePopup(parsed) }
                    connectToAacpSocket()
                }
                lastCaseTime = now
            }

            mainHandler.post { updatePopupContent(parsed) }
            updateNotificationIfNeeded(false)
        }
    }

    private fun decodeApplePacket(data: ByteArray, rssi: Int, prev: AirPodsState): AirPodsState {
        val modelCode = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val modelName = when (modelCode) {
            0x0E20 -> "AirPods Pro"
            0x1420 -> "AirPods Pro 2 (Lightning)"
            0x2420 -> "AirPods Pro 2 (USB-C)"
            else -> "AirPods Pro"
        }
        val isFlipped = (data[5].toInt() and 0x02) == 0
        val rawRight = (data[6].toInt() shr 4) and 0x0F
        val rawLeft = data[6].toInt() and 0x0F
        val rawCase = data[7].toInt() and 0x0F

        val leftBat = if (isFlipped) rawLeft else rawRight
        val rightBat = if (isFlipped) rawRight else rawLeft

        val chargeStatus = (data[7].toInt() shr 4) and 0x0F
        val leftCharging = if (isFlipped) (chargeStatus and 0x01) != 0 else (chargeStatus and 0x02) != 0
        val rightCharging = if (isFlipped) (chargeStatus and 0x02) != 0 else (chargeStatus and 0x01) != 0
        val caseCharging = (chargeStatus and 0x04) != 0

        val inEarBit1 = (data[5].toInt() and 0x08) != 0
        val inEarBit2 = (data[5].toInt() and 0x02) != 0

        return prev.copy(
            modelName = modelName,
            leftBattery = if (leftBat <= 10) leftBat * 10 else -1,
            rightBattery = if (rightBat <= 10) rightBat * 10 else -1,
            caseBattery = if (rawCase <= 10) rawCase * 10 else -1,
            isLeftCharging = leftCharging,
            isRightCharging = rightCharging,
            isCaseCharging = caseCharging,
            isLeftInEar = inEarBit1,
            isRightInEar = inEarBit2,
            rssi = rssi
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectToAacpSocket() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (aacpSocket?.isConnected == true) {
                    AirPodsRepository.update(applicationContext) { it.copy(isConnectedAacp = true) }
                    return@launch
                }
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val bondedAirPods: BluetoothDevice? = btManager.adapter?.bondedDevices?.firstOrNull {
                    it.name?.contains("AirPods", ignoreCase = true) == true
                } ?: return@launch

                aacpSocket?.close()
                val socket = bondedAirPods?.createInsecureRfcommSocketToServiceRecord(AACP_UUID)
                socket?.connect()
                aacpSocket = socket
                aacpOutputStream = socket?.outputStream

                val handshake = byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                aacpOutputStream?.write(handshake)
                aacpOutputStream?.flush()
                AirPodsRepository.update(applicationContext) { it.copy(isConnectedAacp = true) }
            } catch (_: Exception) {
                AirPodsRepository.update(applicationContext) { it.copy(isConnectedAacp = false) }
            }
        }
    }

    private fun sendAncCommand(modeByte: Byte) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val command = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, modeByte, 0x00, 0x00, 0x00)
                aacpOutputStream?.write(command)
                aacpOutputStream?.flush()
            } catch (_: Exception) {
                AirPodsRepository.update(applicationContext) { it.copy(isConnectedAacp = false) }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AirPods Status", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotificationIfNeeded(force: Boolean) {
        val state = AirPodsRepository.state.value
        val summary = "${state.leftBattery}_${state.caseBattery}_${state.rightBattery}_${state.ancMode}_${state.isLeftCharging}"
        val now = System.currentTimeMillis()
        if (!force && summary == lastSummary && now - lastNotifTime < 4_000L) return
        lastSummary = summary
        lastNotifTime = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: AirPodsState): Notification {
        val leftStr = if (state.leftBattery >= 0) "${state.leftBattery}%${if (state.isLeftCharging) "⚡" else ""}" else "—"
        val caseStr = if (state.caseBattery >= 0) "${state.caseBattery}%${if (state.isCaseCharging) "⚡" else ""}" else "—"
        val rightStr = if (state.rightBattery >= 0) "${state.rightBattery}%${if (state.isRightCharging) "⚡" else ""}" else "—"

        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(state.modelName)
            .setContentText("L: $leftStr   |   Кейс: $caseStr   |   R: $rightStr")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, if (state.ancMode == 2) "● Шумодав" else "Шумодав", makeAncPI(2))
            .addAction(0, if (state.ancMode == 3) "● Прозрачн." else "Прозрачн.", makeAncPI(3))
            .addAction(0, if (state.ancMode == 1) "● Выкл" else "Выкл", makeAncPI(1))
            .build()
    }

    private fun makeAncPI(mode: Int): PendingIntent {
        val intent = Intent(this, AirPodsService::class.java).apply { action = ACTION_SET_ANC; putExtra(EXTRA_ANC_MODE, mode) }
        return PendingIntent.getService(this, mode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun showCasePopup(state: AirPodsState) {
        if (!Settings.canDrawOverlays(this)) return
        if (popupView != null) {
            updatePopupContent(state)
            resetPopupTimer()
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 20.dp(), 24.dp(), 20.dp())
            background = GradientDrawable().apply { setColor(AndroidColor.parseColor("#F51E1E1E")); cornerRadius = 28f * density; setStroke(1.dp(), AndroidColor.parseColor("#33FFFFFF")) }
        }

        val titleView = TextView(this).apply { text = state.modelName; setTextColor(AndroidColor.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        root.addView(titleView)

        val batteryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 18.dp(), 0, 18.dp()) }
        val leftCol = createPopupCol("Левый")
        val caseCol = createPopupCol("Кейс")
        val rightCol = createPopupCol("Правый")
        popupLeftText = leftCol.second
        popupCaseText = caseCol.second
        popupRightText = rightCol.second

        val colParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        batteryRow.addView(leftCol.first, colParams)
        batteryRow.addView(caseCol.first, colParams)
        batteryRow.addView(rightCol.first, colParams)
        root.addView(batteryRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val ancBtn = createPopupBtn("Шумодав", "#2C2C2C") { AirPodsRepository.update(this) { it.copy(ancMode = 2) }; sendAncCommand(0x02); updateNotificationIfNeeded(true) }
        val transBtn = createPopupBtn("Прозрачность", "#2C2C2C") { AirPodsRepository.update(this) { it.copy(ancMode = 3) }; sendAncCommand(0x03); updateNotificationIfNeeded(true) }
        val closeBtn = createPopupBtn("Готово", "#2196F3") { removePopup() }

        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp(); marginEnd = 4.dp() }
        actionsRow.addView(ancBtn, btnParams)
        actionsRow.addView(transBtn, btnParams)
        actionsRow.addView(closeBtn, btnParams)
        root.addView(actionsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val layoutParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 32.dp() }

        popupView = root
        updatePopupContent(state)
        wm.addView(root, layoutParams)
        resetPopupTimer()
    }

    private fun createPopupCol(label: String): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val title = TextView(this).apply { text = label; setTextColor(AndroidColor.GRAY); textSize = 13f; gravity = Gravity.CENTER }
        val value = TextView(this).apply { text = "—"; setTextColor(AndroidColor.WHITE); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        col.addView(title); col.addView(value)
        return Pair(col, value)
    }

    private fun createPopupBtn(title: String, hexColor: String, onClick: () -> Unit): AndroidButton {
        val density = resources.displayMetrics.density
        return AndroidButton(this).apply {
            text = title; isAllCaps = false; textSize = 12f; setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply { setColor(AndroidColor.parseColor(hexColor)); cornerRadius = 14f * density }
            setOnClickListener { onClick() }
        }
    }

    private fun updatePopupContent(state: AirPodsState) {
        if (popupView == null) return
        popupLeftText?.text = if (state.leftBattery >= 0) "${state.leftBattery}%" else "—"
        popupCaseText?.text = if (state.caseBattery >= 0) "${state.caseBattery}%" else "—"
        popupRightText?.text = if (state.rightBattery >= 0) "${state.rightBattery}%" else "—"
    }

    private fun resetPopupTimer() {
        mainHandler.removeCallbacks(dismissPopupRunnable)
        mainHandler.postDelayed(dismissPopupRunnable, 8_000L)
    }

    private fun removePopup() {
        mainHandler.removeCallbacks(dismissPopupRunnable)
        popupView?.let { try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        popupView = null
        popupLeftText = null; popupCaseText = null; popupRightText = null
    }

    override fun onDestroy() {
        stopBleScan()
        removePopup()
        try { aacpSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

@Composable
fun BatteryCard(title: String, battery: Int, isCharging: Boolean, inEar: Boolean) {
    Card(modifier = Modifier.width(105.dp).height(120.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.Gray, fontSize = 14.sp)
            Text(text = if (battery >= 0) "$battery%" else "—", color = if (isCharging) Color(0xFF81C784) else Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(text = when { isCharging -> "⚡ Зарядка"; inEar -> "👂 В ухе"; else -> "" }, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@Composable
fun AncButton(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color(0xFF2196F3) else Color(0xFF2C2C2C)), shape = RoundedCornerShape(12.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
