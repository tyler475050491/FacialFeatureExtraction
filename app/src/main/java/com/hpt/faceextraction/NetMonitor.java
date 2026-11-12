package com.hpt.faceextraction;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 网络状态监测：以太网 / WiFi 连接模式、IP 地址
 */
public final class NetMonitor {

    public enum Mode { ETHERNET, WIFI, NONE }

    private NetMonitor() {
    }

    /** 当前联网模式：优先以太网（默认显示以太网图标） */
    public static Mode currentMode() {
        ConnectivityManager cm = (ConnectivityManager) AppHolder.app()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return Mode.NONE;
        }
        boolean eth = isActive(cm, NetworkCapabilities.TRANSPORT_ETHERNET);
        boolean wifi = isActive(cm, NetworkCapabilities.TRANSPORT_WIFI);
        if (eth) {
            return Mode.ETHERNET;
        }
        if (wifi) {
            return Mode.WIFI;
        }
        // targetSdk 30 以下设备可能只上报 NetworkInfo
        try {
            NetworkInfo ethInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
            NetworkInfo wifiInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (ethInfo != null && ethInfo.isConnected()) {
                return Mode.ETHERNET;
            }
            if (wifiInfo != null && wifiInfo.isConnected()) {
                return Mode.WIFI;
            }
        } catch (Throwable ignored) {
        }
        return Mode.NONE;
    }

    private static boolean isActive(ConnectivityManager cm, int transport) {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(transport)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 设备 IP 地址（优先以太网网卡的 IPv4） */
    public static String getIpAddress() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String wifiIp = null;
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (name.startsWith("eth")) {
                            return ip;
                        }
                        if (wifiIp == null) {
                            wifiIp = ip;
                        }
                    }
                }
            }
            if (wifiIp != null) {
                return wifiIp;
            }
            // WiFi int 型 IP 兜底
            WifiManager wm = (WifiManager) AppHolder.app()
                    .getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF)
                            + "." + ((ip >> 24) & 0xFF);
                }
            }
        } catch (Throwable ignored) {
        }
        return "0.0.0.0";
    }
}
