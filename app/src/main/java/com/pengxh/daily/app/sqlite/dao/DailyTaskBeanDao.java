package com.pengxh.daily.app.sqlite.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean;

import java.util.List;

@Dao
public interface DailyTaskBeanDao {
    @Query("SELECT * FROM daily_task_table ORDER BY time ASC")
    List<DailyTaskBean> loadAll();

    @Update
    void update(DailyTaskBean bean);

    @Delete
    void delete(DailyTaskBean bean);

    @Query("SELECT COUNT(*) FROM daily_task_table WHERE time = :time")
    int queryTaskByTime(String time);

    @Insert
    void insert(DailyTaskBean bean);
}
