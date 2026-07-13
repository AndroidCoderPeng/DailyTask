# 功能线 Review 路线图

项目按功能拆成 9 条线，每条只涉及 2-5 个文件。按"容易出 Bug 的程度 + 重要程度"排序，逐条
Review，比盯着全部代码轻松得多。

每次只看一条线，**只看涉及的 2-5 个文件**，跟着数据流向走一遍：谁发起 → 经过谁 → 谁处理 → 谁响应。

---

## ① 链式任务调度线（最核心，最复杂）

**做什么**：启动 → 算时间 → 倒计时 → 到点打开 APP → `select{超时|打卡}` 竞态 → 推进下一个 → 循环直到全部完成

**涉及文件**：

- `TaskScheduler.kt` — 调度核心，自包含全部逻辑
- `FloatingWindowController.kt` — 超时倒计时 tick 通过 `_timeTick` SharedFlow 更新悬浮窗
- `MainActivity.kt`（部分）— 订阅 `isRunning` / `tipsEvent` / `returnToApp` 三个 Flow 驱动 UI

**架构要点**：

- `startTask()` 启动一个 `while(isActive)` 持久协程：`executeSchedule()` → `waitUntilNextReset()` →
  循环
- `executeSchedule()` 用 `for` 链式执行：阶段1 countdown → 阶段2 `openApplication() + select{...}` →
  阶段3 推进
- `select{}` 竞态：`timeoutJob.onJoin { false }` vs `clockInDeferred.onAwait { true }`，
  `notifyClockIn()` 完成 Deferred
- 倒计时用 `SystemClock.elapsedRealtime()` 自校准，休眠唤醒后剩余时间准确
- 超时路径通过 `_returnToApp` SharedFlow 通知 MainActivity 回到主页

**重点关注**：

| 风险点                                | 描述                                                                                                                    |
|------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `clockInDeferred` 在 `select{}` 内创建 | `notifyClockIn()` 调用时 `clockInDeferred` 为 null 则 `complete()` 是空操作，但重新赋值进入 select 时 Deferred 已完成 → 分支 B 立即返回 true（正确） |
| 超时兜底截屏 `tick <= 5`                 | 最后 5 秒只触发一次（`hasCaptured` 标志），但如果截屏服务挂了，截屏悄无声息地失败                                                                     |

---

## ② 打卡检测 & 远程指令线

**做什么**：监听通知栏 → 识别"打卡成功" → `emitMonitorEvent(ClockInSuccess)` → MainActivity 通过
SharedFlow 收集 → `TaskScheduler.notifyClockIn()`；识别
"执行任务/终止任务/息屏/亮屏/截屏/考勤记录/状态查询"等远程指令

**涉及文件**：

- `NotificationMonitorService.kt` — 全部逻辑（`events: SharedFlow<MonitorEvent>` 对外通信）
- `MainActivity.kt`（`handleMonitorEvent` 部分）— 订阅 `NotificationMonitorService.events`

**通信方式**：

- `NotificationMonitorService.events` SharedFlow → MainActivity（6 种 MonitorEvent）
- `NotificationMonitorService.listenerState` SharedFlow → SettingsActivity（UI 状态）

**重点关注**：

| 风险点                                           | 描述                                                              |
|-----------------------------------------------|-----------------------------------------------------------------|
| `listenerConnected` 不是 `@Volatile`            | `onListenerConnected/Disconnected` 回调和"状态查询"指令可能在不同线程执行，可能读到过期值 |
| `handleRemoteCommand()` 用 `when { }` 链        | 通知内容同时包含多个关键词时会命中第一个，可能不是用户意图                                   |
| 打卡检测依赖通知内容包含"成功"                              | 如果 APP 更新了通知文案，就检测不到了                                           |
| `events` SharedFlow `extraBufferCapacity = 2` | 如果 MainActivity 未收集（被杀死），最多缓存 2 个事件，超出的丢失                       |

---

## ③ 截屏服务线

**做什么**：MediaProjection 授权 → VirtualDisplay + ImageReader → 收到 `CaptureScreen` 事件 → 截图 →
裁剪上半部分 → 检测黑色画面 → 重试 → 保存文件

**涉及文件**：

- `CaptureImageService.kt` — 全部逻辑
- `ProjectionSession.kt` — MediaProjection 生命周期管理
- `MainActivity.kt`（部分）— 遥控截屏时通过 `captureResults` SharedFlow 等待结果
- `SettingsActivity.kt`（部分）— 手动测试截屏

**重点关注**：

| 风险点                                                 | 描述                                                  |
|-----------------------------------------------------|-----------------------------------------------------|
| `onStartCommand` 失败时死循环                             | resultCode = RESULT_CANCELED 返回 START_STICKY，服务反复重启 |
| `isBitmapMostlyBlack()` 采样步长 10 + 阈值 90%            | 如果截图正好是暗色 UI（如暗黑模式聊天界面），可能误判为黑屏                     |
| `ImageReader` 用 `RGBA_8888`                         | 部分 OEM 的 VirtualDisplay 对 RGBA 支持不好，可能返回黑色帧，导致不断重试  |
| `waitForImageAvailable` 的 `withTimeoutOrNull(2000)` | 2 秒超时可能在低端机上不够                                      |

---

## ④ 伪息屏（MaskView）线

**做什么**：显示全屏遮罩 → 隐藏真实界面 → 时钟随机移动模拟熄屏显示 → 手势/音量键切换

**涉及文件**：

- `MaskViewController.kt` — 蒙层控制
- `GestureController.kt` — 滑动手势检测
- `MainActivity.kt`（部分）— 音量键、onNewIntent

**重点关注**：

| 风险点                                                | 描述                                                                   |
|----------------------------------------------------|----------------------------------------------------------------------|
| `clockAnimationRunnable` 递归 postDelayed 30000ms    | 如果 `stopClockAnimation` 和 `startClockAnimation` 竞争，可能创建多个并行 Runnable |
| `GestureController.minFlingDistance = 1000f`       | 1000px 的滑动距离在有些屏幕上太长，可能划不动                                           |
| `MaskViewController` 持有 `insetsController` 是构造时传入的 | Activity 配置变更后可能失效                                                   |
| `showMaskView()` 里 `hideMaskView()` 的动画取消操作        | `currentAnimation?.cancel()` 后立即创建新动画，cancel 回调可能干扰                  |

---

## ⑤ 悬浮窗线

**做什么**：显示悬浮窗 → 拖动 → 倒计时显示 → 内存使用监控 → 超标预警

**涉及文件**：

- `FloatingWindowService.kt` — 悬浮窗主体，订阅 `FloatingWindowController` 的 3 个 SharedFlow
- `FloatingWindowController.kt` — 外部控制接口（`timeTick` / `overtime` / `visibility`）

**谁在驱动悬浮窗**：

- `TaskScheduler.executeSchedule()` 超时倒计时 → `FloatingWindowController.updateTime(tick)`
- `NotificationMonitorService` 遥控"打卡"独立倒计时 → `FloatingWindowController.updateTime()`
- `MaskViewController.showMaskView/hideMaskView` → `FloatingWindowController.hide/show()`
- `TaskConfigActivity` 修改超时时间 → `FloatingWindowController.setOvertime()`
- `MainActivity` 遥控"截屏"倒计时 → `FloatingWindowController.updateTime()`

**重点关注**：

| 风险点                                    | 描述                                                                             |
|----------------------------------------|--------------------------------------------------------------------------------|
| `lateinit binding` + `START_STICKY`    | 服务重启不调 onCreate，binding 未初始化                                                   |
| 内存监控 `delay(1000)` 省电模式下 `60000`       | 省电模式每 60 秒检查一次，内存可能已经爆了才报警                                                     |
| `onDestroy` 里 `cancel()`               | `CoroutineScope by CoroutineScope(Dispatchers.Main)` 的实现，如果 cancel 先于协程完成，可能残留 |
| `windowManager.addView` 在 `onCreate` 中 | 如果 App 在后台时 Service 被 START_STICKY 重启，`onCreate` 可能不被调用                        |
| 多个协程同时 `collect` `timeTick`            | `FloatingWindowService` 里 3 个 launch 各 collect 不同 Flow，互不干扰；但只启动一次 Service     |

---

## ⑥ 任务重置线

**做什么**：每天到设定时间 → 重置任务状态 → 重新启动链式调度

**涉及文件**：

- `AlarmScheduler.kt` — 设置闹钟
- `TaskResetReceiver.kt` — 接收闹钟广播
- `ForegroundRunningService.kt` — Alarm 兜底 + 倒计时显示

**重点关注**：

| 风险点                                                                                           | 描述                                                                                       |
|-----------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| Alarm + 每分钟广播兜底                                                                               | 双重触发可能导致 `ResetDailyTask` 被 post 两次                                                      |
| `checkAndTriggerReset()` 的 `currentHour !in resetHour..(resetHour + 1)`                       | 如果 resetHour=23，范围是 23..24，`Calendar.HOUR_OF_DAY` 最大 23，边界没问题。但如果 resetHour=0，范围 0..1，正常 |
| `resetTaskSeconds()` 当 `currentHour == hour && currentMinute == 0 && currentSecond == 0` 时算明天 | 只有刚好在 00:00:00 这一秒才触发，几乎不可能命中                                                            |

---

## ⑦ 消息通知线

**做什么**：打卡结果 / 错误信息 → 根据渠道（QQ邮箱 / 企业微信）→ 发送

**涉及文件**：

- `MessageDispatcher.kt` — 消息分发
- `EmailManager.kt` — 邮件发送
- `HttpRequestManager.kt` — 企业微信 Webhook
- `MessageViewModel.kt` + `MessageChannelActivity.kt` — 企业微信配置

**重点关注**：

| 风险点                                                                             | 描述                                   |
|---------------------------------------------------------------------------------|--------------------------------------|
| `sendMessage()` 变量遮蔽 `content`                                                  | 不影响运行但误导                             |
| `HttpRequestManager` 每次 `sendMessage` 都 `CoroutineScope(Dispatchers.IO).launch` | 每次都创建新协程作用域，无法取消，如果网络超时 10 秒，多次调用会堆积 |

---

## ⑧ 配置 & 数据线

**做什么**：任务的增删改查 + 邮箱配置持久化 + SharedPreferences 读写

**涉及文件**：

- `DatabaseWrapper.kt` — Room/数据库封装
- `TaskDataManager.kt` — 任务导入导出
- `ConfigStore.kt` — JSON 文件配置存储（邮箱等）
- `SaveKeyValues.kt`（lite 模块）— SharedPreferences 封装
- `Constant.kt` — 所有 Key 定义

**重点关注**：

| 风险点                                      | 描述                             |
|------------------------------------------|--------------------------------|
| `ConfigStore` 用 `ReentrantReadWriteLock` | 每次 `save()` 都写文件，频繁操作时 I/O 压力大 |
| `DatabaseWrapper` 可能是静态方法                | 需要确认 Room 的线程安全是否正确使用          |
| `SaveKeyValues` 和 `ConfigStore` 两套存储     | 为什么用两套？是否有关联数据需要跨存储同步？         |

---

## ⑨ 保活 & 前台服务线

**做什么**：ForegroundRunningService 降低被杀概率 + 电量监控 + 低电量告警

**涉及文件**：

- `ForegroundRunningService.kt` — 全部逻辑
- `DailyTaskApplication.kt` — 应用初始化

**重点关注**：

| 风险点                                 | 描述                                                                                        |
|-------------------------------------|-------------------------------------------------------------------------------------------|
| `BroadcastReceiver` 在 `onCreate` 注册 | START_STICKY 服务被杀重启时可能重复注册（虽然 onCreate 不一定被再调，但需要确认）                                      |
| `checkLowBattery()`                 | `ACTION_BATTERY_CHANGED` 是 sticky broadcast，注册时会立刻收到一次。但 `onCreate` 结尾也手动调了一次，所以初始化时检查了两次 |
| 低电量提醒冷却 `5 * 60 * 1000`             | 5 分钟内只提醒一次，但如果电量在 19% 和 20% 之间反复横跳，会漏报                                                    |

---

## 🗺️ 建议的 Review 顺序

```
① 链式任务调度  ──→  ② 打卡检测&远程指令  ──→  ③ 截屏服务
     ↓                                              ↓
⑤ 悬浮窗      ←──  ④ 伪息屏MaskView         ⑥ 任务重置
     ↓                                              ↓
⑦ 消息通知    ←──  ⑧ 配置&数据              ⑨ 保活&前台服务
```

从 **① 链式任务调度** 开始深入 Review，这是整个项目的骨架，也是 bug 最密集的地方。