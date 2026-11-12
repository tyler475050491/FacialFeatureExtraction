package com.hpt.faceextraction;

import android.os.SystemClock;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 设备心跳（参考 POST /hpt/v1/CurrentTime）：
 * - 定时上报，服务器返回 {"Status":"100","Msg":"","Time":"202109281205422"}
 * - Status=100：服务器接收并允许连接 → 设备在线
 * - Time 为服务器时间（yyyyMMddHHmmss + 1 位校验位），设备时间偏差超过 10 秒时校准
 * - 响应携带同步标志（UploadImage 等）：为"是"时立即触发人脸同步任务；
 *   为"否"时静默等待；若同步任务正在处理中，则等本次任务完成后的下一次心跳再判断
 */
public final class TimeSyncTask {

    private static final String TAG = "TimeSync";
    public static final String URL = "hpt/v1/CurrentTime";

    /** 在线状态监听（MainActivity 注册） */
    public interface Listener {
        void onOnlineChanged(boolean online);

        void onTimeSynced();
    }

    private static volatile boolean sOnline = false;
    /** 服务器与设备的时钟偏差（服务器 - 本地），校时失败时用于显示修正时间 */
    private static volatile long sServerOffset = 0;
    private static volatile long sLastHeartbeatTime = 0;
    private static Listener sListener;
    private static ScheduledExecutorService sExecutor;

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

    public static void setListener(Listener l) {
        sListener = l;
    }

    public static boolean isOnline() {
        return sOnline;
    }

    public static long getLastHeartbeatTime() {
        return sLastHeartbeatTime;
    }

    /** 校时失败时，用偏差修正后的当前时间（毫秒） */
    public static long correctedNowMillis() {
        return System.currentTimeMillis() + sServerOffset;
    }

    public static synchronized void start() {
        if (sExecutor != null) {
            return;
        }
        sExecutor = Executors.newSingleThreadScheduledExecutor();
        long period = Config.getHttpInterval();
        sExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    heartbeat();
                } catch (Throwable t) {
                    Log.e(TAG, "heartbeat error", t);
                }
            }
        }, 2, period, TimeUnit.SECONDS);
        Log.i(TAG, "心跳已启动, 每 " + period + " 秒一次");
    }

    public static synchronized void stop() {
        if (sExecutor != null) {
            sExecutor.shutdownNow();
            sExecutor = null;
        }
    }

    /** 配置（心跳间隔等）变更后重启心跳 */
    public static synchronized void restart() {
        stop();
        start();
    }

    private static void heartbeat() {
        // 请求体与参考项目一致
        Map<String, Object> params = new HashMap<>();
        params.put("ICCID", "");
        params.put("ICCID2", "");
        String body = HttpUtil.postJson(Config.absoluteUrl(URL), GsonHolder.GSON.toJson(params));
        sLastHeartbeatTime = System.currentTimeMillis();
        if (body == null) {
            setOnline(false);
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String status = json.has("Status") && !json.get("Status").isJsonNull()
                    ? json.get("Status").getAsString() : "";
            if (!"100".equals(status)) {
                Log.w(TAG, "心跳被拒绝 Status=" + status
                        + " Msg=" + (json.has("Msg") ? json.get("Msg") : ""));
                setOnline(false);
                return;
            }
            setOnline(true);
            // 是否需要同步人脸：服务端返回"是"则立即触发同步任务
            if (isSyncFaceRequested(json)) {
                Log.i(TAG, "心跳返回需要同步人脸，触发同步任务");
                FaceSyncTask.syncIfIdle();
            }
            String time = json.has("Time") && !json.get("Time").isJsonNull()
                    ? json.get("Time").getAsString() : "";
            applyServerTime(time);
        } catch (Throwable t) {
            Log.e(TAG, "解析心跳响应失败: " + body, t);
            setOnline(false);
        }
    }

    /** 解析服务器时间并校准设备时间（格式：yyyyMMddHHmmss + 1 位校验位，共 15 位） */
    private static void applyServerTime(String time) {
        if (time == null || time.length() < 14) {
            return;
        }
        try {
            TIME_FMT.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            long serverMillis;
            if (time.length() > 14) {
                serverMillis = TIME_FMT.parse(time.substring(0, 14)).getTime();
            } else {
                serverMillis = TIME_FMT.parse(time).getTime();
            }
            if (serverMillis <= 0) {
                return;
            }
            sServerOffset = serverMillis - System.currentTimeMillis();
            if (Math.abs(sServerOffset) > 10_000) {
                try {
                    boolean ok = SystemClock.setCurrentTimeMillis(serverMillis);
                    Log.i(TAG, "设备时间偏差 " + sServerOffset + "ms, 校时结果=" + ok);
                    if (ok) {
                        sServerOffset = 0;
                    }
                } catch (Throwable t) {
                    // 无系统权限时仅记录偏差，界面显示修正后的时间
                    Log.w(TAG, "校时无权限，使用时间偏差修正: " + t.getMessage());
                }
            }
            if (sListener != null) {
                sListener.onTimeSynced();
            }
        } catch (Throwable t) {
            Log.e(TAG, "解析服务器时间失败: " + time, t);
        }
    }

    /**
     * 解析心跳响应中的"是否同步人脸"标志。
     * 主字段与消费机协议一致：UploadImage；同时兼容 SyncFace / IsSyncFace / NeedSyncFace 等候选字段。
     * 接受布尔 true 或字符串 "true"/"1"/"是"/"yes"
     */
    private static boolean isSyncFaceRequested(JsonObject json) {
        String[] keys = {"UploadImage", "SyncFace", "IsSyncFace", "NeedSyncFace",
                "SyncFaceTask", "FaceSync"};
        for (String k : keys) {
            if (!json.has(k) || json.get(k).isJsonNull()) {
                continue;
            }
            try {
                JsonElement e = json.get(k);
                if (e.isJsonPrimitive()) {
                    if (e.getAsJsonPrimitive().isBoolean()) {
                        if (e.getAsBoolean()) {
                            return true;
                        }
                    } else {
                        String v = e.getAsString().trim();
                        if (v.equalsIgnoreCase("true") || v.equals("1")
                                || v.equals("是") || v.equalsIgnoreCase("yes")) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void setOnline(boolean online) {
        if (sOnline != online) {
            Log.i(TAG, "设备在线状态变化: " + online);
        }
        sOnline = online;
        if (sListener != null) {
            sListener.onOnlineChanged(online);
        }
    }
}
