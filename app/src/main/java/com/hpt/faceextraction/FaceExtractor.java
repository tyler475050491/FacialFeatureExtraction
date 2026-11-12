package com.hpt.faceextraction;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.hpt.facecore.FaceCore;
import com.hpt.facecore.data.DetectBitmapResult;
import com.hpt.facepass.FacePassCore;

import mcv.facepass.types.FacePassDetectFacesOpt;
import mcv.facepass.types.FacePassDetectFacesResult;
import mcv.facepass.types.FacePassQualityOpt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单张照片的人脸特征提取器。
 * 照片只在内存中处理：下载 -> 解码 -> 检测/多人脸判断 -> 提取特征，
 * 不保存照片，不保存特征。
 */
public final class FaceExtractor {

    private static final String TAG = "FaceExtractor";

    /** 提取结果状态码（与服务端约定） */
    public static final int ST_SUCCESS = 0;          // 成功
    public static final int ST_DOWNLOAD_FAIL = 1;    // 下载失败
    public static final int ST_NO_FACE = 2;          // 未检测到人脸
    public static final int ST_QUALITY = 3;          // 照片不合格（人脸特征无效/质量差）
    public static final int ST_MULTI_FACE = 4;       // 照片出现多个人脸
    public static final int ST_DECODE_FAIL = 5;      // 图片解码失败
    public static final int ST_PARAM_EMPTY = 6;      // 参数缺失（无照片地址）
    public static final int ST_OTHER = 9;            // 其他异常

    public static class Result {
        public int status;
        public String message;
        /** 提取成功时的人脸特征（Base64），不落盘，仅用于随结果上报 */
        public String featureBase64;
        public Bitmap bitmap; // 成功时返回（调用方负责回收）；一般不使用
    }

    private FaceExtractor() {
    }

    /**
     * 完整提取流程（在工作线程中调用）。
     *
     * @param faceDataUrl 照片地址（相对路径或绝对路径）
     */
    public static Result extract(String faceDataUrl) {
        Result r = new Result();
        // 1. 参数检查
        if (faceDataUrl == null || faceDataUrl.trim().isEmpty()) {
            r.status = ST_PARAM_EMPTY;
            r.message = "照片地址为空";
            return r;
        }
        // 2. 下载（相对路径自动拼接服务器地址，与参考项目一致）
        String url = Config.faceUrl(faceDataUrl.trim());
        byte[] data = HttpUtil.get(url);
        if (data == null || data.length == 0) {
            r.status = ST_DOWNLOAD_FAIL;
            r.message = "下载失败:" + url;
            Log.w(TAG, r.message);
            return r;
        }
        // 3. 解码（大图缩放到 1000px 以内，与参考项目一致）
        Bitmap bitmap = decode(data);
        if (bitmap == null) {
            r.status = ST_DECODE_FAIL;
            r.message = "图片解码失败";
            Log.w(TAG, r.message + " url=" + url);
            return r;
        }
        try {
            // 4. 多人脸检测 + 人脸分数判断
            DetectInfo info = detectFaceInfo(bitmap);
            if (info.faceCount > 1) {
                r.status = ST_MULTI_FACE;
                r.message = "照片出现多个人脸(" + info.faceCount + "张)";
                Log.w(TAG, r.message + " url=" + url);
                return r;
            }
            // 4.1 分数阈值判断（可在参数设置/网页端配置，默认 70）
            int threshold = Config.getFaceScoreThreshold();
            if (info.faceCount >= 1 && info.maxScore100 < threshold) {
                r.status = ST_QUALITY;
                r.message = "照片不合格-人脸分数 " + info.maxScore100
                        + " 低于阈值 " + threshold;
                Log.w(TAG, r.message + " url=" + url);
                return r;
            }
            // 5. 旷视算法提取特征
            DetectBitmapResult detect;
            try {
                detect = FaceCore.INSTANCE.detectBitmap(bitmap);
            } catch (Throwable t) {
                Log.e(TAG, "特征提取异常 url=" + url, t);
                r.status = ST_QUALITY;
                r.message = "照片不合格-特征提取异常";
                return r;
            }
            if (detect == null || !detect.isSuccess()
                    || detect.getFeature() == null || detect.getFeature().length == 0) {
                String msg = detect != null && detect.getMsg() != null ? detect.getMsg() : "";
                r.status = detect != null && detect.isSuccess() ? ST_QUALITY : ST_NO_FACE;
                if (detect != null && detect.isSuccess()) {
                    r.status = ST_QUALITY;
                    r.message = "照片不合格-特征无效" + (msg.isEmpty() ? "" : ":" + msg);
                } else {
                    r.status = ST_NO_FACE;
                    r.message = "未检测到人脸" + (msg.isEmpty() ? "" : ":" + msg);
                }
                Log.w(TAG, r.message + " url=" + url);
                return r;
            }
            byte[] feature = detect.getFeature();
            r.status = ST_SUCCESS;
            r.message = "";
            r.featureBase64 = Base64.encodeToString(feature, Base64.NO_WRAP);
            r.bitmap = bitmap;
            return r;
        } finally {
            if (r.bitmap == null) {
                bitmap.recycle();
            }
        }
    }

    /** 解码并限制最大边 1000px（参考消费机项目同样处理） */
    private static Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            int sample = 1;
            int maxSide = Math.max(opts.outWidth, opts.outHeight);
            while (maxSide / sample > 1000) {
                sample *= 2;
            }
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp != null && Math.max(bmp.getWidth(), bmp.getHeight()) > 1000) {
                float scale = 1000f / Math.max(bmp.getWidth(), bmp.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        (int) (bmp.getWidth() * scale), (int) (bmp.getHeight() * scale), true);
                if (scaled != bmp) {
                    bmp.recycle();
                }
                bmp = scaled;
            }
            return bmp;
        } catch (Throwable t) {
            Log.e(TAG, "decode error", t);
            return null;
        }
    }

    private static final AtomicInteger detectGuard = new AtomicInteger(0);

    /** 人脸检测信息：人脸数量 + 最高人脸分数（0-100） */
    private static class DetectInfo {
        int faceCount;
        int maxScore100;
    }

    /**
     * 用旷视 detectFaces 接口对静态照片做多人脸检测与质量分数评估；
     * 异常时返回 faceCount=1（跳过多人脸判断，由 detectBitmap 做最终判定）。
     */
    private static DetectInfo detectFaceInfo(Bitmap bitmap) {
        DetectInfo info = new DetectInfo();
        info.faceCount = 1;
        // 无法评估分数时不做分数判断（由 detectBitmap 做最终判定）
        info.maxScore100 = 100;
        // detectFaces 通道同一时刻只允许一个线程使用
        if (!detectGuard.compareAndSet(0, 1)) {
            return info;
        }
        try {
            mcv.facepass.FacePassHandler handler = FacePassCore.INSTANCE.getMFacePassHandler();
            if (handler == null) {
                return info;
            }
            // disable_filter=true：只做人数统计与分数读取，不过滤质量
            FacePassDetectFacesOpt opt = new FacePassDetectFacesOpt(false,
                    new FacePassQualityOpt(true, false));
            FacePassDetectFacesResult result = handler.detectFaces(bitmap, 0, opt);
            if (result == null || result.faceList == null || result.faceList.length == 0) {
                info.faceCount = 0;
                info.maxScore100 = 0;
                return info;
            }
            info.faceCount = result.faceList.length;
            float maxScore = 0f;
            for (mcv.facepass.types.FacePassTrackedFace f : result.faceList) {
                if (f != null && f.lmkScore > maxScore) {
                    maxScore = f.lmkScore;
                }
            }
            // lmkScore 为 0~1，转成 0~100 整数
            info.maxScore100 = Math.round(maxScore * 100f);
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "多人脸检测不可用，跳过: " + t.getMessage());
            info.faceCount = 1;
            info.maxScore100 = 100;
            return info;
        } finally {
            detectGuard.set(0);
        }
    }
}
