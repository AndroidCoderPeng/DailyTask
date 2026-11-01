package com.pengxh.daily.app.vm

import androidx.lifecycle.ViewModel
import com.pengxh.daily.app.events.MainEvent
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.utils.TaskListState
import com.pengxh.kt.lite.extensions.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    private val taskDataManager by lazy { TaskDataManager() }

    private val _taskListState = MutableStateFlow<TaskListState>(TaskListState.Initial)
    val taskListState: StateFlow<TaskListState> = _taskListState.asStateFlow()

    /**
     * 一次性事件通道
     *
     * 用 Channel 而不是 SharedFlow，保证事件不丢失、且消费一次后不再重复触发
     * */
    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        reload()
    }

    /**
     * 重新加载任务列表
     * */
    fun reload() = launch(onError = { logError("加载任务列表失败", it) }) {
        _taskListState.value = TaskListState.Loaded(loadTasks())
    }

    /**
     * 新增任务
     * */
    fun addTask(time: String) = launch(onError = { logError("新增任务失败", it) }) {
        if (withContext(Dispatchers.IO) { DatabaseWrapper.isTaskTimeExist(time) }) {
            _events.send(MainEvent.TaskTimeExists)
            return@launch
        }

        val bean = DailyTaskBean().apply {
            this.time = time
        }
        withContext(Dispatchers.IO) {
            DatabaseWrapper.insert(bean)
        }

        _taskListState.value = TaskListState.Loaded(loadTasks())
        _events.send(MainEvent.TaskAdded)
    }

    /**
     * 修改任务时间
     * */
    fun updateTaskTime(bean: DailyTaskBean, time: String) =
        launch(onError = { logError("修改任务失败", it) }) {
            bean.time = time
            withContext(Dispatchers.IO) {
                DatabaseWrapper.updateTask(bean)
            }

            _taskListState.value = TaskListState.Loaded(loadTasks())
        }

    /**
     * 删除任务
     * */
    fun deleteTask(bean: DailyTaskBean) = launch(onError = { logError("删除任务失败", it) }) {
        withContext(Dispatchers.IO) {
            DatabaseWrapper.deleteTask(bean)
        }

        _taskListState.value = TaskListState.Loaded(loadTasks())
    }

    /**
     * 导入任务
     * */
    fun importTasks(json: String) = launch(onError = { logError("导入任务失败", it) }) {
        when (val result = withContext(Dispatchers.IO) { taskDataManager.importTasks(json) }) {
            is TaskDataManager.ImportResult.Success -> {
                if (result.count > 0) {
                    _taskListState.value = TaskListState.Loaded(loadTasks())
                }
                _events.send(MainEvent.ImportSuccess(result.count, result.warnings))
            }

            is TaskDataManager.ImportResult.Error -> {
                _events.send(MainEvent.ImportError(result.message))
            }
        }
    }

    /**
     * 是否存在任务（用于启动任务前的校验）
     * */
    suspend fun hasTasks(): Boolean = withContext(Dispatchers.IO) {
        DatabaseWrapper.loadAllTask().isNotEmpty()
    }

    private suspend fun loadTasks(): List<DailyTaskBean> = withContext(Dispatchers.IO) {
        DatabaseWrapper.loadAllTask()
    }

    private fun logError(action: String, e: Throwable) {
        LogFileManager.writeLog("$action: ${e.message}")
    }
}
