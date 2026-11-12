package com.hpt.faceextraction;

import android.os.Build;
import android.provider.Settings;

/**
 * 设备常量：SN 序列号等
 */
public final class Constants {

    private static String SN = null;
    private static String MAC = null;

    private Constants() {
    }

    /** 设备 SN 码：优先系统属性，其次 Build，最后 AndroidId */
    public static String getSN() {
        if (SN != null) {
            return SN;
        }
        String sn = sysProp("ro.serialno");
        if (sn == null || sn.isEmpty()) {
            sn = sysProp("ro.boot.serialno");
        }
        if (sn == null || sn.isEmpty()) {
            try {
                sn = Build.getSerial();
            } catch (Throwable ignored) {
            }
        }
        if (sn == null || sn.isEmpty() || Build.UNKNOWN.equals(sn)) {
            try {
                sn = Settings.Secure.getString(AppHolder.app().getContentResolver(),
                        Settings.Secure.ANDROID_ID);
            } catch (Throwable ignored) {
            }
        }
        SN = (sn == null || sn.isEmpty()) ? "UNKNOWN" : sn;
        return SN;
    }

    /**
     * 设备网卡 MAC 地址：优先有线网卡 eth0，其次 wlan0，再次任意有效网卡。
     * 优先读 /sys/class/net/{iface}/address（部分系统 getHardwareAddress 返回 0）。
     * 格式大写冒号分隔，如 AA:BB:CC:DD:EE:FF；获取失败返回 "00:00:00:00:00:00"
     */
    public static String getMac() {
        if (MAC != null) {
            return MAC;
        }
        // 1. 直接读 sysfs（最可靠）
        String ethMac = null, wifiMac = null, anyMac = null;
        try {
            java.io.File dir = new java.io.File("/sys/class/net");
            java.io.File[] ifaces = dir.listFiles();
            if (ifaces != null) {
                for (java.io.File f : ifaces) {
                    String mac = readIfMac(f.getName());
                    if (mac == null || isZeroMac(mac)) {
                        continue;
                    }
                    String name = f.getName().toLowerCase();
                    if (name.startsWith("eth") && ethMac == null) {
                        ethMac = mac;
                    } else if ((name.startsWith("wlan") || name.startsWith("wifi")) && wifiMac == null) {
                        wifiMac = mac;
                    } else if (anyMac == null && !"lo".equals(name)) {
                        anyMac = mac;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2. 兜底走 Java API
        if (ethMac == null && wifiMac == null && anyMac == null) {
            try {
                java.util.Enumeration<java.net.NetworkInterface> nis =
                        java.net.NetworkInterface.getNetworkInterfaces();
                while (nis != null && nis.hasMoreElements()) {
                    java.net.NetworkInterface ni = nis.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) {
                        continue;
                    }
                    byte[] hw = ni.getHardwareAddress();
                    if (hw == null || hw.length != 6) {
                        continue;
                    }
                    StringBuilder sb = new StringBuilder(17);
                    for (int i = 0; i < hw.length; i++) {
                        if (i > 0) {
                            sb.append(':');
                        }
                        sb.append(String.format("%02X", hw[i]));
                    }
                    String mac = sb.toString();
                    if (isZeroMac(mac)) {
                        continue;
                    }
                    String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                    if (name.startsWith("eth") && ethMac == null) {
                        ethMac = mac;
                    } else if ((name.startsWith("wlan") || name.startsWith("wifi")) && wifiMac == null) {
                        wifiMac = mac;
                    } else if (anyMac == null) {
                        anyMac = mac;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        MAC = ethMac != null ? ethMac : wifiMac != null ? wifiMac
                : anyMac != null ? anyMac : "00:00:00:00:00:00";
        return MAC;
    }

    /**
     * 当前联网方式对应网卡的 MAC 地址：以太网取 ethX，WiFi 取 wlanX/wifiX。
     * 每次实时读取（联网方式可能切换），无网络或读取失败时回退 getMac()。
     */
    public static String getActiveMac() {
        String ifacePrefix;
        NetMonitor.Mode mode = NetMonitor.currentMode();
        if (mode == NetMonitor.Mode.ETHERNET) {
            ifacePrefix = "eth";
        } else if (mode == NetMonitor.Mode.WIFI) {
            ifacePrefix = "wlan";
        } else {
            return getMac();
        }
        try {
            java.io.File dir = new java.io.File("/sys/class/net");
            java.io.File[] ifaces = dir.listFiles();
            if (ifaces != null) {
                String fallback = null;
                for (java.io.File f : ifaces) {
                    String name = f.getName().toLowerCase();
                    boolean match = ifacePrefix.equals("eth")
                            ? name.startsWith("eth")
                            : (name.startsWith("wlan") || name.startsWith("wifi"));
                    if (!match) {
                        continue;
                    }
                    String mac = readIfMac(f.getName());
                    if (mac == null || isZeroMac(mac)) {
                        continue;
                    }
                    if (ifacePrefix.equals("eth")) {
                        return mac;
                    }
                    if (fallback == null) {
                        fallback = mac;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            }
        } catch (Throwable ignored) {
        }
        return getMac();
    }

    /** 以太网网卡（ethX）MAC 地址，不存在或无效返回空串 */
    public static String getEthernetMac() {
        return ifaceMac("eth");
    }

    /** WiFi 网卡（wlanX/wifiX）MAC 地址，不存在或无效返回空串 */
    public static String getWifiMac() {
        return ifaceMac("wifi");
    }

    /** 按前缀匹配第一块有效网卡的 MAC，匹配不到返回空串 */
    private static String ifaceMac(String prefix) {
        try {
            java.io.File dir = new java.io.File("/sys/class/net");
            java.io.File[] ifaces = dir.listFiles();
            if (ifaces != null) {
                for (java.io.File f : ifaces) {
                    String name = f.getName().toLowerCase();
                    boolean match = prefix.equals("eth")
                            ? name.startsWith("eth")
                            : (name.startsWith("wlan") || name.startsWith("wifi"));
                    if (!match) {
                        continue;
                    }
                    String mac = readIfMac(f.getName());
                    if (mac != null && !isZeroMac(mac)) {
                        return mac;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 读取 /sys/class/net/{iface}/address，失败返回 null */
    private static String readIfMac(String iface) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/sys/class/net/" + iface + "/address"));
            String line = r.readLine();
            r.close();
            if (line == null || line.length() < 17) {
                return null;
            }
            line = line.trim().toUpperCase();
            return line.length() >= 17 ? line.substring(0, 17) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isZeroMac(String mac) {
        return mac == null || mac.replace("0", "").replace(":", "").isEmpty();
    }

    private static String sysProp(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            return (String) cls.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }
}
