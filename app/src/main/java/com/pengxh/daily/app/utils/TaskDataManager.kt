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

    fun importTasks(json: String): ImportResult {
        return try {
            val type = object : TypeToken<ExportDataModel>() {}.type
            val config = gson.fromJson<ExportDataModel>(json, type)

            val importedTasks = mutableListOf<DailyTaskBean>()
            for (task in config.tasks) {
                // 跳过已存在的任务时间点
                if (!DatabaseWrapper.isTaskTimeExist(task.time)) {
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
        SaveKeyValues.putValue(Constant.MESSAGE_TITLE_KEY, config.messageTitle)

        // 保存企业微信 Key
        SaveKeyValues.putValue(Constant.WX_WEB_HOOK_KEY, config.wxKey)

        val email = config.emailConfig
        if (email != null) {
            DatabaseWrapper.insertConfig(email.outbox, email.authCode, email.inbox)
        }

        SaveKeyValues.putValue(Constant.GESTURE_DETECTOR_KEY, config.isDetectGesture)
        SaveKeyValues.putValue(Constant.BACK_TO_HOME_KEY, config.isBackToHome)
        SaveKeyValues.putValue(Constant.RESET_TIME_KEY, config.resetTime)
        SaveKeyValues.putValue(Constant.STAY_DD_TIMEOUT_KEY, config.overTime)
        SaveKeyValues.putValue(Constant.TASK_COMMAND_KEY, config.command)
        SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, config.isAutoStart)
        SaveKeyValues.putValue(Constant.RANDOM_TIME_KEY, config.isRandomTime)
        SaveKeyValues.putValue(Constant.RANDOM_MINUTE_RANGE_KEY, config.timeRange)
    }

    sealed class ImportResult {
        /** 导入成功，count 为成功导入的任务数量 */
        data class Success(val count: Int) : ImportResult()

        /** 导入失败，message 为错误信息 */
        data class Error(val message: String) : ImportResult()
    }
}