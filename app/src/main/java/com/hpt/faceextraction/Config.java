package com.hpt.faceextraction;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 运行配置（SharedPreferences 持久化）
 */
public final class Config {

    private static final String SP_NAME = "face_extraction_config";

    // 服务器地址，默认与消费机项目相同风格的本地服务器
    private static final String KEY_HTTP_HOST = "httpHost";
    // 设备机号（clientNos）
    private static final String KEY_DEVICE_NUMBER = "deviceNumber";
    // 同步任务轮询间隔（秒）
    private static final String KEY_HTTP_INTERVAL = "httpInterval";
    // 人脸分数阈值（0-100），低于该分数判定照片不合格
    private static final String KEY_FACE_SCORE = "faceScoreThreshold";
    // Web 配置端（端口 8090）登录密码
    private static final String KEY_WEB_PASSWORD = "webPassword";
    // 同步任务单次拉取条数（FaceSyncTasks 的 size 参数）
    private static final String KEY_SYNC_SIZE = "syncSize";
    // size 默认值迁移标记（旧版本默认 1000，新版本默认 200）
    private static final String KEY_SYNC_SIZE_MIGRATED = "syncSizeMigrated200";

    private static SharedPreferences sp() {
        return AppHolder.app().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static String getHttpHost() {
        String host = sp().getString(KEY_HTTP_HOST, "http://192.168.1.1:12345");
        // 规范化：保证以 / 结尾，避免拼接路径时出现 "12345hpt/v1/..." 之类的非法 URL
        if (host != null && !host.isEmpty() && !host.endsWith("/")) {
            host = host + "/";
        }
        return host == null ? "" : host;
    }

    public static void setHttpHost(String host) {
        String v = host == null ? "" : host.trim();
        if (!v.isEmpty() && !v.endsWith("/")) {
            v = v + "/";
        }
        sp().edit().putString(KEY_HTTP_HOST, v).apply();
    }

    /** 服务器根地址 + 相对路径，与参考项目一致：host + "/hpt/v2/" + path */
    public static String absoluteUrl(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String host = getHttpHost();
        if (host.isEmpty()) {
            return path;
        }
        if (path.startsWith("/")) {
            return host.substring(0, host.length() - 1) + path;
        }
        return host + path;
    }

    /**
     * 人脸照片地址 → 完整 URL。
     * 绝对路径（http开头）直接使用；以 / 开头拼到服务器根；相对路径默认在 hpt/v2/ 下（与参考项目一致）
     */
    public static String faceUrl(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String host = getHttpHost();
        if (host.isEmpty()) {
            return path;
        }
        if (path.startsWith("/")) {
            return host.substring(0, host.length() - 1) + path;
        }
        return host + "hpt/v2/" + path;
    }

    public static String getDeviceNumber() {
        return sp().getString(KEY_DEVICE_NUMBER, "0001");
    }

    public static void setDeviceNumber(String no) {
        sp().edit().putString(KEY_DEVICE_NUMBER,
                no == null || no.trim().isEmpty() ? "0001" : no.trim()).apply();
    }

    /** 心跳间隔（秒），同步任务由心跳触发，不再单独轮询。默认 15（与消费机一致） */
    public static int getHttpInterval() {
        return Math.max(5, sp().getInt(KEY_HTTP_INTERVAL, 15));
    }

    /** 同步任务单次拉取条数（size 参数），默认 200，最小 1；照片限制 1M 以内 */
    public static int getSyncSize() {
        SharedPreferences sp = sp();
        int val = sp.getInt(KEY_SYNC_SIZE, 200);
        // 一次性迁移：旧版本默认值为 1000，未手动修改过的设备自动降为新默认 200
        if (!sp.getBoolean(KEY_SYNC_SIZE_MIGRATED, false)) {
            sp.edit().putBoolean(KEY_SYNC_SIZE_MIGRATED, true).apply();
            if (val == 1000) {
                val = 200;
                sp.edit().putInt(KEY_SYNC_SIZE, val).apply();
            }
        }
        return Math.max(1, val);
    }

    public static void setSyncSize(int size) {
        sp().edit().putInt(KEY_SYNC_SIZE, Math.max(1, size)).apply();
    }

    public static void setHttpInterval(int seconds) {
        sp().edit().putInt(KEY_HTTP_INTERVAL, Math.max(5, seconds)).apply();
    }

    /** 人脸分数阈值（0-100），旷视 detectFaces 的 lmkScore*100 与之比较，默认 70 */
    public static int getFaceScoreThreshold() {
        return clamp(sp().getInt(KEY_FACE_SCORE, 70), 0, 100);
    }

    public static void setFaceScoreThreshold(int score) {
        sp().edit().putInt(KEY_FACE_SCORE, clamp(score, 0, 100)).apply();
    }

    /** Web 配置端登录密码，默认 123456 */
    public static String getWebPassword() {
        String v = sp().getString(KEY_WEB_PASSWORD, "123456");
        return v == null || v.isEmpty() ? "123456" : v;
    }

    public static void setWebPassword(String pwd) {
        String v = pwd == null ? "" : pwd.trim();
        if (v.isEmpty()) {
            v = "123456";
        }
        sp().edit().putString(KEY_WEB_PASSWORD, v).apply();
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
