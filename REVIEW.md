# 功能线 Review 路线图

项目按功能拆成 9 条线，每条只涉及 2-5 个文件。按"容易出 Bug 的程度 + 重要程度"排序，逐条
Review，比盯着全部代码轻松得多。

每次只看一条线，**只看涉及的 2-5 个文件**，跟着数据流向走一遍：谁发起 → 经过谁 → 谁处理 → 谁响应。

---

## ① 链式任务调度线（最核心，最复杂）

**做什么**：启动 → 算时间 → 倒计时 → 到点打开 APP → 等打卡 → 推进下一个 → 循环直到全部完成

**涉及文件**：

- `TaskScheduler.kt` — 调度核心，算时间、找下一个任务
- `CountDownTimerService.kt` — 倒计时、到点回调
- `TimeoutTimerManager.kt` — 打卡超时兜底
- `MainActivity.kt`（部分）— `onTaskStarted/Stopped/Completed/Executing` 回调

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| `todaySchedule` 一天只构建一次 | 如果最后一个任务跨到次日 00:00 后，`scheduleNextTask()` 里的 `now`
可能已经超过所有 `actualTimeMillis`，提前结束 |
| `executeNextTask()` 依赖外部调用 | 打卡成功或超时才推进，如果回调丢了就卡死 |
| `CountDownTimerService` 的 `tickRunnable` 和 `stopTimerInternal()` | 看到有
`synchronized(timerLock)` 但 `isTimerRunning` 用 `@Volatile`，混合保护方式存在竞争窗口 |
| `onCountDownFinished()` 打开 APP + 启动超时定时器 | 如果 APP 已经在超时定时器中，会不会重复创建？ |

---

## ② 打卡检测 & 远程指令线

**做什么**：监听通知栏 → 识别"打卡成功" → 回调；识别"
执行任务/终止任务/息屏/亮屏/截屏/考勤记录/状态查询"等远程指令

**涉及文件**：

- `NotificationMonitorService.kt` — 全部逻辑
- `MainActivity.kt`（`MonitorCallback` 实现部分）

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| 静态 `monitorCallback` | Activity 重建时可能持有旧引用 |
| `listenerConnected` 不是 `@Volatile` | 状态查询时可能读到过期值 |
| `handleRemoteCommand()` 用 `when { }` 链 |
通知内容同时包含多个关键词时会命中第一个，可能不是用户意图 |
| 打卡检测依赖通知内容包含"成功" | 如果 APP 更新了通知文案，就检测不到了 |

---

## ③ 截屏服务线

**做什么**：MediaProjection 授权 → VirtualDisplay + ImageReader → 收到 `CaptureScreen` 事件 → 截图 →
裁剪上半部分 → 检测黑色画面 → 重试 → 保存文件

**涉及文件**：

- `CaptureImageService.kt` — 全部逻辑
- `ProjectionSession.kt` — MediaProjection 生命周期管理
- `MainActivity.kt`（部分）— `CaptureCompleted` 事件处理

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| `onStartCommand` 失败时死循环 | resultCode = RESULT_CANCELED 返回 START_STICKY，服务反复重启 |
| `isBitmapMostlyBlack()` 采样步长 10 + 阈值 90% | 如果截图正好是暗色
UI（如暗黑模式聊天界面），可能误判为黑屏 |
| `ImageReader` 用 `RGBA_8888` | 部分 OEM 的 VirtualDisplay 对 RGBA
支持不好，可能返回黑色帧，导致不断重试 |
| `waitForImageAvailable` 的 `withTimeoutOrNull(2000)` | 2 秒超时可能在低端机上不够 |

---

## ④ 伪息屏（MaskView）线

**做什么**：显示全屏遮罩 → 隐藏真实界面 → 时钟随机移动模拟熄屏显示 → 手势/音量键切换

**涉及文件**：

- `MaskViewController.kt` — 蒙层控制
- `GestureController.kt` — 滑动手势检测
- `MainActivity.kt`（部分）— 音量键、onNewIntent

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| `clockAnimationRunnable` 递归 postDelayed 30000ms | 如果 `stopClockAnimation` 和
`startClockAnimation` 竞争，可能创建多个并行 Runnable |
| `GestureController.minFlingDistance = 1000f` | 1000px 的滑动距离在有些屏幕上太长，可能划不动 |
| `MaskViewController` 持有 `insetsController` 是构造时传入的 | Activity 配置变更后可能失效 |
| `showMaskView()` 里 `hideMaskView()` 的动画取消操作 | `currentAnimation?.cancel()` 后立即创建新动画，cancel
回调可能干扰 |

---

## ⑤ 悬浮窗线

**做什么**：显示悬浮窗 → 拖动 → 倒计时显示 → 内存使用监控 → 超标预警

**涉及文件**：

- `FloatingWindowService.kt` — 悬浮窗主体
- `FloatingWindowController.kt` — 外部控制接口
- `TimeoutTimerManager.kt`（部分）— `updateTime` 调用

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| `lateinit binding` + `START_STICKY` | 服务重启不调 onCreate，binding 未初始化 |
| 内存监控 `delay(1000)` 省电模式下 `60000` | 省电模式每 60 秒检查一次，内存可能已经爆了才报警 |
| `onDestroy` 里 `cancel()` | `CoroutineScope by CoroutineScope(Dispatchers.Main)` 的实现，如果
cancel 先于协程完成，可能残留 |
| `windowManager.addView` 在 `onCreate` 中 | 如果 App 在后台时 Service 被 START_STICKY 重启，
`onCreate` 可能不被调用 |

---

## ⑥ 任务重置线

**做什么**：每天到设定时间 → 重置任务状态 → 重新启动链式调度

**涉及文件**：

- `AlarmScheduler.kt` — 设置闹钟
- `TaskResetReceiver.kt` — 接收闹钟广播
- `ForegroundRunningService.kt` — Alarm 兜底 + 倒计时显示

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| Alarm + 每分钟广播兜底 | 双重触发可能导致 `ResetDailyTask` 被 post 两次 |
| `checkAndTriggerReset()` 的 `currentHour !in resetHour..(resetHour + 1)` | 如果 resetHour=23，范围是
23..24，`Calendar.HOUR_OF_DAY` 最大 23，边界没问题。但如果 resetHour=0，范围 0..1，正常 |
| `resetTaskSeconds()` 当 `currentHour == hour && currentMinute == 0 && currentSecond == 0`
时算明天 | 只有刚好在 00:00:00 这一秒才触发，几乎不可能命中 |

---

## ⑦ 消息通知线

**做什么**：打卡结果 / 错误信息 → 根据渠道（QQ邮箱 / 企业微信）→ 发送

**涉及文件**：

- `MessageDispatcher.kt` — 消息分发
- `EmailManager.kt` — 邮件发送
- `HttpRequestManager.kt` — 企业微信 Webhook
- `MessageViewModel.kt` + `MessageChannelActivity.kt` — 企业微信配置

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| `sendMessage()` 变量遮蔽 `content` | 不影响运行但误导 |
| `HttpRequestManager` 每次 `sendMessage` 都 `CoroutineScope(Dispatchers.IO).launch` |
每次都创建新协程作用域，无法取消，如果网络超时 10 秒，多次调用会堆积 |

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
| 风险点 | 描述 |
|--------|------|
| `ConfigStore` 用 `ReentrantReadWriteLock` | 每次 `save()` 都写文件，频繁操作时 I/O 压力大 |
| `DatabaseWrapper` 可能是静态方法 | 需要确认 Room 的线程安全是否正确使用 |
| `SaveKeyValues` 和 `ConfigStore` 两套存储 | 为什么用两套？是否有关联数据需要跨存储同步？ |

---

## ⑨ 保活 & 前台服务线

**做什么**：ForegroundRunningService 降低被杀概率 + 电量监控 + 低电量告警

**涉及文件**：

- `ForegroundRunningService.kt` — 全部逻辑
- `DailyTaskApplication.kt` — 应用初始化

**重点关注**：
| 风险点 | 描述 |
|--------|------|
| `BroadcastReceiver` 在 `onCreate` 注册 | START_STICKY 服务被杀重启时可能重复注册（虽然 onCreate
不一定被再调，但需要确认） |
| `checkLowBattery()` | `ACTION_BATTERY_CHANGED` 是 sticky broadcast，注册时会立刻收到一次。但
`onCreate` 结尾也手动调了一次，所以初始化时检查了两次 |
| 低电量提醒冷却 `5 * 60 * 1000` | 5 分钟内只提醒一次，但如果电量在 19% 和 20% 之间反复横跳，会漏报 |

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

---

# 功能线之间的交互接缝（解缠用）

所有线都汇聚到两个枢纽：**`MainActivity`**（既订阅事件、又实现回调、又驱动调度器）和 **`EventBus`**（全
app 共用总线）。所以 review 某条线时总会撞见其他线。但线之间的交互**只通过 3 类接缝**，把它们当黑盒端口即可独立看每条线。

## 接缝 1：EventBus（全项目唯一总线，9 个事件）

每个事件的「生产者 → 消费者」即线之间的全部直接关联：

| 事件                               | 生产者（线）                                                            | 消费者（线）                          | 连接        |
|----------------------------------|-------------------------------------------------------------------|---------------------------------|-----------|
| `CaptureScreen`                  | TimeoutTimerManager(①)、SettingsActivity(⑧)、NotificationMonitor(②) | CaptureImageService(**③**)      | ①/⑧/② → ③ |
| `CaptureCompleted`               | CaptureImageService(**③**)                                        | MainActivity(**①**)             | ③ → ①     |
| `ProjectionDestroyed`            | CaptureImageService(**③**)                                        | MainActivity(**①**)             | ③ → ①     |
| `ProjectionReady/Failed`         | CaptureImageService(**③**)                                        | SettingsActivity(**⑧**)         | ③ → ⑧     |
| `StopDailyTask`                  | NotificationMonitor(②)                                            | MainActivity(**①**)             | ② → ①     |
| `ResetDailyTask`(sticky)         | TaskResetReceiver(⑥)、FGService(⑨)                                 | MainActivity(**①**)→startTask   | ⑥/⑨ → ①   |
| `SetResetTaskTime`               | TaskConfigActivity(**⑥**)                                         | ForegroundRunningService(**⑨**) | ⑥ → ⑨     |
| `UpdateResetTickTime`            | ForegroundRunningService(**⑨**)                                   | MainActivity(**①**)             | ⑨ → ①     |
| `ListenerConnected/Disconnected` | NotificationMonitor(**②**)                                        | SettingsActivity(**⑧**)         | ② → ⑧     |

## 接缝 2：共享单例（5 个，被多条线直接调用）

| 单例                         | 主要归属线 | 被谁跨线触碰                                                  |
|----------------------------|-------|---------------------------------------------------------|
| `taskScheduler`            | ①     | MainActivity、CountDownTimerService、TimeoutTimerManager  |
| `timeoutTimerManager`      | ①/⑤   | TimeoutTimerManager、FloatingWindowService(`updateTime`) |
| `maskViewController`       | ④     | MainActivity、GestureController                          |
| `projectionSession`        | ③     | MainActivity、CaptureImageService                        |
| `floatingWindowController` | ⑤     | 悬浮窗内外                                                   |

## 接缝 3：唯一一个静态回调

`NotificationMonitorService.monitorCallback`（由 MainActivity 实现）→ ② 把「打卡成功/远程指令」直接推给
①。

## 每条线的「事件端口清单」（Review 时只看进出，其他黑盒）

- **① 链式任务调度**：出 `CaptureScreen`；入 `CaptureCompleted` / `ProjectionDestroyed` /
  `StopDailyTask` / `ResetDailyTask` / `UpdateResetTickTime`
- **② 打卡检测&远程指令**：出 `ListenerConnected` / `ListenerDisconnected` / `StopDailyTask` /
  `CaptureScreen`（远程截屏指令）；入 `monitorCallback` 被 MainActivity 实现
- **③ 截屏服务**：出 `CaptureCompleted` / `ProjectionReady` / `ProjectionFailed` /
  `ProjectionDestroyed`；入 `CaptureScreen`
- **④ 伪息屏 MaskView**：无 EventBus 端口，仅被 MainActivity / GestureController 直接调用
  `maskViewController`
- **⑤ 悬浮窗**：无事件端口，仅通过 `floatingWindowController` / `timeoutTimerManager.updateTime` 跨线
- **⑥ 任务重置**：出 `ResetDailyTask`(sticky) / `SetResetTaskTime`；入 无（自身闹钟/广播触发）
- **⑦ 消息通知**：无事件端口，被各线在出错/打卡结果时直接调用 `messageDispatcher`
- **⑧ 配置&数据**：出 `CaptureScreen`（手动测试截屏）/ `ListenerConnected` 等 UI 态；入
  `ProjectionReady/Failed` / `ListenerConnected/Disconnected`
- **⑨ 保活&前台服务**：出 `ResetDailyTask`(sticky) / `UpdateResetTickTime` / `SetResetTaskTime` 处理；入
  `SetResetTaskTime`

## 聚焦心法

1. 今天只盯一条线时，**只关心它自己 post 的事件和它自己 handle 的事件**。
2. 其他线全部当黑盒：比如线③何时截图、线⑥怎么触发重置，不需要展开，只要知道「收到 `CaptureCompleted`
   时我拿到图片路径」即可。
3. 遇到跨线调用就退一步：记下「别人会调我 / 我会调别人」，然后回到当前线。
4. **交互面 = 9 个事件端口 + 5 个共享单例调用点 + 1 个回调**，共不超过 15
   个固定接触点。脑子里只装一条线 + 它的端口清单，就不会乱。
