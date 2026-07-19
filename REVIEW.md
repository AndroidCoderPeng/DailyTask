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

**测试路线**：

| # | 场景     | 操作                                                 | 预期结果                                                                                          | 看什么                       | 状态       |
|---|--------|----------------------------------------------------|-----------------------------------------------------------------------------------------------|---------------------------|----------|
| 1 | 正常完整链路 | 配置 1 个任务 → 打开目标 APP → 等倒计时归零 → 进入 APP → APP 触发极速打卡 | 倒计时归零打开 APP，NotificationMonitorService 识别通知 → TaskScheduler 收到 notifyClockIn → 自动回到主页后高亮下一个任务 | 悬浮窗倒计时数字、APP 是否被拉起、任务列表状态 | 🟢️测试通过  |
| 2 | 超时兜底截屏 | 配置 1 个任务 → 倒计时归零进入 APP → 一直不打卡，等待超时                | 最后 5 秒触发截屏 → 保存截图 → 超时后回到主页 → 自动发送兜底截图给用户 → 继续下一个任务                                           | 截图文件是否存在、超时后是否回到主页、任务是否推进 | 🟢️测试通过  |
| 3 | 中途停止任务 | 任务执行中 → 通过悬浮窗通知发送"终止任务"                            | TaskScheduler 停止，任务停止，按钮状态重置                                                                  | 按钮状态、协程是否正确取消             | 🟢️测试通过  |
| 4 | 多任务链式  | 配置 3 个任务（不同时间）→ 启动 → 依次等待每个任务完成                    | 每个任务按时间顺序执行，一个完成后自动推进到下一个                                                                     | 任务列表状态依次更新                | 🟢️测试通过️ |
| 5 | 空任务列表  | 清空所有任务 → 点击启动                                      | 提示无任务或直接不可启动                                                                                  | 不崩溃、给出合理提示                | 🟢️测试通过️ |

---

## ② 打卡检测 & 远程指令线

**做什么**：监听通知栏 → 识别"打卡成功" → `emitMonitorEvent(ClockInSuccess)` → MainActivity 通过
SharedFlow 收集 → `TaskScheduler.notifyClockIn()`；识别
`DT#` 前缀开头的"执行任务/终止任务/息屏/亮屏/截屏/考勤记录/状态查询"等远程指令（必须以 `DT#` 开头，否则忽略）

**涉及文件**：

- `NotificationMonitorService.kt` — 全部逻辑（`events: SharedFlow<MonitorEvent>` 对外通信）
- `MainActivity.kt`（`handleMonitorEvent` 部分）— 订阅 `NotificationMonitorService.events`

**通信方式**：

- `NotificationMonitorService.events` SharedFlow → MainActivity（6 种 MonitorEvent）
- `NotificationMonitorService.listenerState` SharedFlow → SettingsActivity（UI 状态）

**重点关注**：

| 风险点                                           | 描述                                        |
|-----------------------------------------------|-------------------------------------------|
| `handleRemoteCommand()` 用 `when { }` 链        | 通知内容同时包含多个关键词时会命中第一个，可能不是用户意图             |
| 打卡检测依赖通知内容包含"成功"                              | 如果 APP 更新了通知文案，就检测不到了                     |
| `events` SharedFlow `extraBufferCapacity = 2` | 如果 MainActivity 未收集（被杀死），最多缓存 2 个事件，超出的丢失 |

**测试路线**：

| # | 场景         | 操作                                           | 预期结果                                                                       | 看什么                                        | 状态       |
|---|------------|----------------------------------------------|----------------------------------------------------------------------------|--------------------------------------------|----------|
| 1 | 打卡成功通知识别   | 让目标 APP 弹出"打卡成功"通知（如钉钉打卡成功）                  | NotificationMonitorService 捕获通知 → 发射 `ClockInSuccess` → MainActivity 收集到事件 | logcat 搜索 `ClockInSuccess`、`notifyClockIn` | 🟢️测试通过  |
| 2 | 远程"执行任务"指令 | 从另一台手机通过QQ、微信、支付宝或者TIM给自己发一条消息，内容包含"DT#执行任务" | 触发 startTask，相当于点击了启动按钮                                                    | 任务是否开始执行                                   | 🟢️测试通过️ |
| 3 | 远程"终止任务"指令 | 任务执行中 → 发送内容含"DT#终止任务"的通知                    | 任务停止                                                                       | 按钮恢复初始                                     | 🟢️测试通过️ |
| 4 | 远程"息屏"指令   | 发送内容含"DT#息屏"的通知                              | MaskViewController 显示蒙层                                                    | 屏幕被遮罩覆盖、时钟动画启动                             | 🟢️测试通过️ |
| 5 | 远程"亮屏"指令   | 在息屏状态 → 发送内容含"DT#亮屏"的通知                      | 蒙层消失                                                                       | 屏幕恢复可见                                     | 🟢️测试通过️ |
| 6 | 远程"截屏"指令   | 发送内容含"DT#截屏"的通知（确保 MediaProjection 已就绪）      | 触发截屏 → 保存文件                                                                | 截图文件生成                                     | 🟢️测试通过️ |
| 7 | 远程"考勤记录"指令 | 发送内容含"DT#考勤记录"的通知                            | 发送当日打卡记录消息（企业微信 / 邮箱）                                                      | 是否收到统计消息                                   | 🟢️测试通过️ |
| 8 | 远程"状态查询"指令 | 发送内容含"DT#状态查询"的通知                            | 回复当前运行状态（哪些服务在跑、当前时间、电量等）                                                  | 返回的状态信息是否准确                                | 🟢️测试通过️ |

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

**测试路线**：

| # | 场景     | 操作                                      | 预期结果                                           | 看什么                        | 状态       |
|---|--------|-----------------------------------------|------------------------------------------------|----------------------------|----------|
| 1 | 正常截屏   | SettingsActivity → 点击"测试截屏"             | 截图保存成功                                         | 截图文件存在、文件大小 > 0、画面正确（上半部分） | 🟢️测试通过️ |
| 2 | 拒绝授权   | 弹出授权弹窗 → 点击"拒绝"                         | `ProjectionFailed` 事件发出，SettingsActivity 显示失败  | 不崩溃、提示授权失败                 | 🟢️测试通过️ |
| 3 | 遥控截屏   | 先确保 MediaProjection 已授权 → 发送"DT#截屏"远程指令 | CaptureImageService 收到 CaptureScreen 事件 → 截屏成功 | 截图文件生成                     | 🟢️测试通过️ |
| 4 | 黑屏画面重试 | 截屏时故意让屏幕显示全黑内容（如打开全黑图片）                 | `isBitmapMostlyBlack` 检测为黑色 → 重试               | logcat 搜索重试日志、最终是否放弃或成功    | 🟢️测试通过️ |
| 5 | 连续多次截屏 | 快速连续触发 3-5 次截屏（手动测试 + 远程指令）             | 每次截屏独立完成，不互相干扰                                 | 截图文件数量正确、无遗漏               | 🟢️测试通过  |

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

**测试路线**：

| # | 场景        | 操作                                | 预期结果                                  | 看什么                   | 状态      |
|---|-----------|-----------------------------------|---------------------------------------|-----------------------|---------|
| 1 | 手势滑动解锁    | 蒙层显示中 → 在屏幕中间向上滑动 > 1000px        | GestureController 检测到滑动 → 触发亮屏 → 蒙层消失 | 滑动灵敏度、误触是否被忽略         | 🟢️测试通过 |
| 2 | 音量键切换     | 蒙层显示中 → 按音量下键 → 再按一次              | 第一次：亮屏（蒙层消失）；再按一次：息屏（蒙层显示）            | 音量键事件是否正确拦截、系统音量不变化   | 🟢️测试通过 |
| 3 | 远程"息屏/亮屏" | 发送"DT#息屏"通知 → 等 3 秒 → 发送"DT#亮屏"通知 | 蒙层按指令显示/隐藏                            | 与②联动的正确性              | 🟢️测试通过 |
| 4 | 时钟动画移动    | 蒙层显示中 → 等待 > 30 秒                 | 时钟文字位置随机变化（每 30 秒 postDelayed 一次）     | 位置确实变了、不卡在同一个位置       | 🟢️测试通过 |
| 5 | 快速切换      | 连续快速按音量下键按钮 5 次                   | 动画不冲突、不出现两个蒙层叠加                       | 无异常 Crash、最终状态与最后操作一致 | 🟢️测试通过 |

---

## ⑤ 悬浮窗线

**做什么**：显示悬浮窗 → 拖动 → 倒计时显示 → 内存使用监控 → 超标预警

**涉及文件**：

- `FloatingWindowService.kt` — 悬浮窗主体，订阅 `FloatingWindowController` 的 3 个 SharedFlow
- `FloatingWindowController.kt` — 外部控制接口（`timeTick` / `overtime` / `visibility`）

**谁在驱动悬浮窗**：

- `TaskScheduler.executeSchedule()` 超时倒计时 → `FloatingWindowController.updateTime(tick)`
- `NotificationMonitorService` 遥控"DT#打卡"独立倒计时 → `FloatingWindowController.updateTime()`
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

**测试路线**：

| # | 场景      | 操作                                | 预期结果                 | 看什么                                                                           | 状态      |
|---|---------|-----------------------------------|----------------------|-------------------------------------------------------------------------------|---------|
| 1 | 拖动悬浮窗   | 按住悬浮窗 → 拖动到屏幕任意位置 → 松手            | 悬浮窗停留在新位置、不弹回原位      | 拖动流畅度、松手后位置锁定                                                                 | 🟢️测试通过 |
| 2 | 倒计时数字更新 | 观察任务执行中悬浮窗显示的剩余秒数                 | 数字每秒递减，与实际倒计时一致      | 数字变化频率、与 TaskScheduler 的 tick 同步                                              | 🟢️测试通过 |
| 3 | 超时时间修改  | TaskConfigActivity 修改超时时间 → 返回主页面 | 悬浮窗的倒计时上限更新为新值       | overtime Flow 是否被正确 collect                                                   | 🟢️测试通过 |
| 4 | 内存监控告警  | 模拟高内存占用（打开多个大型 APP）→ 等待内存监控周期     | 发送内存超标消息（企业微信或者QQ邮件） | 内存值是否正确、告警阈值是否合理                                                              | ⚠️暂未测试  |
| 5 | 省电模式    | 系统设置中开启省电模式 → 观察内存监控频率            | 监控间隔从 1 秒变为 60 秒     | 模式切换是否实时生效                                                                    | 🟢️测试通过 |
| 6 | 息屏时隐藏   | 任务运行中 → 触发息屏（蒙层显示）                | 悬浮窗自动隐藏              | 与④的联动正确：`MaskViewController.showMaskView` → `FloatingWindowController.hide()` | 🟢️测试通过 |

---

## ⑥ 任务重置线

**做什么**：每天到设定时间 → 重置任务状态 → 重新启动链式调度

**涉及文件**：

- `TaskScheduler.kt` — 协程 `delay` 主力调度（`waitUntilNextReset`）
- `ForegroundRunningService.kt` — `ACTION_TIME_TICK` 每分钟兜底 + 倒计时显示

**架构要点**：

- 主力：`TaskScheduler.waitUntilNextReset()` 用协程 `delay` 精确等待到次日重置时间
- 兜底：`ForegroundRunningService` 订阅 `ACTION_TIME_TICK`，每分钟检查一次是否到达重置时间，防止长时间休眠后
  delay 偏移
- `checkAndTriggerReset()` 收拢到 `ForegroundRunningService.kt`，作为重置逻辑的唯一入口

**重点关注**：

| 风险点                                                                                           | 描述                                                                                       |
|-----------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `checkAndTriggerReset()` 的 `currentHour !in resetHour..(resetHour + 1)`                       | 如果 resetHour=23，范围是 23..24，`Calendar.HOUR_OF_DAY` 最大 23，边界没问题。但如果 resetHour=0，范围 0..1，正常 |
| `resetTaskSeconds()` 当 `currentHour == hour && currentMinute == 0 && currentSecond == 0` 时算明天 | 只有刚好在 00:00:00 这一秒才触发，几乎不可能命中                                                            |
| 协程 delay 长时间休眠后唤醒                                                                             | 系统 Doze 模式可能导致 delay 偏差，`ACTION_TIME_TICK` 兜底只能在亮屏时收到广播                                  |

**测试路线**：

| # | 场景     | 操作                             | 预期结果                                                         | 看什么                       | 状态      |
|---|--------|--------------------------------|--------------------------------------------------------------|---------------------------|---------|
| 1 | 修改重置时间 | TaskConfigActivity 修改重置时间 → 保存 | FGService 更新 `resetTickTime` 倒计时，`waitUntilNextReset` 按新时间计算 | 悬浮窗倒计时文字同步更新，delay 等待时间更新 | 🟢️测试通过 |
| 2 | 时间兜底触发 | 修改系统时间到次日重置时间前后                | `ACTION_TIME_TICK` 到达重置时间时触发 `checkAndTriggerReset`          | 不会启动两次调度、不出现重复任务执行        | ⚠️暂未测试 |

---

## ⑦ 消息通知线

**做什么**：打卡结果 / 错误信息 → `MessageDispatcher` 根据用户配置自动分流 → QQ 邮箱（`EmailManager`
）或企业微信 Webhook（`RetrofitServiceManager`）→ 异步发送

**涉及文件**：

- `MessageDispatcher.kt` — 统一消息分发入口（全局 `object`），封装渠道分流逻辑、元数据拼接、企微响应处理，全局复用协程作用域
- `EmailManager.kt` — QQ 邮箱 SMTP 发送，支持普通邮件和带附件邮件，`loadEmailConfig()` 提取公共配置加载
- `RetrofitServiceManager.kt` — 企业微信 Webhook API 调用（文本消息 / 图片消息），基于 Retrofit suspend
  函数
- `MessageChannelActivity.kt` — 消息渠道配置界面（QQ 邮箱 + 企业微信 Key）

**架构要点**：

- `MessageDispatcher` 是全局 `object`，通过 `DailyTaskApplication.onCreate` 中 `initialize()` 拿到
  `BatteryManager`
- 所有调用方统一走 `sendMessage()` / `sendAttachmentMessage()`，内部根据 `SaveKeyValues` 中存储的渠道配置分流
- `sendMessage` 入口统一拼接元数据（标题、正文、日期、电量、版本号），保证邮件和企微正文一致
- 邮件通过 `EmailManager.sendAsync()` 在内部 `CoroutineScope(IO)` 异步发送，回调切到 Main 线程
- 企微通过 `RetrofitServiceManager` suspend 函数在 `MessageDispatcher.scope` 中调用，
  `handleWechatResponse` 统一处理响应

**重点关注**：

| 风险点                                               | 描述                                                                                                   |
|:--------------------------------------------------|:-----------------------------------------------------------------------------------------------------|
| `initialize()` 未调用时 `batteryManager` 是 `lateinit` | 需确保 `DailyTaskApplication.onCreate` 中先调用，否则 `sendMessage` 直接抛 `UninitializedPropertyAccessException` |
| `EmailManager.sendEmail` 配置缺失时回调 `onFailure`，不抛异常 | 若调用方未传 `onFailure`，静默失败，无法感知                                                                         |
| 企业微信图片消息 2MB 限制                                   | `RetrofitServiceManager.sendImageMessage` 检测超限后降级为文本提示，截图分辨率较高时可能被拒绝                                 |
| `handleWechatResponse` 回调均为 null 时提前 return       | 避免无意义的 JSON 解析和线程切换，守卫逻辑正确                                                                           |

**测试路线**：

| # | 场景     | 操作                               | 预期结果                | 看什么           | 状态      |
|:--|:-------|:---------------------------------|:--------------------|:--------------|:--------|
| 1 | 企业微信发送 | 配置好企业微信 Webhook Key → 触发一条打卡结果消息 | 企微收到消息，正文含日期/电量/版本号 | 元数据完整、格式正确    | 🟢️测试通过 |
| 2 | QQ邮箱发送 | 配置好 SMTP 邮箱 → 触发一条通知消息           | 邮箱收到邮件，标题和正文正确      | 邮件标题和内容一致     | 🟢️测试通过 |
| 3 | 无网络发送  | 关闭 WiFi 和移动数据 → 触发消息发送           | 消息发送失败但不 Crash      | 超时后优雅失败、有错误日志 | 🟢️测试通过 |
| 4 | 渠道切换   | 企微 → 邮箱 → 再发送                    | 新消息走新渠道             | 切换后无旧渠道残留     | 🟢️测试通过 |

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

**测试路线**：

| # | 场景      | 操作                                          | 预期结果                  | 看什么                                     | 状态      |
|---|---------|---------------------------------------------|-----------------------|-----------------------------------------|---------|
| 1 | 任务 CRUD | TaskConfigActivity → 添加一个任务 → 编辑 → 删除       | 数据库持久化正确，重启 APP 后数据仍在 | 列表刷新正确、删除后序号重新排列                        | 🟢️测试通过 |
| 2 | 邮箱配置    | SettingsActivity → 配置 SMTP 信息 → 保存 → 重启 APP | 配置持久化，重启后不丢失          | ConfigStore 写入成功、读取一致                   | 🟢️测试通过 |
| 3 | 任务导入导出  | 导出当前任务列表为 JSON → 清空 → 重新导入                  | 导入后任务列表与导出前完全一致       | JSON 格式正确、所有字段完整                        | 🟢️测试通过 |
| 4 | 并发读写    | 快速多次切换页面、同时修改配置和任务                          | 数据一致性正确，无脏读脏写         | Room 事务正确、ReentrantReadWriteLock 不阻塞 UI | 🟢️测试通过 |

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

**测试路线**：

| # | 场景           | 操作                                       | 预期结果                                              | 看什么                     | 状态      |
|---|--------------|------------------------------------------|---------------------------------------------------|-------------------------|---------|
| 1 | 前台服务启动       | APP 启动后 → 下拉通知栏                          | 通知栏显示"每日任务运行中"的前台服务通知                             | 通知存在且内容正确               | 🟢️测试通过 |
| 3 | 低电量提醒 1      | 电量降到 20% 以下 → 观察                         | 触发低电量通知提醒                                         | 通知内容、提醒时机               | ⚠️暂未测试  |
| 4 | 低电量提醒 2（冷却期） | 电量 20% 以下 → 插上充电器 → 拔掉 → 再次低于 20%（5 分钟内） | 不重复提醒                                             | 冷却机制生效                  | ⚠️暂未测试  |
| 5 | 低电量恢复        | 电量低于 20% → 插上充电器充到 30%+                  | 低电量状态解除，不持续告警                                     | 无多余提醒                   | ⚠️暂未测试  |
| 6 | 重置倒计时更新      | 修改重置时间 → 回到主页面                           | `resetTickTime` SharedFlow 更新 MainActivity 的倒计时文字 | 显示的文字与 FGService 内部计算一致 | 🟢️测试通过 |
| 7 | 全天运行稳定性      | 保持 APP 运行 24 小时（或至少 8 小时）                | 服务不异常退出、通知不消失、不 ANR                               | 内存占用稳定、无 OOM            | 🟢️测试通过 |

---

从 **① 链式任务调度** 开始深入 Review，这是整个项目的骨架，也是 bug 最密集的地方。