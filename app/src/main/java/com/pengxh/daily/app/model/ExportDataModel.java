package com.pengxh.daily.app.model;

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean;
import com.pengxh.daily.app.sqlite.bean.EmailConfigBean;

import java.util.List;

/**
 * 导出数据模型
 */
public class ExportDataModel {
    private List<DailyTaskBean> tasks; // 任务列表
    private String messageTitle; // 打卡消息标题
    private String wxKey; // 企业微信消息Key
    private EmailConfigBean emailConfig; // 邮箱配置
    private boolean detectGesture; // 检测手势
    private boolean backToHome; // 返回桌面
    private int resetTime; // 重置时间
    private int overTime; // 超时时间
    private String command; // 口令
    private boolean autoStart; // 自动启动
    private boolean randomTime; // 随机时间
    private boolean skipChinaHoliday; // 跳过中国节假日
    private boolean powerSaveMode; // 省电模式
    private Boolean lowBatteryReminder; // 低电量提醒
    private int timeRange; // 时间范围

    public List<DailyTaskBean> getTasks() {
        return tasks;
    }

    public void setTasks(List<DailyTaskBean> tasks) {
        this.tasks = tasks;
    }

    public String getMessageTitle() {
        return messageTitle;
    }

    public void setMessageTitle(String messageTitle) {
        this.messageTitle = messageTitle;
    }

    public String getWxKey() {
        return wxKey;
    }

    public void setWxKey(String wxKey) {
        this.wxKey = wxKey;
    }

    public EmailConfigBean getEmailConfig() {
        return emailConfig;
    }

    public void setEmailConfig(EmailConfigBean emailConfig) {
        this.emailConfig = emailConfig;
    }

    public boolean isDetectGesture() {
        return detectGesture;
    }

    public void setDetectGesture(boolean detectGesture) {
        this.detectGesture = detectGesture;
    }

    public boolean isBackToHome() {
        return backToHome;
    }

    public void setBackToHome(boolean backToHome) {
        this.backToHome = backToHome;
    }

    public int getResetTime() {
        return resetTime;
    }

    public void setResetTime(int resetTime) {
        this.resetTime = resetTime;
    }

    public int getOverTime() {
        return overTime;
    }

    public void setOverTime(int overTime) {
        this.overTime = overTime;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public boolean isRandomTime() {
        return randomTime;
    }

    public void setRandomTime(boolean randomTime) {
        this.randomTime = randomTime;
    }

    public boolean isSkipChinaHoliday() {
        return skipChinaHoliday;
    }

    public void setSkipChinaHoliday(boolean skipChinaHoliday) {
        this.skipChinaHoliday = skipChinaHoliday;
    }

    public boolean isPowerSaveMode() {
        return powerSaveMode;
    }

    public void setPowerSaveMode(boolean powerSaveMode) {
        this.powerSaveMode = powerSaveMode;
    }

    public Boolean getLowBatteryReminder() {
        return lowBatteryReminder;
    }

    public void setLowBatteryReminder(Boolean lowBatteryReminder) {
        this.lowBatteryReminder = lowBatteryReminder;
    }

    public int getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(int timeRange) {
        this.timeRange = timeRange;
    }
}
