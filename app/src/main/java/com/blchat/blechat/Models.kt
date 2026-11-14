package com.blchat.blechat

/**
 * 一条聊天消息。
 * @param uid 稳定唯一键（用于 DiffUtil 与去重）
 * @param id 发送者 ID
 * @param content 消息内容
 * @param rssi 接收时的信号强度（dBm）；本机消息为 Int.MIN_VALUE
 * @param timestamp 接收/发送时间（毫秒）
 * @param isSelf 是否为本机发出的消息
 */
data class ChatMessage(
    val uid: String,
    val id: String,
    val content: String,
    val rssi: Int,
    val timestamp: Long,
    val isSelf: Boolean
)

/**
 * 附近一台设备的实时信号。
 * @param id 设备 ID
 * @param rssi 最新信号强度（dBm）
 * @param lastSeen 最近一次收到的扫描时间（用于过期剔除）
 */
data class DeviceSignal(
    val id: String,
    val rssi: Int,
    val lastSeen: Long
)
