package com.hpt.faceextraction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Gson 单例
 */
public final class GsonHolder {

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .create();

    private GsonHolder() {
    }
}
