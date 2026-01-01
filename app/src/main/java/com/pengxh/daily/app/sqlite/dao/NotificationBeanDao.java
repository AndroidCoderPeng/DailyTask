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

    @Query("SELECT * FROM notice_record_table ORDER BY postTime DESC LIMIT :pageSize OFFSET :offset")
    List<NotificationBean> loadNoticeByTime(int pageSize, int offset);

    @Query("SELECT * FROM notice_record_table WHERE postTime LIKE :date || '%'")
    List<NotificationBean> loadCurrentDayNotice(String date);

    @Insert
    void insert(NotificationBean bean);
}
