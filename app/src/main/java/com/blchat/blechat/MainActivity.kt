package com.blchat.blechat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.blchat.blechat.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity(), BleChatManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var manager: BleChatManager

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var deviceAdapter: DeviceAdapter

    private var versionDialogShown = false

    // 基础内边距（来自 XML，叠加安全区时用）
    private var headerBaseL = 0; private var headerBaseT = 0; private var headerBaseR = 0
    private var inputBaseL = 0; private var inputBaseR = 0; private var inputBaseB = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                startIfReady()
            } else {
                binding.tvStatus.text = "权限被拒绝"
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (manager.isEnabled()) startEngine() else binding.tvStatus.text = "请开启蓝牙"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = (application as BleChatApp).manager
        manager.addListener(this)

        setupUi()
        applyInsets()
        applyNavBarAppearance()
        refreshMyIdLabel()

        requestPermissionsAndStart()
    }

    // ------------------------------------------------------------------
    // UI 初始化
    // ------------------------------------------------------------------

    private fun setupUi() {
        messageAdapter = MessageAdapter(
            nameResolver = { id -> manager.getName(id) },
            onLongClick = { msg -> promptSetName(msg.id) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = messageAdapter
        (binding.recycler.itemAnimator as? DefaultItemAnimator)?.let {
            it.addDuration = 220
            it.moveDuration = 220
            it.changeDuration = 180
            it.removeDuration = 160
        }

        deviceAdapter = DeviceAdapter(
            nameResolver = { id -> manager.getName(id) },
            onLongClick = { d -> promptSetName(d.id) }
        )
        binding.rvDevices.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvDevices.adapter = deviceAdapter
        binding.rvDevices.itemAnimator = null

        binding.btnSend.setOnClickListener { doSend() }
        binding.btnSetName.setOnClickListener { promptSetMyName() }
        binding.tvMyId.setOnClickListener { promptSetMyName() }
        binding.btnClear.setOnClickListener { clearMessages() }
        binding.btnSync.setOnClickListener {
            if (manager.mappingCount() == 0) {
                android.widget.Toast.makeText(this, "映射表为空，先设置名称", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "正在同步映射表…", android.widget.Toast.LENGTH_SHORT).show()
                manager.syncMapping()
            }
        }

        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                doSend()
                true
            } else {
                false
            }
        }
    }

    private fun doSend() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        manager.send(text)
        binding.input.text?.clear()
    }

    private fun clearMessages() {
        if (messages.isEmpty()) return
        messages.clear()
        messageAdapter.submitList(emptyList())
        android.widget.Toast.makeText(this, "聊天记录已清空", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun refreshMyIdLabel() {
        val name = manager.myName
        binding.tvMyId.text = if (name.isBlank())
            getString(R.string.my_id_prefix) + manager.myId
        else
            "$name（${manager.myId}）"
    }

    // ------------------------------------------------------------------
    // 名称编辑
    // ------------------------------------------------------------------

    private fun promptSetMyName() {
        val input = EditText(this)
        input.setText(manager.myName)
        input.hint = "输入你的显示名称（最多 12 字）"
        input.filters = arrayOf(InputFilter.LengthFilter(12))
        input.setSelection(input.text.length)
        val container = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val pad = (24 * resources.displayMetrics.density).toInt()
        lp.leftMargin = pad; lp.rightMargin = pad
        container.addView(input, lp)

        MaterialAlertDialogBuilder(this)
            .setTitle("设置本机名称")
            .setMessage("本机 ID：${manager.myId}")
            .setView(container)
            .setPositiveButton("确定") { _, _ -> manager.setMyName(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptSetName(id: String) {
        val current = manager.getName(id) ?: ""
        val input = EditText(this)
        input.setText(current)
        input.hint = "输入名称（最多 12 字，留空删除）"
        input.filters = arrayOf(InputFilter.LengthFilter(12))
        input.setSelection(input.text.length)
        val container = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val pad = (24 * resources.displayMetrics.density).toInt()
        lp.leftMargin = pad; lp.rightMargin = pad
        container.addView(input, lp)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (id == manager.myId) "设置本机名称" else "为设备设置名称")
            .setMessage("ID：$id")
            .setView(container)
            .setPositiveButton("确定") { _, _ -> manager.setName(id, input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 刘海 / 挖孔 / 输入法安全区适配
    // ------------------------------------------------------------------

    private fun applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        headerBaseL = binding.header.paddingLeft
        headerBaseT = binding.header.paddingTop
        headerBaseR = binding.header.paddingRight
        inputBaseL = binding.inputBar.paddingLeft
        inputBaseR = binding.inputBar.paddingRight
        inputBaseB = binding.inputBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val cutout = insets.displayCutout

            val top = maxOf(bars.top, cutout?.safeInsetTop ?: 0)
            val bottom = maxOf(bars.bottom, ime.bottom, cutout?.safeInsetBottom ?: 0)
            val left = maxOf(bars.left, cutout?.safeInsetLeft ?: 0)
            val right = maxOf(bars.right, cutout?.safeInsetRight ?: 0)

            binding.header.setPadding(
                headerBaseL + left, headerBaseT + top, headerBaseR + right, binding.header.paddingBottom
            )
            binding.inputBar.setPadding(
                inputBaseL + left, binding.inputBar.paddingTop, inputBaseR + right, inputBaseB + bottom
            )
            insets
        }
    }

    private fun applyNavBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // 浅色模式导航栏在白色输入栏上→深色图标；深色模式→浅色图标
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = !night
    }

    // ------------------------------------------------------------------
    // 权限 / 蓝牙启动流程
    // ------------------------------------------------------------------

    private fun requestPermissionsAndStart() {
        if (manager.adapter == null) {
            binding.tvStatus.text = "设备不支持 BLE"
            return
        }
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startIfReady()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun neededPermissions(): List<String> {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        return needed
    }

    private fun allPermissionsGranted(): Boolean = neededPermissions().all {
        ContextCompat.checkSelfPermission(this, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startIfReady() {
        if (!manager.isEnabled()) {
            try {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                binding.tvStatus.text = "请手动开启蓝牙"
            }
            return
        }
        startEngine()
    }

    private fun startEngine() {
        // 先在当前进程内直接启动（保证前台可用），再尝试拉起前台服务做后台常驻
        try {
            manager.start()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "manager.start failed", e)
        }
        try {
            BleChatService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "start service failed", e)
        }
        binding.tvStatus.text = getString(R.string.status_scanning)
        // neverForLocation 后 API 31+ 无需系统定位；旧版扫描仍需
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            binding.tvStatus.text = "系统定位未开启，可能扫不到设备"
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    }

    // ------------------------------------------------------------------
    // BleChatManager.Listener
    // ------------------------------------------------------------------

    override fun onMessage(message: ChatMessage) {
        messages.add(message)
        messageAdapter.submitList(messages.toList()) {
            binding.recycler.smoothScrollToPosition(messageAdapter.itemCount - 1)
        }
    }

    override fun onDevicesChanged(devices: List<DeviceSignal>) {
        deviceAdapter.submitList(devices)
    }

    override fun onNamesChanged() {
        refreshMyIdLabel()
        messageAdapter.notifyDataSetChanged()
        deviceAdapter.notifyDataSetChanged()
    }

    override fun onMappingReceived(fromId: String, entries: Map<String, String>) {
        val fromName = manager.getName(fromId) ?: fromId
        val sb = StringBuilder()
        for ((id, name) in entries) {
            sb.append(id).append("  →  ").append(name).append('\n')
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("收到映射表")
            .setMessage("来自 $fromName（${entries.size} 条）：\n\n${sb.toString().trimEnd()}")
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onVersionHigher(version: Int) {
        if (versionDialogShown) return
        versionDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle("版本提示")
            .setMessage("收到了更高版本的信息（协议 v$version），可能在此版本显示异常，请升级应用。")
            .setPositiveButton("知道了") { _, _ -> versionDialogShown = false }
            .setOnCancelListener { versionDialogShown = false }
            .show()
    }

    override fun onState(active: Boolean, reason: String) {
        if (active) {
            android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            binding.tvStatus.text = reason
        }
    }

    override fun onResume() {
        super.onResume()
        if (::manager.isInitialized && manager.isEnabled() && allPermissionsGranted()) {
            startEngine()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        manager.removeListener(this)
    }
}
