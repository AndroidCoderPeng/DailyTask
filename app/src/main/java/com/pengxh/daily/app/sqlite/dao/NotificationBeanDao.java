package com.pengxh.daily.app.sqlite.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.pengxh.daily.app.sqlite.bean.NotificationBean;

import java.util.List;

@Dao
public interface NotificationBeanDao {
    @Query("DELETE FROM notice_record_table")
    void deleteAll();

    @Query("SELECT * FROM notice_record_table WHERE date(postTime) >= date(:startDate) AND date(postTime) <= date(:endDate) ORDER BY postTime DESC")
    List<NotificationBean> loadWeeklyNotice(String startDate, String endDate);

    @Query("SELECT * FROM notice_record_table WHERE postTime LIKE :date || '%'")
    List<NotificationBean> loadCurrentDayNotice(String date);

    @Insert
    void insert(NotificationBean bean);
}
