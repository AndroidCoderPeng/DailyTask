# WebRTC + MQTT + SDP 点对点通信方案

## 1. 概述

本方案实现两个 Android 设备之间**不依赖自建服务器**的点对点通信，用于传输文字与小于 200KB 的小图片。

### 1.1 核心原则

- **数据链路**：WebRTC DataChannel 点对点直连，不经过任何中继服务器
- **信令通道**：公共 MQTT Broker 传递 SDP，两端自动交换，无需人工干预
- **NAT 穿透**：公共 STUN 辅助发现公网地址，不转发业务数据
- **零自建基础设施**：不购买服务器、不租用云、不部署 TURN

### 1.2 适用场景

- 两台 Android 设备位于不同网络（如公司 Wi-Fi 与家庭 Wi-Fi），相距数十公里
- 需要传输文字消息和小图片（<200KB）
- 不希望业务数据经过任何服务器
- 首次手动绑定后，后续连接自动建立与恢复

---

## 2. 整体架构

```
公司手机 A                              家里手机 B
   │  ▲                                     ▲  │
   │  │   ① 通过 MQTT Broker 交换 SDP 信令       │  │
   │  └──────── broker.emqx.io ───────────────┼──┘
   │                                          │
   │  ② STUN 辅助发现公网地址                   │
   │     stun:stun.aliyun.com:3478 等           │
   │                                          │
   └────────── ③ WebRTC DataChannel 直连 ────────┘
              文字 + <200K 图片，点对点传输
```

| 路径 | 类型 | 内容 | 是否经过服务器 |
|---|---|---|---|
| ① MQTT | 信令流 | SDP 文本（几 KB） | 经过公共 MQTT Broker |
| ② STUN | 地址发现 | 查询公网 IP:端口 | 经过公共 STUN |
| ③ DataChannel | 业务数据流 | 文字、图片 | **不经过任何服务器** |

---

## 3. 完整链路分阶段拆解

### 阶段 0：准备与配对初始化

目的：建立两台手机之间的信任关系，并约定一个共享的通信主题。

1. App A 生成随机 `roomId`（16 字节随机值的 Hex 编码，32 字符）和 `preSharedKey`（32 字节随机密钥）：
   ```
   roomId = "a3f1b2c4d5e6f708091a2b3c4d5e6f70"   // 16字节随机 → hex，防扫描枚举
   preSharedKey = "k9f2a1b3..."                   // 32字节（256-bit），用于信令 AES-GCM 加密
   ```
2. App A 显示二维码（或文本），内容为 `roomId + preSharedKey`。
3. 用户在两台设备间通过扫码或手动输入完成绑定。
4. 绑定后，两端本地持久化保存：对方设备标识、`preSharedKey`、`roomId`、自身角色（offerer/answerer，由 `deviceId` 字典序比较决定，见 7.4 节）。

### 阶段 1：通过 MQTT 交换 SDP + ICE 候选，建立 WebRTC 连接

> 采用 **Trickle ICE** 模式：SDP offer/answer 生成后立即发送，ICE 候选逐条通过 MQTT 推送，对端可提前开始连通性检查，缩短建连时间（非 Trickle 模式下候选收集可能耗时 5~15 秒）。

1. App A 和 App B 同时连接公共 MQTT Broker（TLS，端口 8883），订阅主题 `c2c/{roomId}/signal`，QoS = 1。
2. App A 创建 `RTCPeerConnection`，配置 STUN + 可选 TURN 列表。
3. App A 创建 DataChannel，调用 `createOffer()` → `setLocalDescription()`。
4. App A 立即通过 MQTT 发布 offer（不等 ICE 收集完成，retained = true）：
   ```json
   { "type": "offer", "from": "device_A_id", "seq": 1, "sdp": "offer SDP 文本..." }
   ```
5. App A 的 ICE 候选逐条收集，每条通过 MQTT 发布（retained = false）：
   ```json
   { "type": "ice_candidate", "from": "device_A_id", "seq": 2, "candidate": "candidate:842163049 1 udp 1677729535 ..." }
   ```
6. App B 收到 offer，调用 `setRemoteDescription()`，再调用 `createAnswer()` → `setLocalDescription()`。
7. App B 通过 MQTT 发布 answer（retained = true）。
8. App B 的 ICE 候选同样逐条推送。
9. 两端 ICE 进行 NAT 穿透连通性检查，打洞成功后 DataChannel 打开，连接建立。

### 阶段 2：日常数据收发

连接建立后，所有业务数据通过 DataChannel 传输，不再使用 MQTT。

#### 文字传输

直接通过 DataChannel 发送 UTF-8 字符串。

#### 小图片传输（<200KB）

采用改进的分片协议（详见 6.8 / 6.9 节）：

1. 发送方先发 JSON 元信息（前缀 `0x00` magic byte，含 CRC32 校验值）：
   ```json
   { "type": "image", "name": "photo.jpg", "size": 180000, "chunks": 12, "chunkSize": 16384, "crc32": "a1b2c3d4" }
   ```
2. 将图片数据分成 **16KB** 大小的块（根据 `dataChannel.maxMessageSize()` 动态调整），每块前加 4 字节序号。
3. 逐块发送，使用**滑动窗口**（窗口大小 4）做流量控制，避免撑爆发送缓冲区；接收方每收一块回复 ACK。
4. 接收方按序号存入 `SparseArray<ByteArray>`，全部收齐后做 CRC32 校验，通过则保存为文件。

### 阶段 3：网络切换 / 连接断开后的自动重连

这是方案的核心场景：在公司用 Wi-Fi 绑定并连接后，回到家网络变了，原连接失效，但人不在现场无法手动交换 SDP。此时 MQTT 自动信令发挥作用。

> 采用 **ICE Restart** 而非销毁重建 `RTCPeerConnection`：在已有连接上重启 ICE，保持 DataChannel 不重建，开销更小、重连更平滑。

1. **检测网络变化 / 连接断开**
   - 通过 `ConnectivityManager` 监听 Wi-Fi 或蜂窝网络切换
   - 或 WebRTC `PeerConnection.Observer` 回调 `onIceConnectionChange(DISCONNECTED / FAILED)`
   - 或应用层心跳超时（30 秒无 pong，见 6.10 节）

2. **防抖处理**
   - 网络切换时 `ConnectivityManager` 可能短时间内触发多次回调，导致频繁创建连接
   - 设置 **3 秒防抖窗口**，窗口内多次回调只触发一次重连

3. **触发 ICE Restart**（仅 offerer 端发起，见 7.4 角色约定）
   - 在已有 `RTCPeerConnection` 上设置 `iceRestart` 约束，重新 `createOffer()`
   - 生成包含新网络地址的 ICE 候选，逐条通过 MQTT 推送
   - 通过 MQTT 发布 `reconnect_offer`（retained = true）：
   ```json
   { "type": "reconnect_offer", "from": "device_A_id", "seq": 3, "sdp": "ICE Restart 的新 offer SDP..." }
   ```

4. **对端收到后自动应答**
   - App B 仍订阅同一 MQTT 主题，收到 `reconnect_offer`
   - B 自动 `setRemoteDescription()` → `createAnswer()` → 发回 `reconnect_answer`
   - 同样接收新的 ICE 候选

5. **重建连接**
   - A 设置 B 的 answer，两端重新完成 ICE 打洞
   - DataChannel 保持或快速恢复，App 回到"已连接"状态，用户无感知

```
公司 WiFi 连接中 ── 回到家切 WiFi/4G
        │
        ├─ 连接断开，检测网络变化
        │
        ├─ 防抖 3 秒 → ICE Restart 生成新候选
        │
        ├─ 通过 MQTT 自动发送新 SDP + ICE 候选
        │
        └─ 对端自动应答 → 重新建立 P2P 连接
```

### 阶段 4：失败处理

如果打洞失败（如某一端在对称型 NAT 后面），且没有 TURN 中继，重连失败。此时：

- 明确提示："当前网络环境不支持直连，请切换网络后重试。"
- 保留绑定关系，不删除 `roomId` 和共享密钥
- 等待网络再次变化时，自动重试

---

## 4. MQTT 信令协议设计

### 4.1 主题设计

```
c2c/{roomId}/signal
```

两端订阅同一主题，通过消息体中的 `from` 字段过滤自发消息。

> **自发消息过滤**：两端订阅同一主题会收到自己发的消息，必须通过 `from` 字段与本地 `deviceId` 比较来丢弃。

### 4.2 消息格式

```json
{
  "type": "offer | answer | reconnect_offer | reconnect_answer | ice_candidate | ping | pong | bye",
  "from": "device_id",
  "seq": 1,
  "sdp": "...",
  "candidate": "...",
  "ts": 1730123456789
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | string | 消息类型 |
| `from` | string | 发送方 deviceId，用于过滤自发消息 |
| `seq` | int | 自增序列号，用于去重与顺序判断 |
| `sdp` | string? | SDP 文本（仅 offer/answer 类消息） |
| `candidate` | string? | ICE 候选文本（仅 ice_candidate 消息） |
| `ts` | long? | 时间戳（ping/pong 用） |

### 4.3 消息类型

| type | 方向 | QoS | retained | 说明 |
|---|---|---|---|---|
| `offer` | A → B | 1 | true | 初始连接时的 offer |
| `answer` | B → A | 1 | true | 初始连接时的 answer |
| `reconnect_offer` | offerer → answerer | 1 | true | 网络变化后 ICE Restart 的新 offer |
| `reconnect_answer` | answerer → offerer | 1 | true | 对 reconnect_offer 的应答 |
| `ice_candidate` | 双向 | 1 | false | Trickle ICE 逐条候选 |
| `ping` | 双向 | 0 | false | MQTT 链路存活检测 |
| `pong` | 双向 | 0 | false | 对 ping 的应答 |
| `bye` | 任一端 | 1 | false | 主动断开前通知对方 |

> **QoS 策略**：SDP 和 ICE 候选必须使用 **QoS 1**（至少一次送达），丢失会导致连接失败。ping/pong 用 QoS 0 即可。

### 4.4 消息顺序与去重

- 每条消息带自增 `seq`，接收方记录最后处理的 `seq`
- 防止重复处理或旧消息覆盖新消息
- 收到旧 `seq` 的消息直接丢弃
- ICE 候选允许乱序到达，按 `candidate.sdpMLineIndex` 归类即可

### 4.5 MQTT LWT（遗嘱消息）

配置 Last Will and Testament，当一端异常断开 MQTT（App 崩溃、网络完全中断）时，对端能更快感知：

```kotlin
val willPayload = """{"type":"bye","from":"$deviceId","reason":"offline"}""".toByteArray()
client.setWill("c2c/$roomId/signal", willPayload, 1, false)
```

### 4.6 Retained 消息策略

| 消息类型 | retained | 原因 |
|---|---|---|
| offer / answer / reconnect_* | true | 对端可能未上线，retained 确保上线后收到最后一条 |
| ice_candidate | false | 候选时效性强，旧候选无意义，retained 只保留最后一条会丢失前面的 |
| ping / pong / bye | false | 无需 retained |

---

## 5. Android 端状态机

```
主流程：  IDLE → BOUND → CONNECTING → CONNECTED
断连流：  CONNECTED → DISCONNECTED → RECONNECTING → CONNECTED
失败流：  CONNECTING / RECONNECTING → FAILED → BOUND（保留绑定，等待重试）
```

| 状态 | 说明 |
|---|---|
| `IDLE` | 未绑定设备 |
| `BOUND` | 已通过二维码/文本绑定，连接未建立 |
| `CONNECTING` | 正在交换 SDP + ICE 候选 |
| `CONNECTED` | DataChannel 打开，可以传数据，心跳运行中 |
| `DISCONNECTED` | 连接意外断开（ICE DISCONNECTED 或心跳超时），但非永久失败 |
| `RECONNECTING` | 正在通过 MQTT 交换新 SDP + ICE 候选（ICE Restart） |
| `FAILED` | 打洞失败或超时，提示用户，保留绑定关系 |

### 核心迁移逻辑

- 网络变化 / 心跳超时 → `CONNECTED` → `DISCONNECTED` →（防抖 3 秒）→ `RECONNECTING` → `CONNECTED`
- 应用层心跳超时（30 秒无 pong）→ `CONNECTED` → `DISCONNECTED` → `RECONNECTING`
- MQTT 断开 → 重连 MQTT，WebRTC 连接不受影响（信令断 ≠ 数据断）
- 收到新的 `reconnect_offer` → 自动生成 answer → 尝试连接
- 收到 `bye`（LWT 或主动）→ `CONNECTED` → `BOUND`（等待对端重新上线）
- `FAILED` 不清空绑定关系 → 回到 `BOUND`，等待网络变化时自动重试

---

## 6. 实现要点

### 6.1 WebRTC 依赖

> 官方 Maven 工件 `org.webrtc:google-webrtc` 自 2021 年起停止维护，不再包含安全补丁。改用社区持续维护的 fork（API 与官方包兼容）：

```gradle
implementation 'io.getstream:stream-webrtc-android:1.3.8'
```

> 来源：Stream WebRTC Android Build（github.com/getStream/webrtc-android），持续跟随 WebRTC 上游主线更新。

### 6.2 STUN / TURN 配置

```kotlin
val iceServers = listOf(
    PeerConnection.IceServer("stun:stun.aliyun.com:3478"),
    PeerConnection.IceServer("stun:stun.cloud.tencent.com:3478"),
    PeerConnection.IceServer("stun:stun.cloudflare.com:3478"),
    PeerConnection.IceServer("stun:stun.l.google.com:19302"),
    // 可选：公共 TURN 兜底（打洞失败时中继流量，见 7.2 节）
    PeerConnection.IceServer("turn:openrelay.metered.ca:80?username=openrelayproject&credential=openrelayproject"),
    PeerConnection.IceServer("turn:openrelay.metered.ca:443?transport=tcp&username=openrelayproject&credential=openrelayproject")
)

val config = PeerConnection.RTCConfiguration(iceServers).apply {
    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    enableIceRestart = true   // 6.7 节 ICE Restart 重连必需
}
```

> **公共 TURN**：Open Relay 提供免费公共 TURN，不保证 SLA，仅作打洞失败时的兜底。TCP 443 变体用于防火墙只放行 TCP 的企业网络。如需高可靠，建议自建 TURN（如 coturn）。

### 6.3 公共 MQTT Broker

| Broker | 地址 | 端口 | 说明 |
|---|---|---|---|
| EMQX 公共 | `broker.emqx.io` | 8883 (TLS) | 免费，稳定运行多年，推荐 |
| Mosquitto 测试 | `test.mosquitto.org` | 8883 (TLS) | 免费，偶有波动 |
| HiveMQ 公共 | `broker.hivemq.com` | 8883 (TLS) | 可用 |

> **必须使用 TLS（8883 端口）**：明文 1883 端口存在信令被截获/篡改的风险，与 7.5 节安全设计不符。

### 6.4 MQTT 连接与订阅

```kotlin
val client = MqttAndroidClient(
    context,
    "ssl://broker.emqx.io:8883",   // TLS 端口，防中间人
    "client_$deviceId",
    MemoryPersistence()
)

// LWT 遗嘱消息：异常断开时通知对端
val willPayload = """{"type":"bye","from":"$deviceId","reason":"offline"}""".toByteArray()
client.setWill("c2c/$roomId/signal", willPayload, 1, false)

val options = MqttConnectOptions().apply {
    isCleanSession = true
    connectionTimeout = 30
    keepAliveInterval = 30
    isAutomaticReconnect = true    // 自动重连
    maxReconnectInterval = 30
}

client.connect(options, null, object : IMqttActionListener {
    override fun onSuccess(asyncActionToken: IMqttToken?) {
        client.subscribe("c2c/$roomId/signal", 1)  // QoS = 1
    }
    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
        // 切换备用 Broker 重试
    }
})

client.setCallback(object : MqttCallbackExtended {
    override fun messageArrived(topic: String, message: MqttMessage) {
        val json = JSONObject(String(message.payload))
        // 过滤自发消息
        if (json.optString("from") == deviceId) return

        when (json.getString("type")) {
            "offer"            -> handleOffer(json.getString("sdp"))
            "answer"           -> handleAnswer(json.getString("sdp"))
            "reconnect_offer"  -> handleReconnectOffer(json.getString("sdp"))
            "reconnect_answer" -> handleReconnectAnswer(json.getString("sdp"))
            "ice_candidate"    -> handleIceCandidate(json.getString("candidate"))
            "bye"              -> handleBye()
            "ping"             -> sendSignaling("pong", extra = mapOf("ts" to json.optLong("ts")))
        }
    }
    // ... connectComplete / deliveryComplete / connectionLost
})
```

### 6.5 发送信令消息

```kotlin
private var seq = 0

fun sendSignaling(
    type: String,
    sdp: String? = null,
    candidate: String? = null,
    retained: Boolean = false,
    extra: Map<String, Any> = emptyMap()
) {
    val msg = JSONObject()
        .put("type", type)
        .put("from", deviceId)     // 用于对端过滤自发消息
        .put("seq", seq++)
    sdp?.let { msg.put("sdp", it) }
    candidate?.let { msg.put("candidate", it) }
    extra.forEach { (k, v) -> msg.put(k, v) }

    val mqttMsg = MqttMessage(msg.toString().toByteArray()).apply {
        this.qos = 1               // QoS 1：至少一次送达，信令丢失会导致连接失败
        this.retained = retained   // offer/answer retained，ICE 候选不 retained
    }
    client.publish("c2c/$roomId/signal", mqttMsg)
}
```

### 6.6 Trickle ICE：逐条发送候选

```kotlin
override fun onIceCandidate(candidate: IceCandidate?) {
    if (candidate != null) {
        // 逐条发送，不等收集完成，对端可提前开始连通性检查
        val candidateStr = "candidate:${candidate.sdp} ${candidate.sdpMLineIndex} ${candidate.sdpMid}"
        sendSignaling("ice_candidate", candidate = candidateStr, retained = false)
    } else {
        // candidate == null 表示收集完成（可用于 UI 更新）
        Log.d(TAG, "ICE gathering complete")
    }
}
```

### 6.7 ICE Restart 重连与防抖

```kotlin
private val reconnectDebounce = Runnable { startReconnect() }

fun onNetworkChanged() {
    // 防抖：3 秒内多次网络回调只触发一次重连
    handler.removeCallbacks(reconnectDebounce)
    handler.postDelayed(reconnectDebounce, 3_000)
}

fun startReconnect() {
    if (state != CONNECTED && state != DISCONNECTED) return
    if (!isOfferer) return  // 仅 offerer 端发起重连（见 7.4 角色约定）

    state = RECONNECTING

    // ICE Restart：在已有 PeerConnection 上重启 ICE，不销毁重建
    val constraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("iceRestart", "true"))
    }
    peerConnection.createOffer(offerObserver, constraints)
    // onIceCandidate 回调会自动逐条发送新候选（见 6.6 节）
}

override fun onCreateOfferSuccess(sdp: SessionDescription) {
    peerConnection.setLocalDescription(sdpObserver, sdp)
    val type = if (state == RECONNECTING) "reconnect_offer" else "offer"
    sendSignaling(type, sdp = sdp.description, retained = true)
}

// 网络变化监听
val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        if (state == CONNECTED || state == DISCONNECTED || state == RECONNECTING) {
            onNetworkChanged()  // 经防抖后触发 startReconnect()
        }
    }
    override fun onLost(network: Network) {
        if (state == CONNECTED) {
            state = DISCONNECTED
            onNetworkChanged()
        }
    }
}
```

> **answerer 端收到 reconnect_offer 时**：如果自己也在尝试做 offer，立即放弃自己的 offer，转为 answerer 应答（详见 7.4 节）。

### 6.8 图片分片发送（改进版）

```kotlin
fun sendImage(filePath: String) {
    val fileBytes = File(filePath).readBytes()
    // 动态确定分片大小：maxMessageSize - 4（序号 header），上限 16KB
    // WebRTC SCTP 层 maxMessageSize 通常 16KB~256KB，硬编码 64KB 可能超限
    val maxPayload = dataChannel.maxMessageSize().let { if (it > 0) it.toInt() else 16 * 1024 }
    val chunkSize = minOf(16 * 1024, maxPayload - 4).coerceAtLeast(1024)
    val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
    val crc = CRC32().apply { update(fileBytes) }.value

    // 1. 发送元信息：0x00 magic byte + JSON（含 CRC32 与 chunkSize）
    val meta = JSONObject()
        .put("type", "image")
        .put("name", File(filePath).name)
        .put("size", fileBytes.size)
        .put("chunks", totalChunks)
        .put("chunkSize", chunkSize)
        .put("crc32", crc.toString(16))
        .toString()
        .toByteArray()
    dataChannel.send(ByteBuffer.wrap(byteArrayOf(0x00) + meta))

    // 2. 滑动窗口发送：窗口大小 4，每发 4 块等待对端 ACK，避免撑爆发送缓冲区
    val windowSize = 4
    var offset = 0
    var chunkSeq = 0
    var unacked = 0

    while (offset < fileBytes.size) {
        if (unacked >= windowSize) {
            ackLatch.await()  // 阻塞等待 ACK（建议在协程中调用，用 suspendCancellableCoroutine）
        }

        val end = minOf(offset + chunkSize, fileBytes.size)
        val chunk = fileBytes.copyOfRange(offset, end)
        val header = ByteBuffer.allocate(4).putInt(chunkSeq).array()
        dataChannel.send(ByteBuffer.wrap(header + chunk))

        offset = end
        chunkSeq++
        unacked++
    }
}
```

### 6.9 图片分片接收（改进版）

```kotlin
dataChannel.registerObserver(object : DataChannel.Observer {
    private val chunks = SparseArray<ByteArray>()  // 按序号存储，支持乱序/去重
    private var totalChunks = 0
    private var receivedChunks = 0
    private var expectedCrc: String? = null
    private var fileName: String? = null

    override fun onMessage(buf: ByteBuffer) {
        val data = ByteArray(buf.remaining())
        buf.get(data)

        // magic byte 区分消息类型：0x00 = 元信息，0x01 = ACK，其余 = 数据块
        // 避免旧版"首条消息一定是 JSON"的竞态误判
        when {
            data.isNotEmpty() && data[0] == 0x00.toByte() -> {
                val meta = JSONObject(String(data, 1, data.size - 1))
                totalChunks = meta.getInt("chunks")
                expectedCrc = meta.getString("crc32")
                fileName = meta.getString("name")
            }
            data.isNotEmpty() && data[0] == 0x01.toByte() -> {
                // 对端 ACK：驱动发送端滑动窗口（unackedCount/ackLatch 为发送侧状态）
                unackedCount.decrementAndGet()
                ackLatch.countDown()
            }
            totalChunks > 0 -> {
                // 数据块：前 4 字节为序号
                val seq = ByteBuffer.wrap(data, 0, 4).int
                if (chunks.indexOfKey(seq) < 0) {
                    chunks.put(seq, data)
                    receivedChunks++
                }
                // 重复块只回 ACK 不重复计数，驱动发送端窗口滑动
                sendAck(seq)
            }
        }

        if (receivedChunks == totalChunks && totalChunks > 0) {
            // 按序号重组
            val buffer = ByteArrayOutputStream()
            for (i in 0 until totalChunks) {
                val chunk = chunks.get(i) ?: return  // 缺块，等待重传
                buffer.write(chunk, 4, chunk.size - 4)  // 跳过 4 字节序号头
            }
            val result = buffer.toByteArray()

            // CRC32 完整性校验
            val actualCrc = CRC32().apply { update(result) }.value.toString(16)
            if (actualCrc == expectedCrc) {
                saveImage(result, fileName!!)
            } else {
                Log.e(TAG, "CRC32 mismatch: expected=$expectedCrc actual=$actualCrc")
                // 请求重传或提示用户
            }
            // 重置状态
            chunks.clear()
            totalChunks = 0
            receivedChunks = 0
        }
    }

    private fun sendAck(seq: Int) {
        val ack = byteArrayOf(0x01) + ByteBuffer.allocate(4).putInt(seq).array()
        dataChannel.send(ByteBuffer.wrap(ack))
    }
})
```

> **改进点**：
> - `0x00/0x01` magic byte 显式区分元信息、ACK、数据块，消除"假设首条消息是 JSON"的竞态缺陷
> - `SparseArray` 按序号存储并去重，替代简单计数（旧版重复块会导致计数错位）
> - 重组后 CRC32 校验，损坏可检测
> - ACK 回传驱动发送端滑动窗口

### 6.10 应用层心跳

WebRTC ICE 有底层 keepalive，但 DataChannel 层面没有应用级心跳。NAT 映射超时可能造成连接"假死"——数据发出去无人接收，而底层却未检测到。连接建立后启动双向心跳：

```kotlin
private var lastPongTs = 0L

private val heartbeatRunnable = object : Runnable {
    override fun run() {
        if (state == CONNECTED) {
            val ping = JSONObject()
                .put("type", "ping")
                .put("ts", System.currentTimeMillis())
                .toString()
            dataChannel.send(ByteBuffer.wrap(ping.toByteArray()))

            // 30 秒（2 个周期）未收到 pong → 判定连接失效
            if (System.currentTimeMillis() - lastPongTs > 30_000) {
                state = DISCONNECTED
                onNetworkChanged()  // 经防抖后触发 ICE Restart 重连（见 6.7 节）
                return
            }
        }
        handler.postDelayed(this, 15_000)
    }
}

// 连接建立后启动心跳
fun onConnected() {
    lastPongTs = System.currentTimeMillis()
    handler.post(heartbeatRunnable)
}

// 收到 pong 时更新时间戳（接收方回 pong 走 6.4 节同一消息通道）
fun onPong(ts: Long) {
    lastPongTs = System.currentTimeMillis()
}
```

> **两路 ping/pong 的分工**：
> - **DataChannel 心跳**（本节）：走 P2P 数据通道，验证端到端链路可用，检测 NAT 映射超时导致的"假死"
> - **MQTT ping/pong**（4.3 节 + 6.4 节）：走信令通道，QoS 0，用于 answerer 在 7.4 节探测退避场景中确认 offerer 是否在线

---

## 7. 注意事项与风险

### 7.1 公共 MQTT Broker 可用性

- 公共 Broker 是"尽力而为"的公共设施，不提供 SLA
- 建议配置多个 Broker 冗余，自动切换
- 信令量极小，不产生费用

### 7.2 打洞成功率

| 网络组合 | 打洞结果 |
|---|---|
| 家宽 ↔ 家宽（锥型 NAT） | ✅ 成功率高 |
| 家宽 ↔ 公司 Wi-Fi（对称 NAT / 防火墙） | ⚠️ 可能失败，企业防火墙常阻断 UDP |
| 任一方走 4G/5G 蜂窝 | ⚠️ 国内运营商 CGNAT 普遍是对称型（约 60%~70%），常失败 |
| 双方都在 CGNAT 后 | ❌ 几乎必然失败 |

**打洞失败时的处理策略**：

1. 自动尝试公共 TURN 中继兜底（6.2 节已配置）
2. TURN 也失败或不可用 → 明确提示用户："当前网络环境不支持直连，请切换网络后重试。"
3. **保留绑定关系**，不删除 `roomId` 和 `preSharedKey`，等待网络变化时自动重试
4. UI 上展示当前网络类型的预期成功率，管理用户预期

### 7.3 Android 后台保活

- 传输期间启动前台服务，防止锁屏断连
- 引导用户关闭电池优化白名单
- 国产 ROM 需引导用户加入自启动白名单

### 7.4 双方同时发起重连

通过**固定角色约定 + 探测退避 + 冲突回退**三层机制避免竞态：

- **固定角色**：`deviceId` 字典序较小的一方始终为 offerer，另一方为 answerer。初始连接与所有重连场景均由 offerer 发起，answerer 只被动应答。
- **探测退避**：如果 answerer 在 5 秒内未收到 offerer 的 `reconnect_offer`（可能 offerer 进程已死），可通过 MQTT `ping` 探测；确认对端无响应后，answerer 可突破角色约定，自行发起一次重连。
- **冲突回退**：如果 answerer 已误发了 offer，随后收到 offerer 的 `reconnect_offer` 时，立即放弃自己的 offer、销毁本地 offer 状态，转为 answerer 应答，避免 glare（双方同时 offer 导致的 SDP 冲突）。

### 7.5 安全措施

| 措施 | 说明 |
|---|---|
| 随机 roomId | 16 字节随机值 → 32 字符 Hex，熵足够，防扫描枚举 |
| 随机 preSharedKey | 32 字节（256-bit）随机密钥 |
| SDP 加密 | AES-GCM 加密 SDP 与 ICE 候选，密钥为 `preSharedKey` |
| Nonce 管理 | nonce 由 `seq`（2 字节）+ 随机前缀（10 字节）组成，保证不重复 |
| 完整性校验 | HMAC-SHA256 对 SDP 签名，防止被篡改 |
| TLS | MQTT 使用 8883 端口 TLS 连接，防中间人 |
| 前向保密 | preSharedKey 仅用于信令加密，DataChannel 自身有 DTLS 加密 |

> **Nonce 构造**：AES-GCM 的 12 字节 nonce = `seq`（2 字节 Big-Endian）+ 10 字节随机前缀。每次建连重新生成 10 字节随机前缀，`seq` 每条消息递增，保证 nonce 全局唯一（AES-GCM 在同一密钥下复用 nonce 会彻底破坏加密安全性）。

### 7.6 DataChannel 消息大小限制

- WebRTC SCTP 层默认 `maxMessageSize` 通常为 **16KB~256KB**，取决于两端实现与 SDP 协商结果
- 发送前必须检查：`val maxSize = dataChannel.maxMessageSize()`
- 图片分片大小动态计算：`minOf(16 * 1024, maxSize - 4)`，不要硬编码 64KB
- 超限消息会被底层静默丢弃或直接异常，且无重传提示

### 7.7 SDP 体积

完整 SDP 一般 1~5 KB（Trickle 模式下不含候选，更小），远小于公共 Broker 的消息上限。不要将图片 Base64 编码后放入 SDP 或 MQTT 消息。

---

## 8. 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

<!-- 通知（Android 13+ 需运行时申请） -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- 前台服务（Android 14+ 必须声明具体类型） -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- 电池优化白名单 -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- Doze 模式下精确闹钟（定时重连兜底，配合 WorkManager / AlarmManager） -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

> **前台服务配置**：`FOREGROUND_SERVICE_DATA_SYNC` 声明后，启动前台服务时还需在 `foregroundServiceType` 中指定 `dataSync`，否则 Android 14+ 会抛异常。

---

## 9. 方案总结

| 维度 | 方案 |
|---|---|
| 数据传输 | WebRTC DataChannel，点对点直连（DTLS 加密） |
| 信令交换 | 公共 MQTT Broker（TLS 8883 + QoS 1）自动传递 SDP + ICE 候选 |
| ICE 模式 | Trickle ICE，候选逐条推送，缩短建连时间 |
| NAT 穿透 | 公共 STUN 发现公网地址；可选公共 TURN 兜底中继 |
| 首次绑定 | 二维码/文本手动交换，建立信任 |
| 后续重连 | ICE Restart + MQTT 自动推送新 SDP，3 秒防抖，无需人工干预 |
| 心跳保活 | DataChannel 应用层 15 秒心跳，30 秒超时判定失效 |
| 信令安全 | preSharedKey + AES-GCM + HMAC 签名 + TLS |
| 自有服务器 | **无** |
| 数据经第三方 | **否，始终 P2P 直传**（TURN 兜底仅在打洞失败时介入） |
