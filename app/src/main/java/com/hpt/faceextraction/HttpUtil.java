package com.hpt.faceextraction;

import android.util.Log;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 简单的 HTTP 工具（OkHttp）
 */
public final class HttpUtil {

    private static final String TAG = "HttpUtil";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * 全局协议头：Client-ID（机号）、sn（设备SN码）、mac（设备网卡地址）。
     * 所有接口请求/上报统一携带（用拦截器实现，含照片下载）
     */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(chain -> {
                Request original = chain.request();
                Request.Builder b = original.newBuilder();
                String clientId = Config.getDeviceNumber();
                if (clientId != null && !clientId.isEmpty()) {
                    b.header("Client-ID", clientId);
                }
                String sn = Constants.getSN();
                if (sn != null && !sn.isEmpty()) {
                    b.header("sn", sn);
                }
                String mac = Constants.getMac();
                if (mac != null && !mac.isEmpty()) {
                    b.header("mac", mac);
                }
                return chain.proceed(b.build());
            })
            .build();

    private HttpUtil() {
    }

    public static byte[] get(String url) {
        Request request = new Request.Builder().url(url).get().build();
        Response response = null;
        try {
            response = CLIENT.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body().bytes();
            }
            Log.w(TAG, "GET fail " + response.code() + " " + url);
        } catch (Throwable t) {
            Log.w(TAG, "GET error " + url + " : " + t.getMessage());
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return null;
    }

    /** POST JSON，返回响应体字符串；失败返回 null */
    public static String postJson(String url, String json) {
        RequestBody body = RequestBody.create(JSON, json == null ? "" : json);
        Request request = new Request.Builder().url(url).post(body).build();
        Response response = null;
        try {
            response = CLIENT.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            Log.w(TAG, "POST fail " + response.code() + " " + url);
        } catch (Throwable t) {
            Log.w(TAG, "POST error " + url + " : " + t.getMessage());
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return null;
    }
}
