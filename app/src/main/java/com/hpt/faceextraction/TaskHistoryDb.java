package com.hpt.faceextraction;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史同步任务记录（SQLite）
 */
public final class TaskHistoryDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "task_history.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE = "task_history";

    public static class Record {
        public long id;
        public String taskTime;      // 处理时间 yyyy-MM-dd HH:mm:ss
        public String taskId = "";   // 任务编号（服务端 TaskIdentifier，可为空）
        public int photoCount;       // 照片数量
        public int successCount;     // 成功数量
        public int failCount;        // 失败数量
        public long durationMs;      // 整体耗时（毫秒）
    }

    public interface Listener {
        void onDataChanged();
    }

    private static Listener sListener;

    public static void setListener(Listener l) {
        sListener = l;
    }

    private static TaskHistoryDb sInstance;

    public static synchronized TaskHistoryDb get() {
        if (sInstance == null) {
            sInstance = new TaskHistoryDb(AppHolder.app());
        }
        return sInstance;
    }

    private TaskHistoryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "task_time TEXT NOT NULL,"
                + "task_time_millis INTEGER NOT NULL,"
                + "task_id TEXT NOT NULL DEFAULT '',"
                + "photo_count INTEGER NOT NULL,"
                + "success_count INTEGER NOT NULL,"
                + "fail_count INTEGER NOT NULL,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_task_time_millis ON " + TABLE + "(task_time_millis)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        boolean altered = false;
        if (oldVersion < 2) {
            // v2 新增耗时列，保留历史数据
            try {
                db.execSQL("ALTER TABLE " + TABLE
                        + " ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0");
                altered = true;
            } catch (Throwable ignored) {
            }
        }
        if (oldVersion < 3) {
            // v3 新增任务编号列，历史数据留空
            try {
                db.execSQL("ALTER TABLE " + TABLE
                        + " ADD COLUMN task_id TEXT NOT NULL DEFAULT ''");
                altered = true;
            } catch (Throwable ignored) {
            }
        }
        if (!altered) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /** 一次任务完成，写入历史（durationMs = 整体耗时毫秒，无任务编号） */
    public static void insert(long timeMillis, int photoCount, int successCount,
                              int failCount, long durationMs) {
        insert(timeMillis, photoCount, successCount, failCount, durationMs, "");
    }

    /** 一次任务完成，写入历史（含任务编号 TaskIdentifier，可为空串） */
    public static void insert(long timeMillis, int photoCount, int successCount,
                              int failCount, long durationMs, String taskId) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("task_time", FMT.format(new Date(timeMillis)));
            cv.put("task_time_millis", timeMillis);
            cv.put("task_id", taskId == null ? "" : taskId);
            cv.put("photo_count", photoCount);
            cv.put("success_count", successCount);
            cv.put("fail_count", failCount);
            cv.put("duration_ms", durationMs);
            get().getWritableDatabase().insert(TABLE, null, cv);
        } catch (Throwable ignored) {
        }
        if (sListener != null) {
            sListener.onDataChanged();
        }
    }

    /** 清空全部历史记录 */
    public static void clearAll() {
        try {
            get().getWritableDatabase().delete(TABLE, null, null);
        } catch (Throwable ignored) {
        }
        if (sListener != null) {
            sListener.onDataChanged();
        }
    }

    /** 毫秒 → 中文可读时长，如 "1小时5分13秒20毫秒" */
    public static String formatDuration(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long hours = ms / 3600000L;
        long minutes = (ms % 3600000L) / 60000L;
        long seconds = (ms % 60000L) / 1000L;
        long millis = ms % 1000L;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (hours > 0 || minutes > 0) {
            sb.append(minutes).append("分");
        }
        if (hours > 0 || minutes > 0 || seconds > 0) {
            sb.append(seconds).append("秒");
        }
        sb.append(millis).append("毫秒");
        return sb.toString();
    }

    /** 按时间范围查询（毫秒时间戳，含边界），limit=0 表示不限 */
    public List<Record> query(long fromMillis, long toMillis, int limit) {
        return query(fromMillis, toMillis, null, limit);
    }

    /**
     * 按时间范围 + 任务编号查询。
     * taskId 为空/null 时不筛选；否则按"包含"匹配（LIKE %taskId%）。
     */
    public List<Record> query(long fromMillis, long toMillis, String taskId, int limit) {
        List<Record> list = new ArrayList<>();
        try {
            StringBuilder selection = new StringBuilder(
                    "task_time_millis >= ? AND task_time_millis <= ?");
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(fromMillis));
            args.add(String.valueOf(toMillis));
            if (taskId != null && !taskId.trim().isEmpty()) {
                selection.append(" AND task_id LIKE ?");
                args.add("%" + taskId.trim() + "%");
            }
            String limitStr = limit > 0 ? String.valueOf(limit) : null;
            Cursor c = get().getReadableDatabase().query(TABLE, null, selection.toString(),
                    args.toArray(new String[0]),
                    null, null, "task_time_millis DESC", limitStr);
            fillRecords(c, list);
        } catch (Throwable ignored) {
        }
        return list;
    }

    /** 最近 N 条（N<=0 时查全部） */
    public List<Record> queryRecent(int limit) {
        List<Record> list = new ArrayList<>();
        try {
            Cursor c = get().getReadableDatabase().query(TABLE, null, null, null,
                    null, null, "task_time_millis DESC",
                    limit > 0 ? String.valueOf(limit) : null);
            fillRecords(c, list);
        } catch (Throwable ignored) {
        }
        return list;
    }

    private static void fillRecords(Cursor c, List<Record> list) {
        while (c != null && c.moveToNext()) {
            Record r = new Record();
            r.id = c.getLong(c.getColumnIndexOrThrow("id"));
            r.taskTime = c.getString(c.getColumnIndexOrThrow("task_time"));
            r.taskId = c.getString(c.getColumnIndexOrThrow("task_id"));
            r.photoCount = c.getInt(c.getColumnIndexOrThrow("photo_count"));
            r.successCount = c.getInt(c.getColumnIndexOrThrow("success_count"));
            r.failCount = c.getInt(c.getColumnIndexOrThrow("fail_count"));
            r.durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
            list.add(r);
        }
        if (c != null) {
            c.close();
        }
    }
}
