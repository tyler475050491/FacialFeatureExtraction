package com.hpt.faceextraction;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局 Holder（部分类无 Context 时使用）
 */
public final class AppHolder {

    private static Context sApp;

    private AppHolder() {
    }

    public static void init(Context app) {
        sApp = app.getApplicationContext();
    }

    public static Context app() {
        return sApp;
    }
}
