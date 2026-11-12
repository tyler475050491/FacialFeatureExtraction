package com.hpt.faceextraction;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 人脸同步任务（参考 GET/POST /hpt/v2/Accounts/FaceSyncTasks）：
 * 1. 定时 GET /hpt/v2/Accounts/FaceSyncTasks?clientNos={机号}&size=1000 获取照片地址（faceDataUrl，相对/绝对路径均可）
 * 2. 多线程下载照片（只在内存中处理，不落盘）并用旷视算法提取特征（特征不本地存储）
 * 3. POST /hpt/v2/Accounts/FaceSyncTasks 上报每个照片的提取状态（成功/失败 + 原因）
 * 4. 任务完成后写入 SQLite 历史记录
 */
public final class FaceSyncTask {

    private static final String TAG = "FaceSync";
    public static final String URL = "/hpt/v2/Accounts/FaceSyncTasks";
    /** 特征提取线程数（多线程提取） */
    private static final int WORKER_THREADS = 4;

    /** 提取结果上报状态码含义（与 FaceExtractor 对应）：
     *  0=成功  1=下载失败  2=未检测到人脸  3=照片不合格
     *  4=照片出现多个人脸  5=图片解码失败  6=照片地址为空  9=其他异常 */

    public static class TaskState {
        public boolean idle = true;      // 是否空闲
        public String taskTime = "";     // 处理时间（完成时间）
        public String taskId = "";       // 任务编号（服务端 TaskIdentifier，可为空）
        public int total;                // 照片数量
        public int done;                 // 已处理
        public int success;              // 成功数量
        public int fail;                 // 失败数量
        public long durationMs;          // 整体耗时（毫秒）
    }

    public interface Listener {
        /** 当前任务进度变化（任意线程回调，UI 侧自行切线程） */
        void onCurrentTaskChanged(TaskState state);

        /** 上一次任务完成 */
        void onLastTaskFinished(TaskState lastState);
    }

    private static volatile TaskState sCurrent = new TaskState();
    private static volatile TaskState sLast = null;
    private static Listener sListener;
    private static ExecutorService sWorkers;
    private static final AtomicInteger sRunning = new AtomicInteger(0);

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    public static void setListener(Listener l) {
        sListener = l;
    }

    public static TaskState getCurrentState() {
        return sCurrent;
    }

    public static TaskState getLastState() {
        return sLast;
    }

    /** 恢复上次任务状态（APP 重启后从历史库恢复，仅用于界面显示） */
    public static void restoreLastState(TaskState state) {
        if (state != null && sLast == null) {
            sLast = state;
            notifyLast();
        }
    }

    public static synchronized void start() {
        if (sWorkers != null) {
            return;
        }
        sWorkers = Executors.newFixedThreadPool(WORKER_THREADS);
        Log.i(TAG, "同步任务已启动（由心跳触发，不再定时轮询）");
    }

    public static synchronized void restart() {
        // 配置变更后无需重建，工作线程池保持；下次心跳返回同步标志时自动生效
        start();
    }

    /** 手动立即执行一次（供界面"立即同步"按钮使用） */
    public static void syncNow() {
        ensureWorkers();
        sWorkers.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    syncOnce();
                } catch (Throwable t) {
                    Log.e(TAG, "syncNow error", t);
                }
            }
        });
    }

    /**
     * 心跳返回"需要同步人脸"时触发：
     * - 空闲 → 立即开始处理
     * - 正在处理任务 → 静默跳过，等下一次心跳再判断
     */
    public static void syncIfIdle() {
        if (sRunning.get() > 0) {
            Log.i(TAG, "心跳请求同步，但当前任务正在处理中，等待本次任务完成后的下一次心跳");
            return;
        }
        ensureWorkers();
        sWorkers.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    syncOnce();
                } catch (Throwable t) {
                    Log.e(TAG, "syncIfIdle error", t);
                }
            }
        });
    }

    private static synchronized void ensureWorkers() {
        if (sWorkers == null) {
            sWorkers = Executors.newFixedThreadPool(WORKER_THREADS);
        }
    }

    // ------------------------------------------------------------------ //

    /** 一条照片任务 */
    private static class FaceEntry {
        String clientID = "";
        String accountID = "";
        String faceDataUrl = "";
    }

    private static void syncOnce() {
        // 上一次任务没处理完，跳过本轮（与参考项目一致）
        if (sRunning.get() > 0) {
            Log.d(TAG, "上一次任务还没处理完，跳过");
            return;
        }
        String url = Config.absoluteUrl(URL)
                + "?clientNos=" + Config.getDeviceNumber() + "&size=" + Config.getSyncSize();
        byte[] respBytes = HttpUtil.get(url);
        String body = respBytes == null ? null : new String(respBytes);
        if (body == null || body.isEmpty()) {
            return;
        }
        List<FaceEntry> entries = parseResponse(body);
        if (entries.isEmpty()) {
            return;
        }
        // 服务端可选的任务编号（响应不带时为空串，历史记录留空显示 "-"）
        String taskId = parseTaskIdentifier(body);
        Log.i(TAG, "获取到 " + entries.size() + " 张照片待提取"
                + (TextUtils.isEmpty(taskId) ? "" : ", 任务编号: " + taskId));
        runTask(entries, taskId);
    }

    /**
     * 解析 GET 响应，兼容多种格式：
     * 1. 完整格式 {"models":[{"clientID":1,"accountInfos":[{"accountID":"x","faceDataUrl":"a.jpg"}]}]}
     * 2. 简化格式 {"models":[{"faceDataUrl":"a.jpg"}]} / {"data":[{"faceDataUrl":"a.jpg"}]}
     * 3. 纯数组 [{"faceDataUrl":"a.jpg"}] / ["a.jpg"]
     */
    static List<FaceEntry> parseResponse(String body) {
        List<FaceEntry> entries = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(body);
            String clientID = findClientId(root);
            collectEntries(root, clientID, entries, 0);
        } catch (Throwable t) {
            Log.e(TAG, "解析同步任务响应失败: " + body, t);
        }
        return entries;
    }

    /**
     * 提取响应中的任务编号 TaskIdentifier（可选字段，兼容大小写/任意层级）。
     * 响应不带该参数时返回空串。
     */
    static String parseTaskIdentifier(String body) {
        try {
            return findTaskId(JsonParser.parseString(body));
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String findTaskId(JsonElement e) {
        try {
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                for (java.util.Map.Entry<String, JsonElement> en : o.entrySet()) {
                    if ("taskidentifier".equalsIgnoreCase(en.getKey())
                            && en.getValue() != null && !en.getValue().isJsonNull()) {
                        String v = en.getValue().getAsString();
                        if (!TextUtils.isEmpty(v)) {
                            return v;
                        }
                    }
                }
                for (java.util.Map.Entry<String, JsonElement> en : o.entrySet()) {
                    String id = findTaskId(en.getValue());
                    if (!TextUtils.isEmpty(id)) {
                        return id;
                    }
                }
            } else if (e.isJsonArray()) {
                for (JsonElement child : e.getAsJsonArray()) {
                    String id = findTaskId(child);
                    if (!TextUtils.isEmpty(id)) {
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String findClientId(JsonElement e) {        try {
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("clientID") && !o.get("clientID").isJsonNull()) {
                    return o.get("clientID").getAsString();
                }
                for (java.util.Map.Entry<String, JsonElement> en : o.entrySet()) {
                    String id = findClientId(en.getValue());
                    if (!TextUtils.isEmpty(id)) {
                        return id;
                    }
                }
            } else if (e.isJsonArray()) {
                for (JsonElement child : e.getAsJsonArray()) {
                    String id = findClientId(child);
                    if (!TextUtils.isEmpty(id)) {
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void collectEntries(JsonElement e, String clientID,
                                       List<FaceEntry> out, int depth) {
        if (depth > 6) {
            return;
        }
        try {
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                String cid = clientID;
                if (o.has("clientID") && !o.get("clientID").isJsonNull()) {
                    cid = o.get("clientID").getAsString();
                }
                if (o.has("faceDataUrl") && !o.get("faceDataUrl").isJsonNull()) {
                    FaceEntry entry = new FaceEntry();
                    entry.clientID = cid;
                    entry.accountID = o.has("accountID") && !o.get("accountID").isJsonNull()
                            ? o.get("accountID").getAsString() : "";
                    entry.faceDataUrl = o.get("faceDataUrl").getAsString();
                    out.add(entry);
                    return;
                }
                if (o.has("url") && !o.get("url").isJsonNull()) {
                    FaceEntry entry = new FaceEntry();
                    entry.clientID = cid;
                    entry.accountID = o.has("accountID") && !o.get("accountID").isJsonNull()
                            ? o.get("accountID").getAsString() : "";
                    entry.faceDataUrl = o.get("url").getAsString();
                    out.add(entry);
                    return;
                }
                for (java.util.Map.Entry<String, JsonElement> en : o.entrySet()) {
                    collectEntries(en.getValue(), cid, out, depth + 1);
                }
            } else if (e.isJsonArray()) {
                JsonArray arr = e.getAsJsonArray();
                if (!arr.isEmpty() && arr.get(0).isJsonPrimitive()) {
                    // ["a.jpg","b.jpg"] 纯字符串数组
                    for (JsonElement child : arr) {
                        FaceEntry entry = new FaceEntry();
                        entry.clientID = clientID;
                        entry.faceDataUrl = child.getAsString();
                        out.add(entry);
                    }
                    return;
                }
                for (JsonElement child : arr) {
                    collectEntries(child, clientID, out, depth + 1);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "遍历响应节点失败", t);
        }
    }

    // ------------------------------------------------------------------ //

    private static void runTask(List<FaceEntry> entries, String taskId) {
        if (!sRunning.compareAndSet(0, 1)) {
            return;
        }
        final long taskStartMillis = System.currentTimeMillis();
        TaskState current = new TaskState();
        current.idle = false;
        current.taskId = taskId == null ? "" : taskId;
        current.total = entries.size();
        sCurrent = current;
        notifyCurrent();

        ExecutorService workers = sWorkers != null ? sWorkers
                : Executors.newFixedThreadPool(WORKER_THREADS);
        final CountDownLatch latch = new CountDownLatch(entries.size());
        final List<JsonObject> results = java.util.Collections
                .synchronizedList(new ArrayList<JsonObject>());

        String clientID = entries.get(0).clientID;
        doneCount.set(0);
        okCount.set(0);
        for (final FaceEntry entry : entries) {
            final String cid = TextUtils.isEmpty(entry.clientID) ? clientID : entry.clientID;
            try {
                workers.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            results.add(processOne(entry, cid));
                        } catch (Throwable t) {
                            Log.e(TAG, "处理照片异常", t);
                            doneCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                            // 进度回调
                            TaskState snapshot = new TaskState();
                            snapshot.idle = false;
                            snapshot.taskId = current.taskId;
                            snapshot.total = current.total;
                            snapshot.done = doneCount.get();
                            snapshot.success = okCount.get();
                            snapshot.fail = snapshot.done - snapshot.success;
                            sCurrent = snapshot;
                            notifyCurrent();
                        }
                    }
                });
            } catch (Throwable t) {
                // 线程池已关闭等异常，直接按失败计数
                doneCount.incrementAndGet();
                latch.countDown();
            }
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 上报结果（带任务编号回传）
        uploadResults(clientID, current.taskId, results);

        // 完成状态
        TaskState last = new TaskState();
        last.idle = false;
        last.taskTime = FMT.format(new Date());
        last.taskId = current.taskId;
        last.total = entries.size();
        last.done = last.total;
        last.success = okCount.get();
        last.fail = last.total - last.success;
        last.durationMs = System.currentTimeMillis() - taskStartMillis;
        sLast = last;
        sCurrent = last;
        notifyCurrent();
        notifyLast();

        // 写入历史记录
        TaskHistoryDb.insert(System.currentTimeMillis(), last.total, last.success,
                last.fail, last.durationMs, last.taskId);

        sRunning.set(0);
        Log.i(TAG, "任务完成: 共 " + last.total + " 张, 成功 " + last.success
                + ", 失败 " + last.fail + ", 耗时 "
                + TaskHistoryDb.formatDuration(last.durationMs));
    }

    private static final AtomicInteger doneCount = new AtomicInteger(0);
    private static final AtomicInteger okCount = new AtomicInteger(0);

    /** 处理单张照片并生成上报 JSON */
    private static JsonObject processOne(FaceEntry entry, String clientID) {
        FaceExtractor.Result r = FaceExtractor.extract(entry.faceDataUrl);
        boolean ok = r.status == FaceExtractor.ST_SUCCESS;
        if (ok) {
            okCount.incrementAndGet();
        }
        doneCount.incrementAndGet();

        JsonObject item = new JsonObject();
        if (!TextUtils.isEmpty(entry.accountID)) {
            item.addProperty("accountID", entry.accountID);
        }
        item.addProperty("clientID", clientID);
        item.addProperty("result", ok ? "true" : "false");
        item.addProperty("status", r.status);
        item.addProperty("message", r.message == null ? "" : r.message);
        item.addProperty("updateTime", FMT.format(new Date()));
        if (ok && !TextUtils.isEmpty(r.featureBase64)) {
            // 特征值随结果上报，供服务端复用（本地不存储）
            item.addProperty("faceFeature", r.featureBase64);
            // 特征值的 MD5（对上传的 Base64 串计算，32 位小写十六进制，服务端可校验完整性）
            item.addProperty("featureMD5", md5Hex(r.featureBase64));
        }
        if (r.bitmap != null) {
            r.bitmap.recycle();
        }
        return item;
    }

    /** 批量上报提取结果（body 结构与参考项目 FaceLoadResultBean 兼容，带 TaskIdentifier 回传） */
    private static void uploadResults(String clientID, String taskId, List<JsonObject> results) {
        if (results.isEmpty()) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("clientID", clientID);
        if (!TextUtils.isEmpty(taskId)) {
            root.addProperty("TaskIdentifier", taskId);
        }
        // 算法版本号（整批相同，如 "KS V1.3.3.1"）
        root.addProperty("algoVersion", App.algorithmVersion());
        root.addProperty("result", "true");
        JsonArray models = new JsonArray();
        synchronized (results) {
            for (JsonObject o : results) {
                models.add(o);
            }
        }
        root.add("models", models);
        String resp = HttpUtil.postJson(Config.absoluteUrl(URL), GsonHolder.GSON.toJson(root));
        Log.i(TAG, "上报提取结果: " + results.size() + " 条, 响应: "
                + (resp == null ? "无" : resp));
    }

    /** 字符串 MD5，32 位小写十六进制；异常时返回空串（则不上报该字段也无妨） */
    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "MD5 计算失败: " + t.getMessage());
            return "";
        }
    }

    private static void notifyCurrent() {
        Listener l = sListener;
        if (l != null) {
            l.onCurrentTaskChanged(sCurrent);
        }
    }

    private static void notifyLast() {
        Listener l = sListener;
        if (l != null) {
            l.onLastTaskFinished(sLast);
        }
    }
}
