package com.pengxh.daily.app.sqlite.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.pengxh.daily.app.sqlite.bean.EmailConfigBean;

import java.util.List;

@Dao
public interface EmailConfigBeanDao {
    @Insert
    void insert(EmailConfigBean bean);

    @Update
    void update(EmailConfigBean bean);

    @Query("SELECT * FROM email_config_table ORDER BY createTime DESC LIMIT 1")
    EmailConfigBean loadEmailConfig();

    @Query("SELECT * FROM email_config_table")
    List<EmailConfigBean> loadAll();
}
