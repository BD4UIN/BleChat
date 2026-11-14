package com.blchat.blechat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息列表适配器：名称与内容分开展示，名称高亮（优先显示别名，无则 ID）。
 * 下方元信息显示时间、信号强度（dBm）；本机消息靠右。
 * @param nameResolver 通过 ID 查询显示名称，无则返回 null
 */
class MessageAdapter(
    private val nameResolver: (String) -> String?,
    private val onLongClick: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatMessage, MessageAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val bubble: LinearLayout = root.findViewById(R.id.bubble)
        val tvName: TextView = root.findViewById(R.id.tvName)
        val tvContent: TextView = root.findViewById(R.id.tvContent)
        val tvMeta: TextView = root.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val ctx = holder.root.context

        val name = nameResolver(msg.id)
        val display = if (name.isNullOrBlank()) msg.id else name

        // 名称高亮（独立展示）
        holder.tvName.text = display
        holder.tvName.setTextColor(
            ContextCompat.getColor(ctx, if (msg.isSelf) R.color.name_self else R.color.name_other)
        )

        // 内容独立展示
        holder.tvContent.text = msg.content

        val time = timeFmt.format(Date(msg.timestamp))
        holder.tvMeta.text = if (msg.isSelf) {
            "$time · 本机广播"
        } else {
            val idTag = if (name.isNullOrBlank()) "" else "${msg.id} · "
            "$idTag$time · ${msg.rssi} dBm"
        }

        val lp = holder.bubble.layoutParams as LinearLayout.LayoutParams
        if (msg.isSelf) {
            holder.bubble.setBackgroundResource(R.drawable.bg_message_self)
            lp.gravity = Gravity.END
        } else {
            holder.bubble.setBackgroundResource(R.drawable.bg_message)
            lp.gravity = Gravity.START
        }
        holder.bubble.layoutParams = lp

        holder.root.setOnLongClickListener {
            onLongClick?.invoke(msg)
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem.uid == newItem.uid

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem == newItem
        }
    }
}
