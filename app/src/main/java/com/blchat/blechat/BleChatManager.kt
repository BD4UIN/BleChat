package com.blchat.blechat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * BLE 广播聊天核心（常驻广播 + 常驻扫描 + 长消息分片 + 版本校验）。
 *
 * 长消息：把消息按单包容量切成多个分片，广播端轮转广播各分片（每片停留 CHUNK_DWELL_MS），
 * 接收端按「ID + 序列号」缓存分片，凑齐后合并成一条完整消息。
 * 名称只在在场信标与 0 号分片里携带，节省分片 1+ 的字节。
 */
@SuppressLint("MissingPermission")
class BleChatManager(context: Context) {

    interface Listener {
        fun onMessage(message: ChatMessage)
        fun onDevicesChanged(devices: List<DeviceSignal>)
        fun onNamesChanged()
        fun onMappingReceived(fromId: String, entries: Map<String, String>)
        /** 收到更高协议版本的消息 */
        fun onVersionHigher(version: Int)
        fun onState(active: Boolean, reason: String)
    }

    private class ChunkBuffer(val total: Int) {
        val parts = HashMap<Int, ByteArray>()
        var lastUpdate = System.currentTimeMillis()
        fun isComplete(): Boolean = parts.size == total
    }

    companion object {
        private const val TAG = "BleChatManager"
        private const val PREFS = "ble_chat_prefs"
        private const val KEY_ID = "device_id"

        private const val DEVICE_TTL_MS = 15_000L
        private const val CLEANUP_INTERVAL_MS = 5_000L
        private const val MAPPING_SYNC_MS = 15_000L
        private const val CHUNK_DWELL_MS = 400L
        private const val CHUNK_BUFFER_TTL_MS = 10_000L

        /** legacy 广播总长 31 字节：manufacturer data = 2(len+type) + 2(company) + payload */
        private const val LEGACY_PAYLOAD_MAX = 27
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val advertiser: BluetoothLeAdvertiser?
        get() = adapter?.bluetoothLeAdvertiser

    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    val myId: String = loadOrCreateId()

    private val nameStore = NameStore(appContext)
    val myName: String get() = nameStore.getName(myId) ?: ""

    private val listeners = CopyOnWriteArrayList<Listener>()

    private var isAdvertising = false
    private var isScanning = false

    private var seq = 0
    private var currentMessage: String? = null
    private var currentChunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var isMappingSync = false
    private var fallbackPayload: ByteArray? = null

    private val lastSeqPerId = HashMap<String, Int>()
    private val chunkBuffers = HashMap<String, ChunkBuffer>()
    private val devices = LinkedHashMap<String, DeviceSignal>()

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - DEVICE_TTL_MS
            val before = devices.size
            devices.entries.removeAll { it.value.lastSeen < cutoff }
            if (devices.size != before) pushDevices()

            // 清理超时未凑齐的分片缓冲
            val bufCutoff = System.currentTimeMillis() - CHUNK_BUFFER_TTL_MS
            chunkBuffers.entries.removeAll { it.value.lastUpdate < bufCutoff }

            mainHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    private val chunkAdvanceRunnable = object : Runnable {
        override fun run() {
            if (!isMappingSync && currentChunks.size > 1) {
                chunkIndex = (chunkIndex + 1) % currentChunks.size
                advertiseCurrentChunk()
            }
            mainHandler.postDelayed(this, CHUNK_DWELL_MS)
        }
    }

    private val revertMappingRunnable = Runnable {
        isMappingSync = false
        advertiseCurrentChunk()
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun isEnabled(): Boolean = adapter?.isEnabled == true
    fun getName(id: String): String? = nameStore.getName(id)

    fun supportsExtended(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter?.isLeExtendedAdvertisingSupported == true

    private fun legacyMaxChunkBytes(nameBytes: Int): Int =
        LEGACY_PAYLOAD_MAX - ChatProtocol.HEADER_LEN - 1 - nameBytes - ChatProtocol.CHUNK_META_LEN

    private fun extendedMaxChunkBytes(nameBytes: Int): Int =
        ChatProtocol.EXTENDED_MAX_PAYLOAD - ChatProtocol.HEADER_LEN - 1 - nameBytes - ChatProtocol.CHUNK_META_LEN

    // ---------------------------------------------------------------------
    // 启动 / 停止
    // ---------------------------------------------------------------------

    fun start(): Boolean {
        stopAdvertising()
        val advOk = advertiseCurrentChunk()
        val scanOk = startScanning()
        mainHandler.removeCallbacks(cleanupRunnable)
        mainHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
        mainHandler.removeCallbacks(chunkAdvanceRunnable)
        mainHandler.postDelayed(chunkAdvanceRunnable, CHUNK_DWELL_MS)
        return advOk || scanOk
    }

    // ---------------------------------------------------------------------
    // 发送 / 名称 / 同步
    // ---------------------------------------------------------------------

    fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty()) return

        seq = (seq + 1) and 0xFFFF
        currentMessage = text
        val nameBytes = myName.toByteArray(Charsets.UTF_8).size
        val maxChunk = if (supportsExtended()) extendedMaxChunkBytes(nameBytes) else legacyMaxChunkBytes(nameBytes)
        currentChunks = splitMessage(text, maxOf(maxChunk, 1))
        chunkIndex = 0
        isMappingSync = false

        postToUi {
            listeners.forEach {
                it.onMessage(
                    ChatMessage(
                        uid = "self-${System.currentTimeMillis()}-$seq",
                        id = myId,
                        content = text,
                        rssi = Int.MIN_VALUE,
                        timestamp = System.currentTimeMillis(),
                        isSelf = true
                    )
                )
            }
        }

        advertiseCurrentChunk()
    }

    fun setMyName(name: String) {
        nameStore.setName(myId, name)
        postToUi { listeners.forEach { it.onNamesChanged() } }
        advertiseCurrentChunk()
    }

    fun setName(id: String, name: String) {
        nameStore.setName(id, name)
        postToUi { listeners.forEach { it.onNamesChanged() } }
    }

    fun syncMapping() {
        val entries = nameStore.allEntries()
        if (entries.isEmpty()) return
        val maxPayload = if (supportsExtended()) ChatProtocol.EXTENDED_MAX_PAYLOAD else LEGACY_PAYLOAD_MAX
        val full = ChatProtocol.encodeMapping(myId, ChatProtocol.CURRENT_VERSION, seq, entries, maxPayload)
        val fallback = ChatProtocol.encodeMapping(myId, ChatProtocol.CURRENT_VERSION, seq, entries, LEGACY_PAYLOAD_MAX)

        isMappingSync = true
        mainHandler.removeCallbacks(revertMappingRunnable)
        stopAdvertising()
        advertise(full, fallback)
        mainHandler.postDelayed(revertMappingRunnable, MAPPING_SYNC_MS)
    }

    fun mappingCount(): Int = nameStore.allEntries().size

    // ---------------------------------------------------------------------
    // 广播
    // ---------------------------------------------------------------------

    private fun advertiseCurrentChunk(): Boolean {
        if (isMappingSync) return true

        if (currentChunks.isEmpty()) {
            // 在场信标（total=0，携带名称用于自动绑定）
            val p = ChatProtocol.encodeChatChunk(myId, ChatProtocol.CURRENT_VERSION, seq, myName, 0, 0, "")
            return advertise(p, p)
        }

        val total = currentChunks.size
        val index = chunkIndex.coerceIn(0, total - 1)
        val data = currentChunks[index]
        // 名称只放在 0 号分片，节省后续分片字节
        val name = if (index == 0) myName else ""
        val nameLenHere = name.toByteArray(Charsets.UTF_8).size

        val full = ChatProtocol.encodeChatChunk(myId, ChatProtocol.CURRENT_VERSION, seq, name, total, index, data)
        val legacyMax = legacyMaxChunkBytes(nameLenHere)
        val legacyData = truncateUtf8(data, maxOf(legacyMax, 0))
        val fallback = ChatProtocol.encodeChatChunk(myId, ChatProtocol.CURRENT_VERSION, seq, name, total, index, legacyData)
        return advertise(full, fallback)
    }

    private fun advertise(payload: ByteArray, fallback: ByteArray): Boolean {
        val useExtended = supportsExtended() && payload.size > LEGACY_PAYLOAD_MAX
        return if (useExtended) startExtended(payload, fallback) else startLegacy(payload)
    }

    private fun startLegacy(payload: ByteArray): Boolean {
        val adv = advertiser ?: return false
        val data = AdvertiseData.Builder()
            .addManufacturerData(ChatProtocol.COMPANY_ID, payload)
            .setIncludeDeviceName(false)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        return try {
            adv.startAdvertising(settings, data, legacyCallback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "legacy advertise failed", e)
            postToUi { listeners.forEach { it.onState(false, "广播启动失败: ${e.message}") } }
            false
        }
    }

    private fun startExtended(payload: ByteArray, fallback: ByteArray): Boolean {
        val adv = advertiser ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return startLegacy(payload)
        fallbackPayload = fallback
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(ChatProtocol.COMPANY_ID, payload)
            .setIncludeDeviceName(false)
            .build()
        return try {
            adv.startAdvertisingSet(params, data, null, null, null, extendedCallback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "extended advertise failed, fallback legacy", e)
            advertiseFallback()
            false
        }
    }

    private fun advertiseFallback() {
        val p = fallbackPayload ?: return
        fallbackPayload = null
        startLegacy(p)
    }

    private val legacyCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            fallbackPayload = null
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            postToUi { listeners.forEach { it.onState(false, "广播失败: $errorCode") } }
        }
    }

    private val extendedCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            advertisingSet: android.bluetooth.le.AdvertisingSet?,
            txPower: Int,
            status: Int
        ) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                isAdvertising = true
                fallbackPayload = null
            } else {
                isAdvertising = false
                Log.w(TAG, "extended advertise status=$status, fallback to legacy")
                advertiseFallback()
            }
        }
    }

    private fun stopAdvertising() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                advertiser?.stopAdvertisingSet(extendedCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopAdvertisingSet", e)
        }
        try {
            advertiser?.stopAdvertising(legacyCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopAdvertising", e)
        }
        isAdvertising = false
    }

    // ---------------------------------------------------------------------
    // 扫描
    // ---------------------------------------------------------------------

    fun startScanning(): Boolean {
        val sc = scanner ?: return false
        if (isScanning) return true
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        return try {
            sc.startScan(null, builder.build(), scanCallback)
            isScanning = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            false
        }
    }

    fun stopScanning() {
        try {
            if (isScanning) scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopScan", e)
        }
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            postToUi { listeners.forEach { it.onState(false, "扫描失败: $errorCode") } }
        }
    }

    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val data = record.getManufacturerSpecificData(ChatProtocol.COMPANY_ID) ?: return
        val decoded = ChatProtocol.decode(data) ?: return
        if (decoded.id == myId) return

        val rssi = result.rssi
        val now = System.currentTimeMillis()

        // 1) 信号
        devices[decoded.id] = DeviceSignal(decoded.id, rssi, now)
        pushDevices()

        // 2) 版本校验
        if (decoded.version > ChatProtocol.CURRENT_VERSION) {
            postToUi { listeners.forEach { it.onVersionHigher(decoded.version) } }
        }

        when (decoded.type) {
            ChatProtocol.TYPE_CHAT -> {
                if (decoded.name.isNotEmpty()) {
                    nameStore.setName(decoded.id, decoded.name)
                    postToUi { listeners.forEach { it.onNamesChanged() } }
                }
                if (decoded.chunkTotal >= 1) {
                    handleChunk(decoded, rssi, now)
                }
            }
            ChatProtocol.TYPE_MAPPING -> {
                if (decoded.mapping.isNotEmpty()) {
                    nameStore.merge(decoded.mapping)
                    postToUi {
                        listeners.forEach {
                            it.onNamesChanged()
                            it.onMappingReceived(decoded.id, decoded.mapping)
                        }
                    }
                }
            }
        }
    }

    private fun handleChunk(d: ChatProtocol.Decoded, rssi: Int, now: Long) {
        val key = "${d.id}:${d.seq}"
        if (lastSeqPerId[d.id] == d.seq) return // 已显示过
        val buf = chunkBuffers.getOrPut(key) { ChunkBuffer(d.chunkTotal) }
        buf.parts[d.chunkIndex] = d.chunkData.toByteArray(Charsets.UTF_8)
        buf.lastUpdate = now
        if (buf.isComplete()) {
            chunkBuffers.remove(key)
            lastSeqPerId[d.id] = d.seq
            val full = (0 until buf.total).joinToString("") { i ->
                String(buf.parts[i] ?: ByteArray(0), Charsets.UTF_8)
            }
            if (full.isNotEmpty()) {
                postToUi {
                    listeners.forEach {
                        it.onMessage(
                            ChatMessage(
                                uid = "${d.id}-${d.seq}",
                                id = d.id,
                                content = full,
                                rssi = rssi,
                                timestamp = now,
                                isSelf = false
                            )
                        )
                    }
                }
            }
        }
    }

    private fun pushDevices() {
        val list = devices.values.sortedByDescending { it.rssi }
        postToUi { listeners.forEach { it.onDevicesChanged(list) } }
    }

    // ---------------------------------------------------------------------
    // 清理 / 工具
    // ---------------------------------------------------------------------

    fun shutdown() {
        mainHandler.removeCallbacks(cleanupRunnable)
        mainHandler.removeCallbacks(chunkAdvanceRunnable)
        mainHandler.removeCallbacks(revertMappingRunnable)
        stopAdvertising()
        stopScanning()
    }

    private fun postToUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun splitMessage(message: String, maxChunkBytes: Int): List<String> {
        val result = mutableListOf<String>()
        var sb = StringBuilder()
        var bytes = 0
        for (ch in message) {
            val cb = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + cb > maxChunkBytes && sb.isNotEmpty()) {
                result.add(sb.toString())
                sb = StringBuilder()
                bytes = 0
            }
            sb.append(ch)
            bytes += cb
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        if (result.isEmpty()) result.add("")
        return result
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var bytes = 0
        for (ch in s) {
            val cb = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + cb > maxBytes) break
            sb.append(ch)
            bytes += cb
        }
        return sb.toString()
    }

    private fun loadOrCreateId(): String {
        val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val id = buildString {
            repeat(ChatProtocol.ID_LEN) { append(charset[Random.nextInt(charset.length)]) }
        }
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }
}
