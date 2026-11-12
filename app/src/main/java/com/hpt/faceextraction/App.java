package com.hpt.faceextraction;

import android.app.Application;
import android.util.Log;

import com.hpt.facecore.FaceCore;

import java.util.HashMap;

/**
 * 应用入口：初始化旷视 FaceCore 算法 SDK（与消费机项目同款算法内核）
 */
public class App extends Application {

    private static final String TAG = "FaceExtraction.App";

    @Override
    public void onCreate() {
        super.onCreate();
        AppHolder.init(this);
        initFaceSdk();
        // 内置 Web 配置服务（浏览器访问 http://设备IP:8090 登录后配置参数）
        WebConfigServer.start();
    }

    private void initFaceSdk() {
        try {
            // 与参考项目一致的算法配置
            HashMap<String, Object> config = new HashMap<>();
            config.put("livelinessThreshold", 0.5f);
            config.put("livelinessThreshold3d", 0.5f);
            config.put("faceMinThreshold", 100);
            FaceCore.INSTANCE.setFaceConfig(config, false);

            // 算法模型缓存目录（SDK 要求目录必须已存在且可读写，否则 initHandle 报 error 58）
            String modelDir = getFilesDir().getAbsolutePath() + "/facelib";
            //noinspection ResultOfMethodCallIgnored
            new java.io.File(modelDir).mkdirs();
            FaceCore.INSTANCE.sdkInit(this, modelDir);

            // 授权认证（旷视授权，设备上已有授权文件时直接通过）
            boolean certified = FaceCore.INSTANCE.certification(this);
            Log.i(TAG, "FaceCore 初始化完成, version=" + algorithmVersion() + ", certification=" + certified
                    + ", isInit=" + FaceCore.INSTANCE.isInit());
        } catch (Throwable t) {
            // 算法初始化失败时 app 仍可运行（界面/心跳正常），提取任务会全部按失败上报
            Log.e(TAG, "FaceCore 初始化异常", t);
        }
    }

    /** 当前算法版本，例如 “KS FacePass133 v1.3.3” */
    public static String algorithmVersion() {
        try {
            return FaceCore.INSTANCE.getFaceCoreType() + " " + FaceCore.INSTANCE.getFaceCoreVersion();
        } catch (Throwable t) {
            return "未知";
        }
    }
}
