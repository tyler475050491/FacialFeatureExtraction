package com.hpt.facepass;

/**
 * 手工提供的资源 ID 映射。
 *
 * 旷视 FacePassCore 通过 Context.getString(R.string.mcv_xxx) 读取模型路径，
 * 而 pipeline-orig.jar 中的 R$string 固化的是消费机工程的资源 ID，与本 APP 不一致，
 * 会导致 Resources$NotFoundException。此处从 jar 中移除了该类，改用本源码类，
 * ID 必须与 APK 实际生成的资源 ID 一致（用 aapt2 dump resources 校验）。
 *
 * ⚠️ 注意：若在 strings.xml 中 mcv_* 之前增删字符串资源，这些 ID 会变化，
 * 需重新用 aapt2 dump resources 校对并同步更新。
 */
public final class R {
    public static final class string {
        public static final int mcv_feature_Ari = 0x7f0d003f;
        public static final int mcv_liveness_A = 0x7f0d0040;
        public static final int mcv_livenessrgb_A = 0x7f0d0041;
        public static final int mcv_occlusion_B = 0x7f0d0042;
        public static final int mcv_poseblur_A = 0x7f0d0043;
        public static final int mcv_postfilter_A = 0x7f0d0044;
        public static final int mcv_rc_attribute_A = 0x7f0d0045;
        public static final int mcv_rk3568_det_A_det = 0x7f0d0046;
    }

    private R() {
    }
}
