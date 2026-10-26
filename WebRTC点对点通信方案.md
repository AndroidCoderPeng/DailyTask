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

1. App A 生成随机 `roomId` 和 `preSharedKey`：
   ```
   roomId = "abc123xyz"
   preSharedKey = "k9f2..."
   ```
2. App A 显示二维码（或文本），内容为 `roomId + preSharedKey`。
3. 用户在两台设备间通过扫码或手动输入完成绑定。
4. 绑定后，两端本地持久化保存：对方设备标识、`preSharedKey`、`roomId`。

### 阶段 1：通过 MQTT 交换初始 SDP，建立 WebRTC 连接

1. App A 和 App B 同时连接公共 MQTT Broker，订阅主题 `c2c/{roomId}/signal`。
2. App A 创建 `RTCPeerConnection`，配置 STUN 列表：
   ```json
   ["stun:stun.aliyun.com:3478", "stun:stun.cloud.tencent.com:3478"]
   ```
3. App A 创建 DataChannel，调用 `createOffer()`。
4. 等待 ICE 候选收集完成（非 Trickle 模式，一次性取得完整 SDP）。
5. App A 通过 MQTT 发布 offer：
   ```json
   { "type": "offer", "seq": 1, "sdp": "完整的 SDP 文本..." }
   ```
6. App B 收到 offer，调用 `setRemoteDescription()`，再调用 `createAnswer()`。
7. App B 收集完 ICE 候选后，通过 MQTT 发布 answer：
   ```json
   { "type": "answer", "seq": 2, "sdp": "完整的 answer SDP 文本..." }
   ```
8. App A 收到 answer，调用 `setRemoteDescription()`。
9. 两端 ICE 进行 NAT 穿透，打洞成功后 DataChannel 打开，连接建立。

### 阶段 2：日常数据收发

连接建立后，所有业务数据通过 DataChannel 传输，不再使用 MQTT。

#### 文字传输

直接通过 DataChannel 发送 UTF-8 字符串。

#### 小图片传输（<200KB）

1. 发送方先发 JSON 元信息：
   ```json
   { "type": "image", "name": "photo.jpg", "size": 180000, "chunks": 3 }
   ```
2. 将图片数据分成 64KB 大小的块，每块前加 4 字节序号。
3. 逐块发送，接收方按序号重组，全部收齐后保存为文件。

### 阶段 3：网络切换 / 连接断开后的自动重连

这是方案的核心场景：在公司用 Wi-Fi 绑定并连接后，回到家网络变了，原连接失效，但人不在现场无法手动交换 SDP。此时 MQTT 自动信令发挥作用。

1. **检测网络变化 / 连接断开**
   - 通过 `ConnectivityManager` 监听 Wi-Fi 或蜂窝网络切换
   - 或 WebRTC `PeerConnection.Observer` 回调 `onIceConnectionChange(DISCONNECTED / FAILED)`

2. **触发生成新 SDP**
   - App 检测到旧连接不可用后，自动创建新的 `RTCPeerConnection`
   - 等待新的 ICE 候选收集完成，生成包含新 IP/端口信息的 offer SDP

3. **通过 MQTT 自动推送新 SDP**
   ```json
   { "type": "reconnect_offer", "seq": 3, "sdp": "新的完整 SDP 文本..." }
   ```

4. **对端收到后自动应答**
   - App B 仍订阅同一 MQTT 主题，收到新 SDP
   - B 自动生成新的 answer，发回 A

5. **重建连接**
   - A 设置 B 的 answer，两端重新完成 ICE 打洞
   - DataChannel 重新打开，App 回到"已连接"状态，用户无感知

```
公司 WiFi 连接中 ── 回到家切 WiFi/4G
        │
        ├─ 连接断开，检测网络变化
        │
        ├─ 通过 MQTT 自动发送新 SDP
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

两端订阅同一主题，实现双向信令交换。

### 4.2 消息格式

```json
{
  "type": "offer | answer | reconnect_offer | reconnect_answer | ping | bye",
  "seq": 1,
  "sdp": "..."
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | string | 消息类型 |
| `seq` | int | 自增序列号，用于去重与顺序判断 |
| `sdp` | string | SDP 文本（offer 或 answer） |

### 4.3 消息类型

| type | 方向 | 说明 |
|---|---|---|
| `offer` | A → B | 初始连接时的 offer |
| `answer` | B → A | 初始连接时的 answer |
| `reconnect_offer` | 任一端 → 另一端 | 网络变化后新的 offer |
| `reconnect_answer` | 另一端 → 任一端 | 对新的 offer 应答 |
| `ping` | 双向 | 可选，检测 MQTT 链路存活 |
| `bye` | 任一端 | 主动断开前通知对方 |

### 4.4 消息顺序与去重

- 每条消息带自增 `seq`，接收方记录最后处理的 `seq`
- 防止重复处理或旧消息覆盖新消息
- 收到旧 `seq` 的消息直接丢弃

---

## 5. Android 端状态机

```
IDLE → BOUND → CONNECTING → CONNECTED
                          ↓
                 RECONNECTING → CONNECTED
                          ↓
                        FAILED → IDLE
```

| 状态 | 说明 |
|---|---|
| `IDLE` | 未绑定设备 |
| `BOUND` | 已通过二维码/文本绑定，连接未建立 |
| `CONNECTING` | 正在交换 SDP |
| `CONNECTED` | DataChannel 打开，可以传数据 |
| `RECONNECTING` | 网络变化或连接断开，正在通过 MQTT 交换新 SDP |
| `FAILED` | 打洞失败或超时，提示用户 |

### 核心迁移逻辑

- 网络变化 → `CONNECTED` → `RECONNECTING` → `CONNECTED`
- MQTT 断开 → 重连 MQTT，WebRTC 连接不受影响（信令断 ≠ 数据断）
- 收到新的 `reconnect_offer` → 自动生成 answer → 尝试连接

---

## 6. 实现要点

### 6.1 WebRTC 依赖

```gradle
implementation 'org.webrtc:google-webrtc:1.0.32061'
```

### 6.2 STUN 配置

```kotlin
val iceServers = listOf(
    PeerConnection.IceServer("stun:stun.aliyun.com:3478"),
    PeerConnection.IceServer("stun:stun.cloud.tencent.com:3478"),
    PeerConnection.IceServer("stun:stun.cloudflare.com:3478"),
    PeerConnection.IceServer("stun:stun.l.google.com:19302")
)

val config = PeerConnection.RTCConfiguration(iceServers)
```

### 6.3 公共 MQTT Broker

| Broker | 地址 | 端口 | 说明 |
|---|---|---|---|
| EMQX 公共 | `broker.emqx.io` | 1883 / 8883 (TLS) | 免费，稳定运行多年 |
| Mosquitto 测试 | `test.mosquitto.org` | 1883 / 8883 | 免费，偶有波动 |
| HiveMQ 公共 | `broker.hivemq.com` | 1883 | 可用 |

### 6.4 MQTT 连接与订阅

```kotlin
val client = MqttClient("tcp://broker.emqx.io:1883", "client_$deviceId", MemoryPersistence())
client.connect()
client.subscribe("c2c/$roomId/signal")

client.setCallback(object : MqttCallback {
    override fun messageArrived(topic: String, message: MqttMessage) {
        val json = JSONObject(String(message.payload))
        when (json.getString("type")) {
            "offer" -> handleOffer(json.getString("sdp"))
            "answer" -> handleAnswer(json.getString("sdp"))
            "reconnect_offer" -> handleReconnectOffer(json.getString("sdp"))
            "reconnect_answer" -> handleReconnectAnswer(json.getString("sdp"))
        }
    }
})
```

### 6.5 发送 SDP

```kotlin
fun sendSdp(type: String, sdp: String) {
    val msg = JSONObject()
        .put("type", type)
        .put("seq", seq++)
        .put("sdp", sdp)
    client.publish("c2c/$roomId/signal", MqttMessage(msg.toString().toByteArray()))
}
```

### 6.6 ICE 候选收集完成时机

非 Trickle 模式下，`setLocalDescription()` 后 ICE 候选开始收集，所有候选齐了 SDP 才完整可用：

```kotlin
override fun onIceCandidate(candidate: IceCandidate?) {
    if (candidate == null) {
        // 收集完成，此时可取 localDescription 发出去
        val sdp = peerConnection.localDescription.description
        sendSdp("offer", sdp)
    }
}
```

### 6.7 网络变化监听与重连触发

```kotlin
val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        if (currentState == CONNECTED || currentState == RECONNECTING) {
            startReconnect()
        }
    }
}

fun startReconnect() {
    if (state == RECONNECTING) return
    state = RECONNECTING
    createNewPeerConnection()
    createOfferAndSendViaMqtt()
}
```

### 6.8 图片分片发送

```kotlin
val metaJson = """{"type":"image","name":"photo.jpg","size":180000,"chunks":3}"""
dataChannel.send(ByteBuffer.wrap(metaJson.toByteArray()))

val fileBytes = File(...).readBytes()
val chunkSize = 64 * 1024
var offset = 0
var seq = 0
while (offset < fileBytes.size) {
    val end = minOf(offset + chunkSize, fileBytes.size)
    val chunk = fileBytes.copyOfRange(offset, end)
    val header = ByteBuffer.allocate(4).putInt(seq).array()
    dataChannel.send(ByteBuffer.wrap(header + chunk))
    offset = end
    seq++
}
```

### 6.9 图片分片接收

```kotlin
dataChannel.registerObserver(object : DataChannel.Observer {
    private val buffer = ByteArrayOutputStream()
    private var totalChunks = 0
    private var receivedChunks = 0

    override fun onMessage(buf: ByteBuffer) {
        val data = ByteArray(buf.remaining())
        buf.get(data)

        if (totalChunks == 0) {
            val text = String(data)
            if (text.startsWith("{")) {
                totalChunks = JSONObject(text).getInt("chunks")
            }
            return
        }

        val seq = ByteBuffer.wrap(data, 0, 4).int
        val chunk = data.copyOfRange(4, data.size)
        buffer.write(chunk)
        receivedChunks++

        if (receivedChunks == totalChunks) {
            saveImage(buffer.toByteArray())
        }
    }
})
```

---

## 7. 注意事项与风险

### 7.1 公共 MQTT Broker 可用性

- 公共 Broker 是"尽力而为"的公共设施，不提供 SLA
- 建议配置多个 Broker 冗余，自动切换
- 信令量极小，不产生费用

### 7.2 打洞成功率

| 网络组合 | 打洞结果 |
|---|---|
| 家宽 ↔ 家宽（锥型 NAT） | ✅ 成功 |
| 家宽 ↔ 公司 Wi-Fi（对称 NAT） | ⚠️ 可能失败 |
| 任一方走 4G/5G 蜂窝 | ⚠️ CGNAT 约 40% 对称型，常失败 |

打洞失败时，无 TURN 中继则无法连接，只能提示用户切换网络。

### 7.3 Android 后台保活

- 传输期间启动前台服务，防止锁屏断连
- 引导用户关闭电池优化白名单
- 国产 ROM 需引导用户加入自启动白名单

### 7.4 双方同时发起重连

如果 A 和 B 同时检测到网络变化，可能同时发出 `reconnect_offer`：

- 约定固定角色：`deviceId` 较小的一方总是 offerer，另一方总是 answerer
- 或收到对方 `reconnect_offer` 时，放弃自己的 offer，转为应答方

### 7.5 安全措施

- 使用随机 `roomId`，防扫描
- MQTT 消息中对 SDP 做 AES-GCM 加密，密钥为 `preSharedKey`
- 使用 TLS（8883 端口）连接公共 MQTT，防止信令被中间人截获

### 7.6 SDP 体积

完整非 Trickle SDP 一般 2~5 KB，远小于公共 Broker 的 1MB 上限。不要将图片 Base64 编码后放入 SDP。

---

## 8. 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

---

## 9. 方案总结

| 维度 | 方案 |
|---|---|
| 数据传输 | WebRTC DataChannel，点对点直连 |
| 信令交换 | 公共 MQTT Broker 自动传递 SDP |
| NAT 穿透 | 公共 STUN 发现公网地址 |
| 首次绑定 | 二维码/文本手动交换，建立信任 |
| 后续重连 | MQTT 自动推送新 SDP，无需人工干预 |
| 自有服务器 | **无** |
| 数据经第三方 | **否，始终 P2P 直传** |
