package com.pengxh.daily.app.utils

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues

class TaskDataManager() {

    private val gson by lazy { Gson() }
    private val taskTimePattern by lazy {
        Regex("""^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$""")
    }

    fun importTasks(json: String): ImportResult {
        return try {
            val type = object : TypeToken<ExportDataModel>() {}.type
            val config = gson.fromJson<ExportDataModel>(json, type)

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

            // 保存相关配置
            saveConfiguration(config)

            ImportResult.Success(importedTasks.size)
        } catch (e: JsonSyntaxException) {
            e.printStackTrace()
            ImportResult.Error("导入失败，请确认导入的是正确的任务数据")
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult.Error("导入失败：${e.message}")
        }
    }

    private fun saveConfiguration(config: ExportDataModel) {
        SaveKeyValues.putValue(
            Constant.MESSAGE_TITLE_KEY,
            config.messageTitle?.takeIf { it.isNotBlank() } ?: "打卡结果通知"
        )

        // 保存企业微信 Key
        SaveKeyValues.putValue(Constant.WX_WEB_HOOK_KEY, config.wxKey ?: "")

        val email = config.emailConfig
        val outbox = email?.outbox
        val authCode = email?.authCode
        val inbox = email?.inbox
        if (email != null &&
            !outbox.isNullOrBlank() &&
            !authCode.isNullOrBlank() &&
            !inbox.isNullOrBlank()
        ) {
            DatabaseWrapper.insertConfig(outbox, authCode, inbox)
        }

        SaveKeyValues.putValue(Constant.GESTURE_DETECTOR_KEY, config.isDetectGesture)
        SaveKeyValues.putValue(Constant.BACK_TO_HOME_KEY, config.isBackToHome)
        SaveKeyValues.putValue(Constant.RESET_TIME_KEY, config.resetTime.coerceIn(0, 23))
        SaveKeyValues.putValue(
            Constant.STAY_DD_TIMEOUT_KEY,
            config.overTime.takeIf { it > 0 } ?: Constant.DEFAULT_OVER_TIME
        )
        SaveKeyValues.putValue(
            Constant.TASK_COMMAND_KEY,
            config.command?.takeIf { it.isNotBlank() } ?: "打卡"
        )
        SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, config.isAutoStart)
        SaveKeyValues.putValue(Constant.RANDOM_TIME_KEY, config.isRandomTime)
        SaveKeyValues.putValue(
            Constant.RANDOM_MINUTE_RANGE_KEY,
            config.timeRange.coerceAtLeast(0)
        )
    }

    private fun isValidTaskTime(time: String?): Boolean {
        return !time.isNullOrBlank() && taskTimePattern.matches(time)
    }

    sealed class ImportResult {
        /** 导入成功，count 为成功导入的任务数量 */
        data class Success(val count: Int) : ImportResult()

        /** 导入失败，message 为错误信息 */
        data class Error(val message: String) : ImportResult()
    }
}
