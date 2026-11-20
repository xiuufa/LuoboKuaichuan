package com.lobo.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lobo.app.R
import com.lobo.app.data.BoardItem
import com.lobo.app.data.Uploader
import com.lobo.app.databinding.ItemBoardBinding
import com.lobo.app.util.FormatUtils
import kotlin.math.roundToInt

/** 传输板列表的一行：服务端条目，或本地待发送/发送失败的任务。 */
sealed class BoardRow {
    data class Item(val item: BoardItem) : BoardRow()
    data class Task(val task: Uploader.Task) : BoardRow()
}

/**
 * 传输板适配器。
 *
 * 用 ListAdapter + DiffUtil 做增量更新：上传/接收的进度变化只走 [PAYLOAD_PROGRESS]，
 * 只刷新进度条与百分比，避免整条重新绑定（否则拖动左滑、点击按钮会被打断）。
 */
class BoardAdapter(
    private val myDeviceId: String,
    private val listener: Listener
) : ListAdapter<BoardRow, BoardAdapter.Holder>(DIFF) {

    interface Listener {
        fun onTextClick(item: BoardItem)
        fun onFileClick(item: BoardItem)
        fun onItemLongClick(item: BoardItem): Boolean
        fun onResend(item: BoardItem)
        fun onOpenLocation(item: BoardItem)
        fun onDeleteFile(item: BoardItem)
        fun onDeleteRecord(item: BoardItem)
        fun onPauseTask(task: Uploader.Task)
        fun onResumeTask(task: Uploader.Task)
        fun onCancelTask(task: Uploader.Task)
        fun onRetryTask(task: Uploader.Task)
    }

    fun rowAt(position: Int): BoardRow? = getItem(position)

    fun taskRowCount(): Int = currentList.count { it is BoardRow.Task }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBoardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val row = getItem(position)) {
            is BoardRow.Item -> bindItem(holder, row.item)
            is BoardRow.Task -> bindTask(holder, row.task)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val row = getItem(position)
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            when (row) {
                is BoardRow.Item -> {
                    holder.currentRow = row
                    bindItemProgress(holder, row.item)
                }

                is BoardRow.Task -> {
                    holder.currentRow = row
                    bindTaskProgress(holder, row.task)
                }
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }

    // ---------------------------------------------------------------- 绑定

    private fun bindItem(holder: Holder, item: BoardItem) {
        val binding = holder.binding
        holder.currentRow = BoardRow.Item(item)
        holder.swipeLayout.closeActionsImmediately()
        holder.swipeLayout.swipeEnabled = !item.isReceiving
        holder.swipeLayout.swipeListener = object : SwipeActionLayout.Listener {
            override fun onSwipeDelete() {
                listener.onDeleteRecord(item)
            }
        }

        binding.btnRetry.visibility = View.GONE
        binding.btnPause.visibility = View.GONE
        binding.btnCancel.visibility = View.GONE

        if (item.isText) {
            binding.tvBadge.text = binding.root.context.getString(R.string.badge_text)
            binding.tvTitle.text = item.text
            binding.tvTitle.maxLines = 3
            binding.tvMeta.text = buildString {
                append(binding.root.context.getString(R.string.meta_sender, item.sender.ifBlank { "匿名设备" }))
                append(" · ")
                append(FormatUtils.formatTime(item.createdAt))
            }
            binding.progressRow.visibility = View.GONE
            configureActions(binding, resend = true, openLocation = false, deleteFile = false, deleteRecord = true)
            binding.cardContent.setOnClickListener { listener.onTextClick(item) }
            binding.cardContent.setOnLongClickListener { listener.onItemLongClick(item) }
        } else {
            binding.tvBadge.text = fileBadge(item.name)
            binding.tvTitle.text = item.name
            binding.tvTitle.maxLines = 3
            binding.tvMeta.text = buildString {
                append(FormatUtils.formatSize(item.size))
                append(" · ")
                append(binding.root.context.getString(R.string.meta_sender, item.sender.ifBlank { "匿名设备" }))
                append(" · ")
                append(FormatUtils.formatTime(item.createdAt))
            }
            bindItemProgress(holder, item)
            if (item.isReady) {
                val mine = item.senderId == myDeviceId
                if (mine) {
                    configureActions(binding, resend = true, openLocation = false, deleteFile = false, deleteRecord = true)
                } else {
                    configureActions(binding, resend = false, openLocation = true, deleteFile = true, deleteRecord = true)
                }
            } else {
                configureActions(binding, resend = false, openLocation = false, deleteFile = false, deleteRecord = true)
            }
            binding.cardContent.setOnClickListener { listener.onFileClick(item) }
            binding.cardContent.setOnLongClickListener { listener.onItemLongClick(item) }
        }

        setupActionClicks(holder, item)
    }

    private fun bindItemProgress(holder: Holder, item: BoardItem) {
        val binding = holder.binding
        if (item.isReceiving) {
            val fraction = if (item.size > 0L) item.received.toFloat() / item.size else 0f
            binding.progressRow.visibility = View.VISIBLE
            binding.progress.progress = (fraction * 1000).roundToInt().coerceIn(0, 1000)
            binding.tvProgress.text = binding.root.context.getString(R.string.state_receiving) +
                " " + (fraction * 100).roundToInt() + "%"
        } else {
            binding.progressRow.visibility = View.GONE
        }
    }

    private fun bindTask(holder: Holder, task: Uploader.Task) {
        val binding = holder.binding
        val context = binding.root.context
        holder.currentRow = BoardRow.Task(task)
        holder.swipeLayout.closeActionsImmediately()
        holder.swipeLayout.swipeEnabled = false
        binding.tvBadge.text = fileBadge(task.name)
        binding.tvTitle.text = task.name
        binding.tvTitle.maxLines = 2
        binding.tvMeta.text = when (task.state) {
            Uploader.State.FAILED -> context.getString(R.string.state_failed) +
                (task.error?.let { "：$it" } ?: "")

            Uploader.State.PAUSED -> context.getString(R.string.state_paused) +
                " · " + FormatUtils.formatSize(task.size)

            Uploader.State.QUEUED -> context.getString(R.string.state_queued) +
                " · " + FormatUtils.formatSize(task.size)

            else -> context.getString(R.string.state_uploading) +
                " · " + FormatUtils.formatSize(task.size)
        }
        bindTaskProgress(holder, task)
        configureActions(binding, resend = false, openLocation = false, deleteFile = false, deleteRecord = false)

        val showActions = task.state == Uploader.State.UPLOADING ||
            task.state == Uploader.State.PAUSED ||
            task.state == Uploader.State.QUEUED ||
            task.state == Uploader.State.FAILED
        binding.btnPause.visibility = if (showActions && task.state != Uploader.State.FAILED) View.VISIBLE else View.GONE
        binding.btnCancel.visibility = if (showActions && task.state != Uploader.State.FAILED) View.VISIBLE else View.GONE
        binding.btnRetry.visibility = if (task.state == Uploader.State.FAILED) View.VISIBLE else View.GONE
        binding.btnPause.setImageResource(
            if (task.state == Uploader.State.PAUSED) R.drawable.ic_play else R.drawable.ic_pause
        )
        binding.btnPause.setOnClickListener {
            if (task.state == Uploader.State.PAUSED) listener.onResumeTask(task) else listener.onPauseTask(task)
        }
        binding.btnCancel.setOnClickListener { listener.onCancelTask(task) }
        binding.btnRetry.setOnClickListener { listener.onRetryTask(task) }
        binding.cardContent.setOnClickListener {
            if (task.state == Uploader.State.PAUSED) listener.onResumeTask(task) else listener.onPauseTask(task)
        }
        binding.cardContent.setOnLongClickListener { false }
    }

    private fun bindTaskProgress(holder: Holder, task: Uploader.Task) {
        val binding = holder.binding
        val context = binding.root.context
        val progressVisible = task.state == Uploader.State.UPLOADING ||
            task.state == Uploader.State.PAUSED ||
            task.state == Uploader.State.QUEUED
        binding.progressRow.visibility = if (progressVisible) View.VISIBLE else View.GONE
        if (progressVisible) {
            binding.progress.progress = task.progress
            val percent = (task.progress / 10f).roundToInt()
            binding.tvProgress.text = when (task.state) {
                Uploader.State.UPLOADING -> "$percent% · ${FormatUtils.formatSize(task.sent)} / ${FormatUtils.formatSize(task.size)}"
                Uploader.State.PAUSED -> context.getString(R.string.state_paused) + " · $percent%"
                else -> context.getString(R.string.state_queued) + " · " + FormatUtils.formatSize(task.size)
            }
        }
    }

    /** 只显示需要的动作按钮（最多 3 个，按可见性从右往左排布）。 */
    private fun configureActions(
        binding: ItemBoardBinding,
        resend: Boolean,
        openLocation: Boolean,
        deleteFile: Boolean,
        deleteRecord: Boolean
    ) {
        binding.action1.visibility = if (resend || openLocation) View.VISIBLE else View.GONE
        if (resend) {
            binding.action1.setImageResource(R.drawable.ic_refresh)
            binding.action1.contentDescription = binding.root.context.getString(R.string.action_resend)
        } else {
            binding.action1.setImageResource(R.drawable.ic_folder)
            binding.action1.contentDescription = binding.root.context.getString(R.string.action_open_location)
        }
        binding.action2.visibility = if (deleteFile) View.VISIBLE else View.GONE
        binding.action2.setImageResource(R.drawable.ic_delete)
        binding.action2.contentDescription = binding.root.context.getString(R.string.action_delete_file)
        binding.action3.visibility = if (deleteRecord) View.VISIBLE else View.GONE
    }

    /** 动作按钮点击：按当前绑定的 contentDescription 区分「再次发送」与「打开存储位置」。 */
    private fun setupActionClicks(holder: Holder, item: BoardItem) {
        val binding = holder.binding
        val context = binding.root.context
        binding.action1.setOnClickListener {
            if (binding.action1.contentDescription == context.getString(R.string.action_resend)) {
                listener.onResend(item)
            } else {
                listener.onOpenLocation(item)
            }
        }
        binding.action2.setOnClickListener { listener.onDeleteFile(item) }
        binding.action3.setOnClickListener { listener.onDeleteRecord(item) }
    }

    private fun fileBadge(name: String): String {
        val extension = name.substringAfterLast('.', "").uppercase()
        return if (extension.isBlank() || extension.length > 5) {
            "文件"
        } else {
            extension
        }
    }

    class Holder(val binding: ItemBoardBinding) : RecyclerView.ViewHolder(binding.root) {
        var currentRow: BoardRow? = null
        val swipeLayout: SwipeActionLayout get() = binding.root
    }

    companion object {
        const val PAYLOAD_PROGRESS = "payload_progress"

        private val DIFF = object : DiffUtil.ItemCallback<BoardRow>() {
            override fun areItemsTheSame(oldItem: BoardRow, newItem: BoardRow): Boolean = when {
                oldItem is BoardRow.Item && newItem is BoardRow.Item -> oldItem.item.id == newItem.item.id
                oldItem is BoardRow.Task && newItem is BoardRow.Task -> oldItem.task.id == newItem.task.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: BoardRow, newItem: BoardRow): Boolean =
                oldItem == newItem

            override fun getChangePayload(oldItem: BoardRow, newItem: BoardRow): Any? = when {
                oldItem is BoardRow.Item && newItem is BoardRow.Item ->
                    if (oldItem.item.copy(received = 0L) == newItem.item.copy(received = 0L)) PAYLOAD_PROGRESS else null

                oldItem is BoardRow.Task && newItem is BoardRow.Task ->
                    if (oldItem.task.copy(sent = 0L) == newItem.task.copy(sent = 0L)) PAYLOAD_PROGRESS else null

                else -> null
            }
        }
    }
}
