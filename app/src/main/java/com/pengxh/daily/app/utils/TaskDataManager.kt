package com.pengxh.daily.app.utils

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues

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

    private fun saveConfiguration(config: ExportDataModel): List<String> {
        val warnings = mutableListOf<String>()
        //
        SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, config.resetTime.coerceIn(0, 23))
        SaveKeyValues.saveInt(
            Constant.STAY_OVERTIME_KEY,
            config.overtime.takeIf { it > 0 } ?: Constant.DEFAULT_OVER_TIME
        )
        SaveKeyValues.saveInt(
            Constant.TIME_RANGE_KEY,
            config.timeRange.coerceAtLeast(0)
        )
        val messageChannel = config.msgChannel?.takeIf { it in 0..1 }
        if (messageChannel == null) {
            warnings += "消息渠道未配置，请在设置页完成配置。"
        }
        SaveKeyValues.saveInt(
            Constant.MSG_CHANNEL_KEY, messageChannel ?: Constant.DEFAULT_INDEX
        )

        val targetApp = config.targetApp.coerceIn(0, 3)
        SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, targetApp)
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
        SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, resultSource)

        //
        SaveKeyValues.saveString(
            Constant.REMOTE_COMMAND_KEY,
            config.remoteCommand?.takeIf { it.isNotBlank() } ?: "打卡"
        )
        SaveKeyValues.saveString(
            Constant.MESSAGE_TITLE_KEY,
            config.msgTitle?.takeIf { it.isNotBlank() } ?: "打卡结果通知"
        )
        SaveKeyValues.saveString(Constant.WX_WEB_HOOK_KEY, config.wxKey ?: "")
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
        SaveKeyValues.saveString(Constant.CUSTOM_WORKDAYS_KEY, workdays)

        //
        SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, config.isDetectGesture)
        SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, config.isBackToHome)
        SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, config.isAutoRecycle)
        SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, config.isRandomTime)
        SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, config.isSkipHoliday)
        SaveKeyValues.saveBoolean(Constant.POWER_SAVE_MODE_KEY, config.isSavePower)

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
            ConfigStore.get().save(Constant.EMAIL_CONFIG_KEY, cacheObj)
        }
        return warnings
    }

    private fun isValidTaskTime(time: String?): Boolean {
        return !time.isNullOrBlank() && taskTimePattern.matches(time)
    }

    sealed class ImportResult {
        /** 导入成功，count 为成功导入的任务数量 */
        data class Success(val count: Int, val warnings: List<String> = emptyList()) : ImportResult()

        /** 导入失败，message 为错误信息 */
        data class Error(val message: String) : ImportResult()
    }
}
