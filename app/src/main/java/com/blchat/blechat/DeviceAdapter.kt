package com.blchat.blechat

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 附近设备信号列表适配器：每台设备一个芯片，优先显示名称（无则 ID）+ 实时 dBm。
 * @param nameResolver 通过 ID 查询显示名称，无则返回 null
 */
class DeviceAdapter(
    private val nameResolver: (String) -> String?,
    private val onLongClick: ((DeviceSignal) -> Unit)? = null
) : ListAdapter<DeviceSignal, DeviceAdapter.VH>(DIFF) {

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val dot: View = root.findViewById(R.id.dot)
        val tvId: TextView = root.findViewById(R.id.tvDeviceId)
        val tvRssi: TextView = root.findViewById(R.id.tvDeviceRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = getItem(position)
        val name = nameResolver(d.id)
        val display = if (name.isNullOrBlank()) d.id else name
        holder.tvId.text = display

        // 若用了别名，附上原始 ID
        val idTag = if (name.isNullOrBlank()) "" else " · ${d.id}"
        holder.tvRssi.text = "${d.rssi} dBm$idTag"

        val color = when {
            d.rssi >= -50 -> R.color.signal_good
            d.rssi >= -70 -> R.color.signal_mid
            else -> R.color.signal_bad
        }
        holder.dot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(holder.root.context, color)
        )

        holder.root.setOnLongClickListener {
            onLongClick?.invoke(d)
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DeviceSignal>() {
            override fun areItemsTheSame(oldItem: DeviceSignal, newItem: DeviceSignal) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DeviceSignal, newItem: DeviceSignal) =
                oldItem.rssi == newItem.rssi
        }
    }
}
