package com.pengxh.daily.app.model;

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean;

import java.util.List;

import kotlin.Triple;

/**
 * 导出数据模型
 */
public class ExportDataModel {
    private int resetTime; // 重置时间
    private int overtime; // 超时时间
    private int timeRange; // 时间范围
    private int msgChannel; // 消息渠道
    private int targetApp; // 目标应用

    private String remoteCommand; // 口令
    private String msgTitle; // 打卡消息标题
    private String wxKey; // 企业微信消息Key

    private boolean detectGesture; // 检测手势
    private boolean backToHome; // 返回桌面
    private boolean autoRecycle; // 任务每日自动循环
    private boolean randomTime; // 随机时间
    private boolean skipHoliday; // 跳过节假日
    private boolean savePower; // 省电模式

    private Triple<String, String, String> emailConfig; // 邮箱配置<发件箱、授权码、收件箱>
    private List<DailyTaskBean> tasks; // 任务列表

    public int getResetTime() {
        return resetTime;
    }

    public void setResetTime(int resetTime) {
        this.resetTime = resetTime;
    }

    public int getOvertime() {
        return overtime;
    }

    public void setOvertime(int overtime) {
        this.overtime = overtime;
    }

    public int getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(int timeRange) {
        this.timeRange = timeRange;
    }

    public int getMsgChannel() {
        return msgChannel;
    }

    public int getTargetApp() {
        return targetApp;
    }

    public void setTargetApp(int targetApp) {
        this.targetApp = targetApp;
    }

    public void setMsgChannel(int msgChannel) {
        this.msgChannel = msgChannel;
    }

    public String getRemoteCommand() {
        return remoteCommand;
    }

    public void setRemoteCommand(String remoteCommand) {
        this.remoteCommand = remoteCommand;
    }

    public String getMsgTitle() {
        return msgTitle;
    }

    public void setMsgTitle(String msgTitle) {
        this.msgTitle = msgTitle;
    }

    public String getWxKey() {
        return wxKey;
    }

    public void setWxKey(String wxKey) {
        this.wxKey = wxKey;
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

    public boolean isAutoRecycle() {
        return autoRecycle;
    }

    public void setAutoRecycle(boolean autoRecycle) {
        this.autoRecycle = autoRecycle;
    }

    public boolean isRandomTime() {
        return randomTime;
    }

    public void setRandomTime(boolean randomTime) {
        this.randomTime = randomTime;
    }

    public boolean isSkipHoliday() {
        return skipHoliday;
    }

    public void setSkipHoliday(boolean skipHoliday) {
        this.skipHoliday = skipHoliday;
    }

    public boolean isSavePower() {
        return savePower;
    }

    public void setSavePower(boolean savePower) {
        this.savePower = savePower;
    }

    public Triple<String, String, String> getEmailConfig() {
        return emailConfig;
    }

    public void setEmailConfig(Triple<String, String, String> emailConfig) {
        this.emailConfig = emailConfig;
    }

    public List<DailyTaskBean> getTasks() {
        return tasks;
    }

    public void setTasks(List<DailyTaskBean> tasks) {
        this.tasks = tasks;
    }
}
