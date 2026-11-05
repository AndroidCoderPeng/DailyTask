package com.pengxh.daily.app.utils

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.extensions.toJson
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskDataManager() {

    private val gson by lazy { Gson() }
    private val taskTimePattern by lazy {
        Regex("""^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$""")
    }

    suspend fun importTasks(json: String): ImportResult {
        return try {
            val type = object : TypeToken<ExportDataModel>() {}.type
            val config = gson.fromJson<ExportDataModel>(json, type)

            // 保存相关配置
            val warnings = saveConfiguration(config)

            // 导入任务
            val importedTasks = mutableListOf<DailyTaskBean>()
            for (task in config.tasks.orEmpty()) {
                val taskTime = task.time
                if (!isValidTaskTime(taskTime)) continue

                // 跳过已存在的任务时间点
                if (!DatabaseWrapper.isTaskTimeExist(taskTime)) {
                    task.id = 0
                    DatabaseWrapper.insert(task)
                    importedTasks.add(task)
                }
            }

            ImportResult.Success(importedTasks.size, warnings)
        } catch (e: JsonSyntaxException) {
            e.printStackTrace()
            ImportResult.Error("导入失败，请确认导入的是正确的任务数据")
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult.Error("导入失败：${e.message}")
        }
    }

    suspend fun exportTasks(): String {
        val exportData = ExportDataModel()

        // Int
        exportData.resetTime =
            AppConfig.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        exportData.overtime =
            AppConfig.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        exportData.timeRange =
            AppConfig.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
        exportData.msgChannel = AppConfig.loadInt(
            Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX
        ).takeIf { it in 0..1 }
        exportData.targetApp = AppConfig.loadInt(Constant.TARGET_APP_KEY, 0)
        exportData.resultSource = AppConfig.loadInt(
            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
        ).takeIf { it in 0..1 }

        // String
        exportData.remoteCommand = AppConfig.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
        exportData.msgTitle =
            AppConfig.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        exportData.wxKey = AppConfig.loadString(Constant.WX_WEB_HOOK_KEY, "")
        exportData.customWorkdays = CustomWorkdayManager.serializeWorkdays(
            CustomWorkdayManager.loadWorkdays()
        )

        // Boolean
        exportData.isDetectGesture =
            AppConfig.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
        exportData.isBackToHome = AppConfig.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
        exportData.isAutoRecycle =
            AppConfig.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
        exportData.isRandomTime = AppConfig.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        exportData.isSkipHoliday = AppConfig.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        exportData.isSavePower =
            AppConfig.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
        exportData.isRemoteClockInCapture =
            AppConfig.loadBoolean(Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, false)

        // EmailConfig
        val obj = AppConfig.loadJson(Constant.EMAIL_CONFIG_KEY)
        if (!obj.isEmpty) {
            val outbox = obj.get("outbox").asString
            val authCode = obj.get("authCode").asString
            val inbox = obj.get("inbox").asString
            exportData.emailConfig = Triple(outbox, authCode, inbox)
        }

        // TaskBeans
        val taskBeans = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        if (taskBeans.isNotEmpty()) {
            exportData.tasks = taskBeans
        } else {
            exportData.tasks = ArrayList<DailyTaskBean>()
        }

        return exportData.toJson()
    }

    private fun saveConfiguration(config: ExportDataModel): List<String> {
        val warnings = mutableListOf<String>()
        //
        AppConfig.saveInt(Constant.RESET_TIME_KEY, config.resetTime.coerceIn(0, 23))
        AppConfig.saveInt(
            Constant.STAY_OVERTIME_KEY,
            config.overtime.takeIf { it > 0 } ?: Constant.DEFAULT_OVER_TIME
        )
        AppConfig.saveInt(
            Constant.TIME_RANGE_KEY,
            config.timeRange.coerceAtLeast(0)
        )
        val messageChannel = config.msgChannel?.takeIf { it in 0..1 }
        if (messageChannel == null) {
            warnings += "消息渠道未配置，请在设置页完成配置。"
        }
        AppConfig.saveInt(
            Constant.MSG_CHANNEL_KEY, messageChannel ?: Constant.DEFAULT_INDEX
        )

        val targetApp = config.targetApp.coerceIn(0, 3)
        AppConfig.saveInt(Constant.TARGET_APP_KEY, targetApp)
        val importedResultSource = config.resultSource?.takeIf { it in 0..1 }
            ?: Constant.DEFAULT_INDEX
        val resultSource = when {
            targetApp != 0 && importedResultSource != 1 -> {
                warnings += "当前目标应用仅支持截屏结果来源，已自动切换为截屏；请在设置页开启截屏服务。"
                1
            }

            targetApp != 0 && !ProjectionSession.isStateActive() -> {
                warnings += "当前目标应用使用截屏结果来源，请在设置页开启截屏服务。"
                importedResultSource
            }

            targetApp == 0 && importedResultSource == 0 &&
                    !DailyTaskApplication.get().notificationEnable() -> {
                warnings += "当前结果来源为通知监听，但服务未授权，请在设置页开启通知监听。"
                importedResultSource
            }

            targetApp == 0 && importedResultSource == 1 &&
                    !ProjectionSession.isStateActive() -> {
                warnings += "当前结果来源为截屏，但服务未启动，请在设置页开启截屏服务。"
                importedResultSource
            }

            targetApp == 0 && importedResultSource == Constant.DEFAULT_INDEX -> {
                warnings += "结果来源未配置，请在设置页选择通知监听或截屏服务。"
                importedResultSource
            }

            else -> importedResultSource
        }
        AppConfig.saveInt(Constant.RESULT_SOURCE_KEY, resultSource)

        //
        AppConfig.saveString(
            Constant.REMOTE_COMMAND_KEY,
            config.remoteCommand?.takeIf { it.isNotBlank() } ?: "打卡"
        )
        AppConfig.saveString(
            Constant.MESSAGE_TITLE_KEY,
            config.msgTitle?.takeIf { it.isNotBlank() } ?: "打卡结果通知"
        )
        AppConfig.saveString(Constant.WX_WEB_HOOK_KEY, config.wxKey ?: "")
        val workdays = config.customWorkdays
            ?.takeIf { it.isNotBlank() }
            ?.let {
                CustomWorkdayManager.serializeWorkdays(
                    CustomWorkdayManager.loadWorkdaysFromRaw(it)
                )
            }
            ?: CustomWorkdayManager.serializeWorkdays(
                CustomWorkdayManager.getOrderedDays().take(5).toSet()
            )
        AppConfig.saveString(Constant.CUSTOM_WORKDAYS_KEY, workdays)

        //
        AppConfig.saveBoolean(Constant.GESTURE_DETECTOR_KEY, config.isDetectGesture)
        AppConfig.saveBoolean(Constant.BACK_TO_HOME_KEY, config.isBackToHome)
        AppConfig.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, config.isAutoRecycle)
        AppConfig.saveBoolean(Constant.RANDOM_TIME_KEY, config.isRandomTime)
        AppConfig.saveBoolean(Constant.SKIP_HOLIDAY_KEY, config.isSkipHoliday)
        AppConfig.saveBoolean(Constant.POWER_SAVE_MODE_KEY, config.isSavePower)
        AppConfig.saveBoolean(Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, config.isRemoteClockInCapture)

        //
        val email = config.emailConfig
        val outbox = email?.first
        val authCode = email?.second
        val inbox = email?.third
        if (email != null &&
            !outbox.isNullOrBlank() &&
            !authCode.isNullOrBlank() &&
            !inbox.isNullOrBlank()
        ) {
            val cacheObj = JsonObject().apply {
                addProperty("outbox", outbox)
                addProperty("authCode", authCode)
                addProperty("inbox", inbox)
            }
            AppConfig.saveJson(Constant.EMAIL_CONFIG_KEY, cacheObj)
        }
        return warnings
    }

    private fun isValidTaskTime(time: String?): Boolean {
        return !time.isNullOrBlank() && taskTimePattern.matches(time)
    }

    sealed class ImportResult {
        data class Success(val count: Int, val warnings: List<String> = emptyList()) :
            ImportResult()

        data class Error(val message: String) : ImportResult()
    }
}
