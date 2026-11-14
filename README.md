# 蓝牙广播聊天（BLE Broadcast Chat）

基于 **BLE 广播（无连接）** 的局域网聊天应用：无需配对、无需服务器，设备间通过 Bluetooth Low Energy 的 manufacturer-specific data 直接广播「ID + 消息」，附近所有设备实时接收并显示信号强度（RSSI，dBm）。

## 功能特性

- **常驻广播 + 常驻扫描**：App 启动即开始持续广播在场信标与持续扫描，附近设备实时互相可见并刷新信号强度。
- **多设备信号列表**：顶部实时显示每台附近设备的 `ID` 与 `dBm`，按信号强弱排序，15 秒无广播自动剔除。
- **长消息分片合并**：消息按单包容量切分，广播端轮转广播各分片，接收端按 `ID + 序列号` 缓存并自动合并成完整消息。
- **名称映射表**：支持自定义本机名称与设备别名，名称随广播自动绑定、优先显示（高亮）；可一键「同步映射表」广播给所有接收端。
- **前台服务常驻通知**：通知栏常驻「正在接收」，收到新消息弹通知；前台服务类型 `connectedDevice`，扫描声明 `neverForLocation`。
- **深色模式**：随系统自动切换（`values-night`）。
- **版本校验**：广播头携带协议版本，收到更高版本消息时提示升级。
- **刘海/挖孔适配**：edge-to-edge + `WindowInsets`（含 `ime()` 输入法）安全区适配，输入框始终浮在键盘之上。

## 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin 1.9.24 |
| 构建 | Gradle 8.11.1 + AGP 8.10.1 |
| SDK | compileSdk/targetSdk 36，minSdk 26 |
| UI | 原生 View + Material Components + ViewBinding |
| 架构 | 单例 `BleChatManager` + 前台服务 `BleChatService` + `Application` |

## 权限

- `BLUETOOTH_SCAN`（`neverForLocation`）/ `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`（旧版扫描）
- `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE(_CONNECTED_DEVICE)`

## 广播协议

```
[魔数 0xBC][类型 1B][版本 1B][ID 4B][序列号 2B][负载]
```

- **类型 0（聊天/在场）**：`[名称长度][名称][总分片数][分片序号][分片内容]`，总分片数 0 表示在场信标。
- **类型 1（映射表同步）**：`[条目数][4B ID][名称长度][名称]…`

厂商 ID 使用 `0xFFFF`（Bluetooth SIG 保留测试用途）。

## 构建

```bash
# 需 Android SDK（compileSdk 36）+ JDK 17
export ANDROID_HOME=/path/to/sdk
./gradlew assembleRelease   # 或 assembleDebug
```

> 发布包使用本地 `blechat.keystore` 签名（已 gitignore，请自备密钥）。

## 目录结构

```
app/src/main/java/com/blchat/blechat/
├── BleChatApp.kt        # Application 单例 + 崩溃日志
├── BleChatManager.kt    # BLE 广播/扫描/分片/去重/版本核心
├── BleChatService.kt    # 前台服务 + 常驻/消息通知
├── ChatProtocol.kt      # 协议编解码（含分片）
├── NameStore.kt         # ID→名称 映射表
├── MainActivity.kt      # 主界面
├── MessageAdapter.kt    # 消息列表（名称高亮）
├── DeviceAdapter.kt     # 设备信号列表
└── Models.kt            # 数据模型
```

## License

MIT
