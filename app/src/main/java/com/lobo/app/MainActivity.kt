package com.lobo.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lobo.app.core.AppConfig
import com.lobo.app.core.ServerBus
import com.lobo.app.core.ServerStatus
import android.content.Context
import android.os.PowerManager
import com.lobo.app.data.BoardClient
import com.lobo.app.notify.TransferNotifier
import com.lobo.app.data.BoardItem
import com.lobo.app.data.SettingsStore
import com.lobo.app.data.Uploader
import com.lobo.app.data.UriUploadSource
import com.lobo.app.databinding.ActivityMainBinding
import com.lobo.app.service.HttpServerService
import com.lobo.app.ui.BoardAdapter
import com.lobo.app.ui.BoardRow
import com.lobo.app.util.FormatUtils
import com.lobo.app.util.NetworkUtils
import com.lobo.app.util.QrCodeUtil
import com.lobo.app.util.StorageLocator
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * 萝卜快传主界面：本机共享开关 + 二维码/网址 + 共享传输板 + 底部输入栏。
 *
 * APP 自身也走 HTTP 客户端访问传输板：
 * - 主机模式：baseUrl = http://127.0.0.1:8080（由前台服务提供）
 * - 客户端模式：baseUrl = 扫码得到的其它主机地址
 */
class MainActivity : AppCompatActivity(), BoardAdapter.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: BoardAdapter
    private lateinit var uploader: Uploader

    /** 当前传输板客户端；null 表示未开启共享且未连接主机。 */
    @Volatile
    private var client: BoardClient? = null

    /** 扫码连接的主机地址；null 表示未连接远端。 */
    private var remoteUrl: String? = null
    private var plusPopup: PopupWindow? = null

    /** 本机共享开启后的局域网访问地址。 */
    private var hostUrl: String? = null

    private var items: List<BoardItem> = emptyList()
    private var version: Long = -1L
    private var pollJob: Job? = null
    private var refreshing = false
    private var connectionLost = false

    /** 客户端模式下自动接收文件的去重/跳过集合，以及串行下载任务。 */
    private val autoReceiveSkip = mutableSetOf<String>()
    private var autoReceiveJob: Job? = null

    /** 客户端模式已经处理过（已通知）的条目 id，用于发现“有新内容”。 */
    private val notifiedIds = mutableSetOf<String>()
    private var primeNotified = false
    private var lastImeVisible = false

    /** 用户希望共享开启（用于启动中状态显示，避免开关闪烁）。 */
    private var wantServer = false
    private var suppressSwitch = false

    /** 从其它应用分享进来、尚未发送的内容。 */
    private val pendingUris = mutableListOf<Uri>()
    private var pendingText: String? = null
    private var reconnectJob: Job? = null

    private var inputTouchDownY = 0f
    private var inputTouchDownX = 0f
    private var inputSwipeSent = false

    // ==================== 启动 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        adapter = BoardAdapter(SettingsStore.deviceId, this)
        binding.recyclerBoard.layoutManager = LinearLayoutManager(this)
        binding.recyclerBoard.adapter = adapter

        uploader = Uploader(
            scope = lifecycleScope,
            clientProvider = { client },
            senderProvider = { SettingsStore.deviceName to SettingsStore.deviceId },
            onBoardChanged = { refresh(force = true) }
        )

        setupToolbar()
        setupActions()
        setupObservers()
        updateInputAvailability()
        renderBoard()

        // 若服务已在运行（例如 Activity 重建），直接进入主机模式
        val status = ServerBus.status.value
        if (status.running) {
            wantServer = true
            renderServerStatus(status)
        } else {
            tryReconnectLastRemote()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 处理全面屏 / 状态栏 / 导航栏 / 输入法边距（targetSdk 35+ 默认 edge-to-edge）。 */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(system.left, system.top, system.right, maxOf(system.bottom, ime.bottom))
            // 键盘弹出时临时收起二维码，否则电话屏幕矮时机输入栏会被顶出可见区域。
            val imeVisible = ime.bottom > 0
            if (imeVisible != lastImeVisible) {
                lastImeVisible = imeVisible
                binding.qrPanel.isVisible = !imeVisible && ServerBus.status.value.running
            }
            insets
        }
    }

    /** 点击输入框以外的任意位置：让输入框失焦并收起键盘。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val rect = Rect()
                focused.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    getSystemService(InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ==================== 界面初始化 ====================

    private fun setupToolbar() {
        binding.toolbar.clipChildren = false
        binding.toolbar.clipToPadding = false
        binding.toolbar.setOnMenuItemClickListener { item: MenuItem ->
            if (item.itemId == R.id.action_plus) {
                togglePlusMenu()
                true
            } else {
                false
            }
        }
    }

    // ==================== 右上角「+」菜单（加号下方右侧展开，加号旋转成叉号） ====================

    private fun plusAnchorView(): View? = binding.toolbar.findViewById(R.id.action_plus)

    private fun togglePlusMenu() {
        val popup = plusPopup
        if (popup != null && popup.isShowing) {
            popup.dismiss()
            return
        }
        showPlusMenu()
    }

    private fun rotatePlus(open: Boolean) {
        plusAnchorView()?.animate()
            ?.rotation(if (open) 45f else 0f)
            ?.setDuration(PLUS_ROTATE_MS)
            ?.start()
    }

    private fun setupActions() {
        binding.switchServer.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) enableSharing() else disableSharing()
        }
        binding.tvServerUrl.setOnClickListener { hostUrl?.let { copyText(it) } }
        binding.btnCopyUrl.setOnClickListener { hostUrl?.let { copyText(it) } ?: toast(R.string.server_no_address) }
        binding.btnOpenBrowser.setOnClickListener { openBoardInBrowser() }
        binding.btnRefresh.setOnClickListener { refresh(force = true) }
        binding.btnQrToggle.setOnClickListener {
            SettingsStore.qrExpanded = !SettingsStore.qrExpanded
            applyQrExpanded()
        }
        binding.btnSend.setOnClickListener { sendInput() }
        binding.btnAttach.setOnClickListener {
            if (client == null) toast(R.string.no_target) else pickFiles.launch(arrayOf("*/*"))
        }
        binding.btnDisconnect.setOnClickListener { disconnectRemote() }
        binding.etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.etInput.hint = null
            } else {
                refreshInputHint()
            }
        }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else {
                false
            }
        }
        // 输入框上滑：发送剪切板内容
        binding.etInput.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inputTouchDownY = event.y
                    inputTouchDownX = event.x
                    inputSwipeSent = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = inputTouchDownY - event.y
                    val dx = abs(event.x - inputTouchDownX)
                    if (!inputSwipeSent && dy > dp(80) && dx < dp(60)) {
                        inputSwipeSent = true
                        sendClipboard()
                    }
                }
            }
            false
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ServerBus.status.collect { status -> renderServerStatus(status) }
                }
                launch {
                    NetworkUtils.addresses.collect { renderNetworkHint() }
                }
                launch {
                    uploader.tasks.collect { renderBoard() }
                }
            }
        }
    }

    // ==================== 共享服务（主机模式） ====================

    private fun enableSharing() {
        wantServer = true
        if (remoteUrl != null) disconnectRemote(showToast = false)
        maybeRequestNotificationPermission()
        if (!StorageLocator.hasAllFilesAccess()) showAllFilesDialog()
        binding.tvShareStatus.text = getString(R.string.server_starting)
        HttpServerService.start(this)
    }

    private fun disableSharing() {
        wantServer = false
        HttpServerService.stop(this)
        client = null
        stopPolling()
        items = emptyList()
        version = -1L
        hostUrl = null
        binding.qrPanel.isVisible = false
        binding.tvShareStatus.text = getString(R.string.server_stopped)
        renderBoard()
        updateInputAvailability()
    }

    private fun applyQrExpanded() {
        val expanded = SettingsStore.qrExpanded
        binding.qrContent.isVisible = expanded
        binding.btnQrToggle.setText(if (expanded) R.string.qr_collapse else R.string.qr_expand)
        binding.btnQrToggle.setIconResource(
            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }

    private fun renderServerStatus(status: ServerStatus) {
        if (status.running) wantServer = true
        if (status.error != null && !status.running) wantServer = false

        val wantOn = status.running || wantServer
        if (binding.switchServer.isChecked != wantOn) {
            suppressSwitch = true
            binding.switchServer.isChecked = wantOn
            suppressSwitch = false
        }

        // 客户端模式（扫码连别人）：优先展示远端信息
        if (remoteUrl != null && !status.running) {
            binding.tvShareStatus.text = getString(R.string.remote_title)
            binding.qrPanel.isVisible = false
            binding.tvOffHint.isVisible = false
            binding.cardRemote.isVisible = true
            return
        }

        binding.qrPanel.isVisible = status.running
        if (status.running) applyQrExpanded()
        binding.tvOffHint.isVisible = !status.running
        binding.tvShareStatus.text = when {
            status.running -> getString(R.string.server_running)
            status.error != null -> getString(R.string.server_error, status.error)
            wantServer -> getString(R.string.server_starting)
            else -> getString(R.string.server_stopped)
        }

        if (!status.running) {
            hostUrl = null
            if (client != null) {
                client = null
                stopPolling()
                items = emptyList()
                renderBoard()
                updateInputAvailability()
            }
            return
        }

        val url = status.url ?: NetworkUtils.urlFor(status.port)
        hostUrl = url
        if (url != null) {
            binding.tvServerUrl.text = url
            runCatching { binding.ivQr.setImageBitmap(QrCodeUtil.generate(url, 560)) }
        } else {
            binding.tvServerUrl.text = getString(R.string.server_no_address)
            binding.ivQr.setImageDrawable(null)
        }

        if (remoteUrl == null) {
            val localBase = "http://127.0.0.1:${status.port}"
            if (client?.baseUrl != localBase) {
                client = BoardClient(localBase)
                binding.cardRemote.isVisible = false
                items = emptyList()
                version = -1L
                startPolling()
                refresh(force = true)
                updateInputAvailability()
                flushPendingShares()
            }
        }
    }

    private fun renderNetworkHint() {
        if (remoteUrl != null) return
        if (ServerBus.status.value.running) {
            val url = NetworkUtils.urlFor(AppConfig.HTTP_PORT)
            if (url != null) {
                if (hostUrl != url) {
                    hostUrl = url
                    binding.tvServerUrl.text = url
                    runCatching { binding.ivQr.setImageBitmap(QrCodeUtil.generate(url, 560)) }
                }
                binding.tvNetworkHint.text = getString(R.string.server_url_hint)
            } else {
                binding.tvNetworkHint.text = getString(R.string.server_no_address)
            }
        }
    }

    // ==================== 客户端模式（扫码连接） ====================

    private fun tryReconnectLastRemote(onFailure: (() -> Unit)? = null) {
        val last = SettingsStore.lastRemoteUrl
        if (last.isNullOrBlank()) return
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { BoardClient.probe(last) }
                if (info != null) {
                    applyRemoteConnection(last, info)
                } else {
                    SettingsStore.lastRemoteUrl = null
                    // 分享进来但上次的电脑不在线：改为本机开启共享，让文件先落到传输板
                    if (onFailure != null) {
                        onFailure()
                    } else if (hasPendingShare()) {
                        startHostForShare()
                    }
                }
            } finally {
                reconnectJob = null
            }
        }
    }

    private fun connectToRemote(rawUrl: String, confirmIfHosting: Boolean) {
        val base = BoardClient.normalize(rawUrl)
        if (base.isEmpty()) {
            toast(R.string.scan_invalid)
            return
        }
        val proceed: () -> Unit = {
            if (binding.switchServer.isChecked) {
                wantServer = false
                suppressSwitch = true
                binding.switchServer.isChecked = false
                suppressSwitch = false
                HttpServerService.stop(this)
            }
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { BoardClient.probe(base) }
                if (info == null) {
                    toast(getString(R.string.scan_connect_failed, base))
                } else {
                    applyRemoteConnection(base, info)
                }
            }
        }
        if (confirmIfHosting && binding.switchServer.isChecked) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remote_connect_confirm_title)
                .setMessage(R.string.remote_connect_confirm_message)
                .setPositiveButton(R.string.action_continue) { _, _ -> proceed() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            proceed()
        }
    }

    private fun applyRemoteConnection(base: String, info: com.lobo.app.data.ServerInfo) {
        remoteUrl = base
        SettingsStore.lastRemoteUrl = base
        client = BoardClient(base)
        hostUrl = null
        binding.cardRemote.isVisible = true
        binding.tvRemoteInfo.text = getString(R.string.remote_connected, info.deviceName.ifBlank { base })
        binding.tvShareStatus.text = getString(R.string.remote_title)
        binding.qrPanel.isVisible = false
        binding.tvOffHint.isVisible = false
        items = emptyList()
        version = -1L
        notifiedIds.clear()
        primeNotified = true
        renderBoard()
        startPolling()
        refresh(force = true)
        updateInputAvailability()
        flushPendingShares()
        toast(getString(R.string.remote_connected, info.deviceName.ifBlank { base }))
    }

    private fun disconnectRemote(showToast: Boolean = true) {
        remoteUrl = null
        SettingsStore.lastRemoteUrl = null
        client = null
        binding.cardRemote.isVisible = false
        stopPolling()
        items = emptyList()
        version = -1L
        renderBoard()
        updateInputAvailability()
        renderServerStatus(ServerBus.status.value)
        if (showToast) toast(R.string.remote_disconnected)
    }

    // ==================== 传输板刷新 ====================

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                refreshBoard()
                delay(AppConfig.BOARD_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun refresh(force: Boolean = false) {
        lifecycleScope.launch { refreshBoard(force) }
    }

    private suspend fun refreshBoard(force: Boolean = false) {
        val activeClient = client ?: return
        if (refreshing) return
        refreshing = true
        try {
            val result = withContext(Dispatchers.IO) { activeClient.list() }
            version = result.version
            items = result.items
            if (connectionLost) {
                connectionLost = false
            }
            notifyNewItems()
            renderBoard()
            maybeAutoReceive()
        } catch (t: Throwable) {
            if (!connectionLost) {
                connectionLost = true
                if (remoteUrl != null) toast(R.string.board_connection_lost)
            }
        } finally {
            refreshing = false
        }
    }

    private fun renderBoard() {
        val tasks = uploader.tasks.value.filter {
            it.state == Uploader.State.QUEUED ||
                it.state == Uploader.State.UPLOADING ||
                it.state == Uploader.State.PAUSED ||
                it.state == Uploader.State.FAILED
        }
        val localIds = buildSet {
            tasks.forEach {
                it.itemId?.let { id -> add(id) }
                it.uploadId?.let { id -> add(id) }
            }
        }
        val rows = ArrayList<BoardRow>(tasks.size + items.size)
        tasks.forEach { rows.add(BoardRow.Task(it)) }
        items.filter { it.id !in localIds }.forEach { rows.add(BoardRow.Item(it)) }
        adapter.submitList(rows)
        binding.emptyView.isVisible = rows.isEmpty()
        binding.tvBoardTitle.text = getString(R.string.board_count, items.size)
    }

    /** 输入框提示：聚焦时隐藏，失焦且内容为空时恢复。 */
    private fun refreshInputHint() {
        val res = if (client != null) R.string.input_hint else R.string.input_hint_offline
        binding.etInput.hint =
            if (binding.etInput.hasFocus()) null else getString(res)
    }

    private fun updateInputAvailability() {
        val online = client != null
        binding.etInput.isEnabled = online
        binding.btnSend.isEnabled = online
        binding.btnSend.alpha = if (online) 1f else 0.4f
        binding.btnAttach.isEnabled = online
        refreshInputHint()
        binding.btnDisconnect.isVisible = remoteUrl != null
        if (!online) {
            binding.etInput.setText("")
        }
        flushPendingShares()
    }

    // ==================== 发送 ====================

    private fun sendInput() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) {
            // 输入框为空时，发送按钮直接发送当前剪贴板内容
            sendClipboard()
            return
        }
        val activeClient = client ?: run {
            toast(R.string.no_target)
            return
        }
        binding.etInput.setText("")
        sendTextNow(activeClient, text, restoreInputOnFailure = true)
    }

    /** 真正的发送动作；分享进 App 的文本也走这里（失败时把内容放回输入框避免丢失）。 */
    private fun sendTextNow(activeClient: BoardClient, text: String, restoreInputOnFailure: Boolean) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activeClient.sendText(text, SettingsStore.deviceName, SettingsStore.deviceId)
                }
                refresh(force = true)
            } catch (t: Throwable) {
                if (restoreInputOnFailure) binding.etInput.setText(text)
                toast(getString(R.string.send_failed, t.message ?: ""))
            }
        }
    }

    private fun sendClipboard() {
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            toast(R.string.clipboard_empty)
            return
        }
        val activeClient = client ?: run {
            toast(R.string.no_target)
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activeClient.sendText(text, SettingsStore.deviceName, SettingsStore.deviceId)
                }
                toast(R.string.clipboard_sent)
                refresh(force = true)
            } catch (t: Throwable) {
                toast(getString(R.string.send_failed, t.message ?: ""))
            }
        }
    }

    private fun readClipboard(): String? {
        val manager = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun copyText(text: String) {
        if (text.isEmpty()) return
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
            toast(R.string.copied)
        }.onFailure { toast(R.string.copy_failed) }
    }

    // ==================== 文件上传 ====================

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) addSources(uris)
    }

    private fun addSources(uris: List<Uri>) {
        if (client == null) {
            toast(R.string.no_target)
            return
        }
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val source = UriUploadSource(this@MainActivity, uri)
                        if (source.size > 0) source else UriUploadSource.copyToCache(this@MainActivity, uri)
                    }.getOrNull()
                }
            }
            if (sources.isEmpty()) {
                toast(R.string.open_failed)
                return@launch
            }
            uploader.enqueueAll(sources)
            toast(R.string.pending_files_added)
            renderBoard()
        }
    }

    private fun flushPendingShares() {
        val activeClient = client ?: return
        pendingText?.let { text ->
            pendingText = null
            if (text.isNotBlank()) sendTextNow(activeClient, text.trim(), restoreInputOnFailure = true)
        }
        if (pendingUris.isNotEmpty()) {
            val uris = pendingUris.toList()
            pendingUris.clear()
            addSources(uris)
        }
    }

    // ==================== 传输板条目操作 ====================

    private fun openItem(item: BoardItem) {
        if (item.isText) {
            copyText(item.text)
            return
        }
        if (!item.isReady) {
            toast(R.string.file_receiving_tip)
            return
        }
        val local = hostLocalFile(item)
        if (local != null) {
            openLocalFile(local)
            return
        }
        toast(R.string.saving)
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { downloadItem(item) }
                openLocalFile(file)
            } catch (t: Throwable) {
                toast(getString(R.string.save_failed, t.message ?: ""))
            }
        }
    }

    private fun saveItem(item: BoardItem) {
        if (item.isText) {
            copyText(item.text)
            return
        }
        val local = hostLocalFile(item)
        if (local != null) {
            toast(getString(R.string.save_ok, local.absolutePath))
            return
        }
        toast(R.string.saving)
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { downloadItem(item) }
                toast(getString(R.string.save_ok, file.absolutePath))
            } catch (t: Throwable) {
                toast(getString(R.string.save_failed, t.message ?: ""))
            }
        }
    }

    private fun hostLocalFile(item: BoardItem): File? {
        if (remoteUrl != null) return localCopyOf(item)
        return runCatching {
            val repository = LuoboApp.instance.repository
            repository.get(item.id)?.let { repository.fileOf(it) }?.takeIf { it.isFile }
        }.getOrNull()
    }

    /** 客户端模式发现新内容：App 在后台（或息屏）时发通知。 */
    private fun notifyNewItems() {
        if (remoteUrl == null) return
        if (primeNotified) {
            items.forEach { if (it.isText || it.isReady) notifiedIds.add(it.id) }
            primeNotified = false
            return
        }
        val myId = SettingsStore.deviceId
        for (item in items) {
            if (item.id in notifiedIds) continue
            if (!(item.isText || item.isReady)) continue
            notifiedIds.add(item.id)
            if (item.senderId == myId) continue
            if (!shouldNotifyBackground()) continue
            if (item.isText) {
                TransferNotifier.notifyText(this, item)
            } else {
                TransferNotifier.notifyFile(this, item)
            }
        }
    }

    /** App 不前台或屏幕熄灭时返回 true（此时新内容需要通知）。 */
    private fun shouldNotifyBackground(): Boolean {
        val app = application as LuoboApp
        if (!app.isForeground) return true
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !power.isInteractive
    }

    /** 客户端模式：把远端已就绪的文件自动下载到本机 Luobo 目录，避免还要手动点。 */
    private fun maybeAutoReceive() {
        if (remoteUrl == null) return
        if (autoReceiveJob?.isActive == true) return
        val myId = SettingsStore.deviceId
        val pending = items.filter { item ->
            item.isFile && item.isReady && item.size > 0L &&
                item.senderId != myId &&
                item.id !in autoReceiveSkip &&
                localCopyOf(item) == null
        }.sortedBy { it.size }
        if (pending.isEmpty()) return
        autoReceiveJob = lifecycleScope.launch {
            for (item in pending) {
                if (!isActive) break
                if (localCopyOf(item) != null) continue
                toast(getString(R.string.auto_receiving, item.name))
                try {
                    val file = withContext(Dispatchers.IO) { downloadItem(item) }
                    toast(getString(R.string.auto_received, file.absolutePath))
                    if (shouldNotifyBackground()) TransferNotifier.notifySaved(this@MainActivity, item)
                } catch (t: Throwable) {
                    autoReceiveSkip.add(item.id)
                }
            }
        }
    }

    /** 本机是否已经有一份完整副本（按文件名 + 大小判断）。 */
    private fun localCopyOf(item: BoardItem): File? {
        if (!item.isFile) return null
        val dir = StorageLocator.mediaDir(this)
        val file = File(dir, sanitizeName(item.name))
        return file.takeIf { it.isFile && (item.size <= 0L || it.length() == item.size) }
    }

    private fun downloadItem(item: BoardItem): File {
        val activeClient = client ?: throw IOException(getString(R.string.no_target))
        val dir = StorageLocator.mediaDir(this)
        if (!dir.isDirectory) dir.mkdirs()
        val target = uniqueFile(dir, sanitizeName(item.name))
        val connection = URL(activeClient.fileUrl(item.id) + "?download=1").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 120_000
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val tmp = File(dir, target.name + ".part")
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output, 256 * 1024) }
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                }
            } finally {
                tmp.delete()
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    private fun openLocalFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, com.lobo.app.util.MimeTypes.forFileName(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_open)))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.open_failed)
        } catch (t: Throwable) {
            toast(getString(R.string.save_failed, t.message ?: ""))
        }
    }

    private fun requestDelete(item: BoardItem) {
        if (item.isText || item.size <= AppConfig.BIG_FILE_THRESHOLD) {
            deleteItem(item)
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, item.name, FormatUtils.formatSize(item.size)))
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteItem(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    private fun deleteItem(item: BoardItem) {
        val activeClient = client ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { activeClient.deleteItem(item.id) }
                items = items.filterNot { it.id == item.id }
                renderBoard()
            } catch (t: Throwable) {
                toast(getString(R.string.network_error, t.message ?: ""))
            }
        }
    }

    private fun showMessageActions(item: BoardItem) {
        if (item.isText) {
            MaterialAlertDialogBuilder(this)
                .setItems(
                    arrayOf(
                        getString(R.string.action_copy),
                        getString(R.string.action_resend),
                        getString(R.string.action_delete_record)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> copyText(item.text)
                        1 -> onResend(item)
                        else -> deleteItem(item)
                    }
                }
                .show()
        } else {
            val actions = mutableListOf(
                getString(R.string.action_open),
                getString(R.string.action_save),
                getString(R.string.action_resend)
            )
            if (item.isReady) {
                actions.add(getString(R.string.action_open_location))
                if (item.senderId != SettingsStore.deviceId) {
                    actions.add(getString(R.string.action_delete_file))
                }
            }
            actions.add(getString(R.string.action_delete_record))
            MaterialAlertDialogBuilder(this)
                .setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        getString(R.string.action_open) -> openItem(item)
                        getString(R.string.action_save) -> saveItem(item)
                        getString(R.string.action_resend) -> onResend(item)
                        getString(R.string.action_open_location) -> onOpenLocation(item)
                        getString(R.string.action_delete_file) -> onDeleteFile(item)
                        else -> deleteItem(item)
                    }
                }
                .show()
        }
    }

    // ==================== BoardAdapter.Listener ====================

    override fun onTextClick(item: BoardItem) = copyText(item.text)

    override fun onFileClick(item: BoardItem) = openItem(item)

    override fun onItemLongClick(item: BoardItem): Boolean {
        showMessageActions(item)
        return true
    }

    override fun onResend(item: BoardItem) {
        val activeClient = client ?: run {
            toast(R.string.no_target)
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { activeClient.resendItem(item.id) }
                refresh(force = true)
                toast(R.string.sent)
            } catch (t: Throwable) {
                toast(getString(R.string.send_failed, t.message ?: ""))
            }
        }
    }

    override fun onOpenLocation(item: BoardItem) {
        val local = hostLocalFile(item)
        if (local != null) {
            openDirectory(local.parentFile ?: StorageLocator.mediaDir(this), local)
        } else {
            openFolder()
        }
    }

    override fun onDeleteFile(item: BoardItem) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_file_confirm_title)
            .setMessage(getString(R.string.delete_file_confirm_message, item.name))
            .setPositiveButton(R.string.action_delete_file) { _, _ -> deleteItemFile(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    override fun onDeleteRecord(item: BoardItem) = deleteItem(item)

    override fun onPauseTask(task: Uploader.Task) = uploader.pause(task.id)

    override fun onResumeTask(task: Uploader.Task) = uploader.resume(task.id)

    override fun onCancelTask(task: Uploader.Task) = uploader.cancel(task.id)

    override fun onRetryTask(task: Uploader.Task) = uploader.retry(task.id)

    private fun deleteItemFile(item: BoardItem) {
        val activeClient = client ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { activeClient.deleteItemFile(item.id) }
                refresh(force = true)
            } catch (t: Throwable) {
                toast(getString(R.string.network_error, t.message ?: ""))
            }
        }
    }

    /**
     * 在系统文件管理器中打开目录。
     *
     * FileProvider 的目录 URI 多数 ROM 无法处理，直接用它会导致「打开位置」失败并退化成复制路径；
     * 因此优先使用 ExternalStorageProvider 的目录文档 URI（系统「文件」应用可以直接定位到该目录），
     * 并依次尝试多种 MIME / 显式组件，只有系统确实没有任何文件管理器时才退回复制路径。
     */
    private fun openDirectory(dir: File, fallbackFile: File? = null) {
        externalStorageDocumentUri(dir)?.let { uri ->
            // 优先显式调起系统「文件」应用，避免出现只支持 resource/folder 的应用选择器
            if (launchDocumentsUi(uri)) return
            if (launchDirectory(uri)) return
        }
        runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", dir)
        }.getOrNull()?.let { uri ->
            if (launchDirectory(uri)) return
        }
        // 目录 URI 无法被系统解析时，至少打开文件管理器，而不是把文件本身交给第三方应用
        if (openFileManager()) return
        if (fallbackFile != null && fallbackFile.isFile) {
            openLocalFile(fallbackFile)
            return
        }
        copyText(dir.absolutePath)
        toast(getString(R.string.folder_open_failed, dir.absolutePath))
    }

    private fun launchDocumentsUi(uri: Uri): Boolean {
        for (component in DOCUMENTS_UI_COMPONENTS) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.component = component
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (launchQuietly(intent)) return true
        }
        return false
    }

    private fun launchDirectory(uri: Uri): Boolean {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "resource/folder")
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (launchQuietly(intent)) return true
        }
        // 部分 ROM 没有为目录 URI 注册默认处理，显式调起系统「文件」应用
        for (component in DOCUMENTS_UI_COMPONENTS) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.component = component
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (launchQuietly(intent)) return true
        }
        return false
    }

    private fun launchQuietly(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** 兜底：打开系统默认文件管理器（不依赖具体厂商的目录 URI 支持）。 */
    private fun openFileManager(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory("android.intent.category.APP_FILES")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchQuietly(intent)
    }

    /** 共享存储内的目录 → ExternalStorageProvider 文档 URI；私有目录 / Android/data 返回 null。 */
    private fun externalStorageDocumentUri(dir: File): Uri? {
        val target = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val root = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
            ?: return null
        val relative = target.relativeToOrNull(root) ?: return null
        val path = relative.path.replace(File.separatorChar, '/')
        if (path.isBlank()) return null
        if (path == "Android" || path.startsWith("Android/data") || path.startsWith("Android/obb")) {
            return null
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$path")
    }

    // ==================== 右上角「+」菜单 ====================

    private fun showPlusMenu() {
        val items = listOf(
            MENU_SCAN to R.string.action_scan,
            MENU_PICK to R.string.action_open_folder,
            MENU_CLIPBOARD to R.string.action_send_clipboard,
            MENU_BROWSER to R.string.action_open_browser,
            MENU_SETTINGS to R.string.action_settings,
            MENU_HELP to R.string.action_help,
            MENU_CLEAR to R.string.action_clear,
            MENU_DISCONNECT to R.string.action_disconnect,
            MENU_BATTERY to R.string.action_battery
        )
        val context = this
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(8).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.luobo_surface))
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val ripple = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .use { it.getDrawable(0) }
        items.forEach { (id, titleRes) ->
            val row = TextView(this).apply {
                text = getString(titleRes)
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.luobo_on_surface))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(32), 0)
                isClickable = true
                isFocusable = true
                background = ripple?.constantState?.newDrawable()?.mutate()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
                )
            }
            row.setOnClickListener { performMenuAction(id) }
            list.addView(row)
        }
        card.addView(list)

        val width = dp(196)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = PopupWindow(card, width, card.measuredHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setOnDismissListener {
                rotatePlus(false)
                plusPopup = null
            }
        }
        val anchor = plusAnchorView() ?: binding.toolbar
        plusPopup = popup
        popup.showAsDropDown(anchor, anchor.width - width, dp(2))
        rotatePlus(true)
    }

    private fun dismissPlusMenu() {
        plusPopup?.dismiss()
    }

    private fun performMenuAction(itemId: Int) {
        dismissPlusMenu()
        when (itemId) {
            MENU_SCAN -> requestCameraAndScan()
            MENU_PICK -> openFolder()

            MENU_CLIPBOARD -> sendClipboard()
            MENU_BROWSER -> openBoardInBrowser()
            MENU_SETTINGS -> showSettingsDialog()
            MENU_HELP -> showHelpDialog()
            MENU_CLEAR -> confirmClearBoard()
            MENU_DISCONNECT -> {
                if (remoteUrl != null) disconnectRemote() else toast(R.string.no_target)
            }

            MENU_BATTERY -> openBatterySettings()
        }
    }

    private fun openBoardInBrowser() {
        val url = hostUrl ?: remoteUrl
        if (url.isNullOrBlank()) {
            toast(R.string.no_target)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            toast(R.string.open_failed)
        }
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openScanner() else toast(R.string.permission_camera_rationale)
        }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val url = result.data?.getStringExtra(ScanActivity.EXTRA_SCAN_RESULT)
                if (!url.isNullOrBlank()) connectToRemote(url, confirmIfHosting = true)
            }
        }

    private fun openScanner() {
        try {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        } catch (t: Throwable) {
            toast(getString(R.string.open_failed))
        }
    }

    // ==================== 对话框 ====================

    private fun confirmClearBoard() {
        if (client == null) {
            toast(R.string.no_target)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.board_clear_confirm)
            .setMessage(R.string.board_clear_message)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                val activeClient = client ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { activeClient.clear() }
                        items = emptyList()
                        renderBoard()
                        toast(R.string.board_cleared)
                    } catch (t: Throwable) {
                        toast(getString(R.string.network_error, t.message ?: ""))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameHint = TextView(this).apply {
            text = getString(R.string.settings_device_name)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.luobo_on_surface_variant))
            textSize = 13f
        }
        val nameInput = EditText(this).apply {
            setText(SettingsStore.deviceName)
            hint = getString(R.string.settings_device_name_hint)
            setSingleLine()
        }
        val pathLabel = TextView(this).apply {
            val path = StorageLocator.displayPath(this@MainActivity)
            text = getString(R.string.settings_storage_title) + "\n" + path
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.luobo_on_surface_variant))
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(nameHint)
        container.addView(nameInput)
        container.addView(pathLabel)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                SettingsStore.deviceName = nameInput.text.toString()
                toast(R.string.settings_saved)
                refresh(force = true)
            }
            .setNeutralButton(R.string.settings_clear_board) { _, _ -> confirmClearBoard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHelpDialog() {
        val path = StorageLocator.displayPath(this)
        val text = SpannableStringBuilder()
        val noticeStart = text.length
        text.append(getString(R.string.help_notice))
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.status_red)),
            noticeStart,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.append("\n\n")
        text.append(getString(R.string.help_message, path))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(text)
            .setPositiveButton(R.string.help_ok, null)
            .setNeutralButton(R.string.help_open_folder) { _, _ -> openFolder() }
            .show()
    }

    private fun showAllFilesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.all_files_title)
            .setMessage(R.string.all_files_message)
            .setPositiveButton(R.string.all_files_grant) { _, _ -> requestAllFilesAccess() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestAllFilesAccess() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure { toast(R.string.open_failed) }
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast(R.string.open_failed) }
        }
    }

    private fun openFolder() {
        openDirectory(StorageLocator.mediaDir(this))
    }

    // ==================== 通知权限 / 分享 Intent ====================

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val type = intent.type.orEmpty()
                val uris = sharedUris(intent)
                var hasContent = false
                if (uris.isNotEmpty()) {
                    uris.forEach { uri -> if (!pendingUris.contains(uri)) pendingUris.add(uri) }
                    hasContent = true
                }
                // 文件分享附带说明文字时，说明文字仍作为独立文本发送
                if (!text.isNullOrBlank() && (uris.isEmpty() || type.startsWith("text/"))) {
                    pendingText = text
                    hasContent = true
                }
                if (hasContent) ensureShareTarget()
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = sharedUris(intent)
                if (uris.isNotEmpty()) {
                    uris.forEach { uri -> if (!pendingUris.contains(uri)) pendingUris.add(uri) }
                    ensureShareTarget()
                }
            }
        }
        flushPendingShares()
    }

    /** 兼容 EXTRA_STREAM 与 ClipData 两种分享方式，部分应用只填其中一种。 */
    private fun sharedUris(intent: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { result.add(it) }
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { result.addAll(it) }
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { result.add(it) }
            }
        }
        return result.distinct()
    }

    private fun hasPendingShare(): Boolean = pendingText != null || pendingUris.isNotEmpty()

    /**
     * 从系统分享进入时，如果还没有传输目标，则自动开启本机共享：
     * 文件会直接进入传输板，对方扫码/打开网址即可下载，不需要用户再点一次开关。
     */
    private fun ensureShareTarget() {
        if (client != null || remoteUrl != null) return
        if (wantServer || ServerBus.status.value.running) return
        val last = SettingsStore.lastRemoteUrl
        if (!last.isNullOrBlank()) {
            if (reconnectJob == null) tryReconnectLastRemote { startHostForShare() }
            return
        }
        startHostForShare()
    }

    private fun startHostForShare() {
        if (remoteUrl != null || wantServer || ServerBus.status.value.running) return
        wantServer = true
        if (!binding.switchServer.isChecked) {
            suppressSwitch = true
            binding.switchServer.isChecked = true
            suppressSwitch = false
        }
        maybeRequestNotificationPermission()
        binding.tvShareStatus.text = getString(R.string.server_starting)
        HttpServerService.start(this)
        toast(R.string.share_hosting_started)
    }

    // ==================== 工具 ====================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun sanitizeName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
        return name.ifBlank { "luobo_file" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val next = File(dir, "$base($index)$ext")
            if (!next.exists()) return next
            index++
        }
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        private val DOCUMENTS_UI_COMPONENTS = listOf(
            ComponentName(
                "com.google.android.documentsui",
                "com.android.documentsui.files.FilesActivity"
            ),
            ComponentName(
                "com.android.documentsui",
                "com.android.documentsui.files.FilesActivity"
            )
        )

        private const val PLUS_ROTATE_MS = 200L

        private const val MENU_SCAN = 1
        private const val MENU_PICK = 2
        private const val MENU_CLIPBOARD = 3
        private const val MENU_BROWSER = 4
        private const val MENU_SETTINGS = 5
        private const val MENU_HELP = 6
        private const val MENU_CLEAR = 7
        private const val MENU_DISCONNECT = 8
        private const val MENU_BATTERY = 9
    }
}
