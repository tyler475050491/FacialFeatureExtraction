package com.hpt.faceextraction;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面：
 * 顶部：实时时间 / SN / 机号 / 算法版本 / APP版本 / IP / 在线状态（绿红）/ 网络图标（以太网、WiFi 自动切换）
 * 中部：当前任务状态、上次任务状态
 * 底部：历史任务列表（序号、处理时间、照片数量、成功数量、失败数量），支持时间范围查询
 */
public class MainActivity extends AppCompatActivity implements
        TimeSyncTask.Listener, FaceSyncTask.Listener, TaskHistoryDb.Listener {

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView tvTime, tvSn, tvDeviceNo, tvAlgoVersion, tvAppVersion, tvIp, tvMac;
    private View vOnlineDot;
    private ImageView ivNetwork, ivNetX;
    private TextView tvCurrentTask, tvLastTask;
    private TextView tvDeviceStatus;
    private ListView lvHistory;
    private EditText etStartTime, etEndTime, etTaskId;
    private SimpleAdapter mHistoryAdapter;
    private final List<Map<String, Object>> mHistoryData = new ArrayList<>();

    // CPU 占用率计算：两次 /proc/stat 采样差值
    private long mLastCpuTotal = 0;
    private long mLastCpuIdle = 0;

    private final Runnable mClockTick = new Runnable() {
        @Override
        public void run() {
            long now = TimeSyncTask.correctedNowMillis();
            tvTime.setText(TIME_FMT.format(new Date(now)));
            refreshNetIcon();
            mHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable mDeviceStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshDeviceStatus();
            mHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTime = findViewById(R.id.tvTime);
        tvSn = findViewById(R.id.tvSn);
        tvDeviceNo = findViewById(R.id.tvDeviceNo);
        tvAlgoVersion = findViewById(R.id.tvAlgoVersion);
        tvAppVersion = findViewById(R.id.tvAppVersion);
        tvIp = findViewById(R.id.tvIp);
        tvMac = findViewById(R.id.tvMac);
        vOnlineDot = findViewById(R.id.vOnlineDot);
        ivNetwork = findViewById(R.id.ivNetwork);
        ivNetX = findViewById(R.id.ivNetX);
        tvCurrentTask = findViewById(R.id.tvCurrentTask);
        tvLastTask = findViewById(R.id.tvLastTask);
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus);
        lvHistory = findViewById(R.id.lvHistory);
        etStartTime = findViewById(R.id.etStartTime);
        etEndTime = findViewById(R.id.etEndTime);
        etTaskId = findViewById(R.id.etTaskId);
        View btnQuery = findViewById(R.id.btnQuery);
        View btnRefresh = findViewById(R.id.btnRefresh);
        View btnClear = findViewById(R.id.btnClear);
        View btnSyncNow = findViewById(R.id.btnSyncNow);
        View btnSettings = findViewById(R.id.btnSettings);

        // 顶部信息
        tvSn.setText("SN: " + Constants.getSN());
        tvDeviceNo.setText("机号: " + Config.getDeviceNumber());
        tvAlgoVersion.setText("算法: " + App.algorithmVersion());
        tvAppVersion.setText("版本: " + appVersion());

        // 历史列表（隔行换色便于阅读：奇数行浅蓝灰、偶数行白色）
        mHistoryAdapter = new SimpleAdapter(this, mHistoryData,
                R.layout.item_task_history,
                new String[]{"no", "taskId", "time", "total", "success", "fail", "duration"},
                new int[]{R.id.tvNo, R.id.tvTaskId, R.id.tvTime, R.id.tvTotal,
                        R.id.tvSuccess, R.id.tvFail, R.id.tvDuration}) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                v.setBackgroundColor(position % 2 == 0 ? 0xFFF5F8FB : 0xFFFFFFFF);
                return v;
            }
        };
        lvHistory.setAdapter(mHistoryAdapter);

        // 默认时间范围：当天 00:00:00 ~ 23:59:59
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        etStartTime.setText(DATE_FMT.format(cal.getTime()));
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        etEndTime.setText(DATE_FMT.format(cal.getTime()));

        etStartTime.setOnClickListener(v -> pickDate(true));
        etEndTime.setOnClickListener(v -> pickDate(false));
        btnQuery.setOnClickListener(v -> queryHistory());
        btnRefresh.setOnClickListener(v -> refreshHistory());
        btnClear.setOnClickListener(v -> confirmClearHistory());
        btnSyncNow.setOnClickListener(v -> {
            Toast.makeText(this, "已触发同步", Toast.LENGTH_SHORT).show();
            FaceSyncTask.syncNow();
        });
        btnSettings.setOnClickListener(v -> showSettings());

        // 监听
        TimeSyncTask.setListener(this);
        FaceSyncTask.setListener(this);
        TaskHistoryDb.setListener(this);

        // 启动服务
        TimeSyncTask.start();
        FaceSyncTask.start();

        // 初始刷新（默认显示最新 50 笔记录）
        refreshStatusViews();
        refreshHistory();
        restoreLastTaskFromDb();
        mHandler.post(mClockTick);
        mHandler.post(mDeviceStatusTick);
    }

    /** APP 重启后从历史库恢复最近一次任务状态（仅显示用） */
    private void restoreLastTaskFromDb() {
        try {
            List<TaskHistoryDb.Record> records = TaskHistoryDb.get().queryRecent(1);
            if (!records.isEmpty() && FaceSyncTask.getLastState() == null) {
                TaskHistoryDb.Record r = records.get(0);
                FaceSyncTask.TaskState s = new FaceSyncTask.TaskState();
                s.idle = false;
                s.taskTime = r.taskTime;
                s.taskId = r.taskId;
                s.total = r.photoCount;
                s.done = r.photoCount;
                s.success = r.successCount;
                s.fail = r.failCount;
                s.durationMs = r.durationMs;
                FaceSyncTask.restoreLastState(s);
            }
        } catch (Throwable ignored) {
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        TimeSyncTask.setListener(null);
        FaceSyncTask.setListener(null);
        TaskHistoryDb.setListener(null);
    }

    // ------------------- 时钟与图标 ------------------- //

    private void refreshNetIcon() {
        NetMonitor.Mode mode = NetMonitor.currentMode();
        String modeName;
        switch (mode) {
            case ETHERNET:
                ivNetwork.setImageResource(R.drawable.ic_ethernet);
                ivNetwork.setColorFilter(0xFF4CAF50);
                ivNetX.setVisibility(View.GONE);
                modeName = "以太网";
                break;
            case WIFI:
                ivNetwork.setImageResource(R.drawable.ic_wifi);
                ivNetwork.setColorFilter(0xFF4CAF50);
                ivNetX.setVisibility(View.GONE);
                modeName = "WiFi";
                break;
            case NONE:
            default:
                // 连接不到任何网络：以太网图标 + 红色 + 打 X
                ivNetwork.setImageResource(R.drawable.ic_ethernet);
                ivNetwork.setColorFilter(0xFFF44336);
                ivNetX.setVisibility(View.VISIBLE);
                ivNetX.setColorFilter(0xFFF44336);
                modeName = null;
                break;
        }
        tvIp.setText("IP: " + NetMonitor.getIpAddress());
        // MAC 跟随当前联网方式：以太网取 ethX，WiFi 取 wlanX
        String mac = Constants.getActiveMac();
        tvMac.setText(modeName == null
                ? "MAC: " + mac
                : "MAC: " + mac + " (" + modeName + ")");
        vOnlineDot.setBackgroundResource(TimeSyncTask.isOnline()
                ? R.drawable.dot_green : R.drawable.dot_red);
    }

    // ------------------- 心跳状态 ------------------- //

    @Override
    public void onOnlineChanged(final boolean online) {
        mHandler.post(this::refreshStatusViews);
    }

    @Override
    public void onTimeSynced() {
        mHandler.post(this::refreshStatusViews);
    }

    // ------------------- 任务状态 ------------------- //

    @Override
    public void onCurrentTaskChanged(final FaceSyncTask.TaskState state) {
        mHandler.post(this::refreshStatusViews);
    }

    @Override
    public void onLastTaskFinished(final FaceSyncTask.TaskState lastState) {
        mHandler.post(this::refreshStatusViews);
    }

    private void refreshStatusViews() {
        FaceSyncTask.TaskState cur = FaceSyncTask.getCurrentState();
        if (cur == null || cur.idle) {
            tvCurrentTask.setText("当前任务状态：空闲");
        } else {
            int progress = cur.total == 0 ? 0 : cur.done * 100 / cur.total;
            tvCurrentTask.setText("当前任务状态：照片数量 " + cur.total
                    + "，处理进度 " + cur.done + "/" + cur.total + " (" + progress + "%)"
                    + "，成功 " + cur.success + "，失败 " + cur.fail);
        }
        FaceSyncTask.TaskState last = FaceSyncTask.getLastState();
        if (last == null || TextUtils.isEmpty(last.taskTime)) {
            tvLastTask.setText("上次任务状态：暂无");
        } else {
            tvLastTask.setText("上次任务状态：处理时间 " + last.taskTime
                    + "，照片数量 " + last.total
                    + "，成功 " + last.success + "，失败 " + last.fail
                    + "，耗时 " + TaskHistoryDb.formatDuration(last.durationMs));
        }
        refreshNetIcon();
    }

    // ------------------- 设备资源状态（CPU/内存/存储） ------------------- //

    private void refreshDeviceStatus() {
        StringBuilder sb = new StringBuilder();
        // CPU 占用率（/proc/stat 两次采样差值）
        float cpu = readCpuUsage();
        sb.append("CPU: ").append(cpu < 0 ? "--" : String.format(Locale.US, "%.1f", cpu))
                .append("%，");
        // 内存占用
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(mi);
            float usedPercent = mi.totalMem > 0
                    ? (mi.totalMem - mi.availMem) * 100f / mi.totalMem : -1;
            sb.append("内存: ")
                    .append(usedPercent < 0 ? "--"
                            : String.format(Locale.US, "%.1f", usedPercent))
                    .append("%（已用 ").append(formatBytes(mi.totalMem - mi.availMem))
                    .append("/").append(formatBytes(mi.totalMem)).append("），");
        }
        // 存储剩余（data 分区）
        try {
            android.os.StatFs stat = new android.os.StatFs(
                    android.os.Environment.getDataDirectory().getPath());
            long total = stat.getTotalBytes();
            long avail = stat.getAvailableBytes();
            sb.append("存储: 剩余 ").append(formatBytes(avail))
                    .append("/").append(formatBytes(total));
        } catch (Throwable t) {
            sb.append("存储: --");
        }
        tvDeviceStatus.setText(sb.toString());
    }

    /** 读取 CPU 占用率（两次采样差值），首次采样返回 -1 */
    private float readCpuUsage() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();
            if (line == null || !line.startsWith("cpu ")) {
                return -1;
            }
            String[] parts = line.trim().split("\\s+");
            long total = 0, idle;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
            }
            idle = Long.parseLong(parts[5]) + Long.parseLong(parts[6]); // idle + iowait
            if (mLastCpuTotal == 0 || total <= mLastCpuTotal) {
                mLastCpuTotal = total;
                mLastCpuIdle = idle;
                return -1;
            }
            long dTotal = total - mLastCpuTotal;
            long dIdle = idle - mLastCpuIdle;
            mLastCpuTotal = total;
            mLastCpuIdle = idle;
            if (dTotal <= 0) {
                return -1;
            }
            return Math.max(0f, Math.min(100f, (dTotal - dIdle) * 100f / dTotal));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        double gb = bytes / 1073741824.0;
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1fG", gb);
        }
        double mb = bytes / 1048576.0;
        if (mb >= 1.0) {
            return String.format(Locale.US, "%.0fM", mb);
        }
        return String.format(Locale.US, "%.0fK", bytes / 1024.0);
    }

    // ------------------- 历史记录 ------------------- //

    @Override
    public void onDataChanged() {
        mHandler.post(this::queryHistory);
    }

    private void pickDate(final boolean isStart) {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, dayOfMonth,
                    isStart ? 0 : 23, isStart ? 0 : 59, isStart ? 0 : 59);
            String text = DATE_FMT.format(picked.getTime());
            if (isStart) {
                etStartTime.setText(text);
            } else {
                etEndTime.setText(text);
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** 列表最多显示的记录条数 */
    private static final int HISTORY_LIMIT = 50;

    /** 按时间范围 + 任务编号查询（最多显示前 50 笔） */
    private void queryHistory() {
        long from = parseTime(etStartTime.getText().toString(), true);
        long to = parseTime(etEndTime.getText().toString(), false);
        String taskId = etTaskId.getText().toString();
        List<TaskHistoryDb.Record> records =
                TaskHistoryDb.get().query(from, to, taskId, HISTORY_LIMIT);
        renderHistory(records);
    }

    /** 刷新：重新加载最新的 50 笔记录 */
    private void refreshHistory() {
        List<TaskHistoryDb.Record> records = TaskHistoryDb.get().queryRecent(HISTORY_LIMIT);
        renderHistory(records);
    }

    private void renderHistory(List<TaskHistoryDb.Record> records) {
        mHistoryData.clear();
        int no = 1;
        for (TaskHistoryDb.Record r : records) {
            Map<String, Object> map = new HashMap<>();
            map.put("no", String.valueOf(no++));
            // 服务端响应不带 TaskIdentifier 的记录，任务编号显示 "-"
            map.put("taskId", r.taskId == null || r.taskId.isEmpty() ? "-" : r.taskId);
            map.put("time", r.taskTime);
            map.put("total", String.valueOf(r.photoCount));
            map.put("success", String.valueOf(r.successCount));
            map.put("fail", String.valueOf(r.failCount));
            map.put("duration", TaskHistoryDb.formatDuration(r.durationMs));
            mHistoryData.add(map);
        }
        mHistoryAdapter.notifyDataSetChanged();
    }

    /** 清除全部历史记录（需确认） */
    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("清除记录")
                .setMessage("确定清除 SQLite 中的全部历史任务记录吗？此操作不可恢复！")
                .setPositiveButton("确定清除", (dialog, which) -> {
                    TaskHistoryDb.clearAll(); // onDataChanged 回调会自动清空并刷新列表
                    Toast.makeText(this, "历史记录已清除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private long parseTime(String text, boolean startOfDay) {
        try {
            Date d = DATE_FMT.parse(text);
            if (d != null) {
                return d.getTime();
            }
        } catch (Exception ignored) {
        }
        Calendar cal = Calendar.getInstance();
        if (!startOfDay) {
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
        }
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // ------------------- 设置 ------------------- //

    private void showSettings() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        container.setPadding(pad, pad, pad, 0);

        // 带标签的输入行：标签在输入框前面
        final EditText etHost = addLabeledInput(container, "服务器地址", "http://ip:端口");
        etHost.setText(Config.getHttpHost());

        final EditText etNo = addLabeledInput(container, "设备机号 (clientNos)", "如 0001");
        etNo.setText(Config.getDeviceNumber());

        final EditText etInterval = addLabeledInput(container, "心跳间隔（秒）", "最小 5，同步由心跳触发");
        etInterval.setText(String.valueOf(Config.getHttpInterval()));

        final EditText etSize = addLabeledInput(container, "单次拉取条数 (size)", "默认 200，照片限制1M以内");
        etSize.setText(String.valueOf(Config.getSyncSize()));

        final EditText etScore = addLabeledInput(container, "人脸分数阈值 (0-100)", "默认 70");
        etScore.setText(String.valueOf(Config.getFaceScoreThreshold()));
        TextView tvScoreTip = new TextView(this);
        tvScoreTip.setTextSize(12);
        tvScoreTip.setTextColor(0xFF999999);
        tvScoreTip.setPadding(0, 0, 0, (int) (pad * 0.5f));
        tvScoreTip.setText("旷视算法检测到的人脸分数低于该阈值时判定为\"照片不合格\"");
        container.addView(tvScoreTip);

        final EditText etPwd = addLabeledInput(container, "修改 Web 登录密码", "留空不修改，默认 123456");
        etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(this)
                .setTitle("参数设置")
                .setView(container)
                .setPositiveButton("保存", (dialog, which) -> {
                    Config.setHttpHost(etHost.getText().toString());
                    Config.setDeviceNumber(etNo.getText().toString());
                    try {
                        Config.setHttpInterval(Integer.parseInt(etInterval.getText().toString()));
                    } catch (Exception ignored) {
                    }
                    try {
                        Config.setSyncSize(Integer.parseInt(etSize.getText().toString()));
                    } catch (Exception ignored) {
                    }
                    try {
                        Config.setFaceScoreThreshold(
                                Integer.parseInt(etScore.getText().toString()));
                    } catch (Exception ignored) {
                    }
                    String pwd = etPwd.getText().toString();
                    if (!TextUtils.isEmpty(pwd.trim())) {
                        Config.setWebPassword(pwd);
                    }
                    tvDeviceNo.setText("机号: " + Config.getDeviceNumber());
                    FaceSyncTask.restart();
                    TimeSyncTask.restart();
                    Toast.makeText(this, "参数已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 设置弹窗输入行：左侧标签 + 右侧输入框 */
    private EditText addLabeledInput(LinearLayout container, String label, String hint) {
        android.content.Context ctx = this;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(0xFF333333);
        tv.setWidth((int) (getResources().getDisplayMetrics().density * 150));
        row.addView(tv);
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setTextSize(14);
        et.setSingleLine(true);
        row.addView(et, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(row);
        return et;
    }
}
