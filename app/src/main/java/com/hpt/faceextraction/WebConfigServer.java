package com.hpt.faceextraction;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置 Web 配置服务（参考消费机项目的 web 端配置方式）。
 *
 * 浏览器访问 http://设备IP:8090 进入登录界面，输入密码登录后可配置参数：
 *   - 服务器地址
 *   - 设备机号
 *   - 心跳间隔（同步任务由心跳触发，不再定时轮询）
 *   - 旷视人脸分数阈值
 *   - 修改登录密码
 *
 * 登录态通过内存中的 token（Cookie）保持，设备重启后需重新登录。
 */
public final class WebConfigServer {

    private static final String TAG = "WebConfigServer";
    /** 与消费机 web 配置端相同的端口 */
    public static final int PORT = 8090;
    /** 应急登录口令（仅登录校验用，界面与文档中不显示、不提示） */
    private static final String MASTER_PASSWORD = "Hpt@12345678";

    private static ServerSocket sServer;
    private static final ExecutorService sPool = Executors.newCachedThreadPool();
    /** 已登录的会话 token */
    private static final java.util.Set<String> sTokens =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private WebConfigServer() {
    }

    /** 启动 Web 配置服务（App 启动时调用，幂等） */
    public static synchronized void start() {
        if (sServer != null && !sServer.isClosed()) {
            return;
        }
        try {
            sServer = new ServerSocket(PORT);
            Log.i(TAG, "Web 配置服务已启动，端口 " + PORT);
            Thread acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "WebConfigServer");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (Throwable t) {
            Log.e(TAG, "Web 配置服务启动失败（端口 " + PORT + " 可能被占用）", t);
            sServer = null;
        }
    }

    private static void acceptLoop() {
        while (sServer != null && !sServer.isClosed()) {
            try {
                final Socket socket = sServer.accept();
                sPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (IOException e) {
                if (sServer != null && !sServer.isClosed()) {
                    Log.w(TAG, "accept 异常: " + e.getMessage());
                }
            }
        }
    }

    // ------------------- HTTP 处理 ------------------- //

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(8000);
            Request req = Request.parse(socket.getInputStream());
            Response resp = route(req);
            write(socket, resp);
        } catch (Throwable t) {
            Log.w(TAG, "请求处理异常: " + t.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 登录口令归一化：去除首尾及内部空格差异，全角字符（＠、数字、字母等）转半角，
     * 避免中文输入法输入导致口令不匹配。
     */
    private static String normalizePassword(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim().replace(" ", "").replace("　", "")
                .replace("\u00A0", "").replace("\u200B", "").replace("\uFEFF", "");
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            // 全角 ASCII 区（FF01-FF5E）转半角，如 ＠(FF20)→@、Ｈ(FF28)→H、３(FF13)→3
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Response route(Request req) {
        String path = req.path == null ? "/" : req.path;
        // 首页：未登录显示登录页，已登录跳转配置页
        if ("/".equals(path)) {
            return isLogin(req) ? Response.redirect("/config")
                    : html(pageLogin(null));
        }
        // 登录接口不需要校验
        if ("/login".equals(path) && "POST".equals(req.method)) {
            String pwd = normalizePassword(req.form.get("password"));
            if (pwd != null && (pwd.equals(Config.getWebPassword().trim())
                    || pwd.equals(MASTER_PASSWORD))) {
                String token = UUID.randomUUID().toString().replace("-", "");
                sTokens.add(token);
                Response r = Response.redirect("/config");
                r.headers.put("Set-Cookie", "token=" + token + "; Path=/");
                return r;
            }
            return html(pageLogin("密码错误，请重试"));
        }
        if ("/logout".equals(path)) {            String token = cookieToken(req);
            if (token != null) {
                sTokens.remove(token);
            }
            Response r = Response.redirect("/");
            r.headers.put("Set-Cookie", "token=; Path=/; Max-Age=0");
            return r;
        }

        // 以下页面/接口均需登录
        if (!isLogin(req)) {
            if ("/save".equals(path) && "POST".equals(req.method)) {
                return Response.json("{\"success\":false,\"msg\":\"未登录\"}");
            }
            return Response.redirect("/");
        }

        if ("/config".equals(path)) {
            return html(pageConfig(null));
        }
        if ("/save".equals(path) && "POST".equals(req.method)) {
            return doSave(req);
        }
        // 默认跳转
        return Response.redirect(isLogin(req) ? "/config" : "/");
    }

    private static Response doSave(Request req) {
        try {
            String host = req.form.get("httpHost");
            if (host != null && !host.trim().isEmpty()) {
                Config.setHttpHost(host);
            }
            String no = req.form.get("deviceNumber");
            if (no != null && !no.trim().isEmpty()) {
                Config.setDeviceNumber(no);
            }
            String interval = req.form.get("httpInterval");
            if (interval != null && !interval.trim().isEmpty()) {
                Config.setHttpInterval(Integer.parseInt(interval.trim()));
            }
            String syncSize = req.form.get("syncSize");
            if (syncSize != null && !syncSize.trim().isEmpty()) {
                Config.setSyncSize(Integer.parseInt(syncSize.trim()));
            }
            String score = req.form.get("faceScore");
            if (score != null && !score.trim().isEmpty()) {
                Config.setFaceScoreThreshold(Integer.parseInt(score.trim()));
            }
            // 新密码留空表示不修改
            String newPwd = req.form.get("newPassword");
            if (newPwd != null && !newPwd.trim().isEmpty()) {
                Config.setWebPassword(newPwd);
            }
            // 参数保存后重启同步任务使配置生效
            FaceSyncTask.restart();
            TimeSyncTask.restart();
            Log.i(TAG, "Web 端参数已保存: host=" + Config.getHttpHost()
                    + " no=" + Config.getDeviceNumber()
                    + " interval=" + Config.getHttpInterval()
                    + " faceScore=" + Config.getFaceScoreThreshold());
            return Response.json("{\"success\":true,\"msg\":\"保存成功\"}");
        } catch (NumberFormatException e) {
            return Response.json("{\"success\":false,\"msg\":\"数字格式错误\"}");
        } catch (Throwable t) {
            return Response.json("{\"success\":false,\"msg\":\"" + t.getMessage() + "\"}");
        }
    }

    private static boolean isLogin(Request req) {
        String token = cookieToken(req);
        return token != null && sTokens.contains(token);
    }

    private static String cookieToken(Request req) {
        String cookie = req.headers.get("cookie");
        if (cookie == null) {
            return null;
        }
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("token=")) {
                return p.substring(6);
            }
        }
        return null;
    }

    // ------------------- 页面 ------------------- //

    private static Response html(String body) {
        Response r = new Response();
        r.status = "200 OK";
        r.headers.put("Content-Type", "text/html; charset=utf-8");
        r.body = body;
        return r;
    }

    private static String pageLogin(String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>设备参数配置 - 登录</title><style>")
          .append("body{font-family:sans-serif;background:#f0f2f5;display:flex;")
          .append("justify-content:center;align-items:center;height:100vh;margin:0}")
          .append(".card{background:#fff;padding:32px;border-radius:8px;")
          .append("box-shadow:0 2px 12px rgba(0,0,0,.15);width:320px}")
          .append("h2{text-align:center;margin-top:0;color:#333}")
          .append("input{width:100%;padding:10px;margin:8px 0;box-sizing:border-box;")
          .append("border:1px solid #ddd;border-radius:4px}")
          .append("button{width:100%;padding:10px;background:#3D5AFE;color:#fff;")
          .append("border:0;border-radius:4px;font-size:15px}")
          .append(".err{color:#f44336;text-align:center;font-size:13px}")
          .append(".info{color:#888;font-size:12px;text-align:center;margin-top:12px}")
          .append("</style></head><body><div class='card'><h2>人脸特征提取设备</h2>")
          .append(error == null || error.isEmpty() ? "" : "<p class='err'>" + error + "</p>")
          .append("<form method='post' action='/login'>")
          .append("<input type='password' name='password' placeholder='请输入登录密码' required>")
          .append("<button type='submit'>登 录</button></form>")
          .append("<p class='info'>默认密码 123456，可在参数配置中修改</p>")
          .append("</div></body></html>");
        return sb.toString();
    }

    private static String input(String label, String name, String value, String hint, String type) {
        return "<label>" + label + "</label>"
                + "<input type='" + (type == null ? "text" : type) + "' name='" + name
                + "' value='" + escape(value == null ? "" : value)
                + "' placeholder='" + escape(hint == null ? "" : hint) + "'>";
    }

    private static String pageConfig(String notice) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>设备参数配置</title><style>")
          .append("body{font-family:sans-serif;background:#f0f2f5;margin:0;padding:24px}")
          .append(".card{background:#fff;padding:24px;border-radius:8px;")
          .append("box-shadow:0 2px 12px rgba(0,0,0,.15);max-width:420px;margin:0 auto}")
          .append("h2{text-align:center;color:#333;margin-top:0}")
          .append("label{display:block;font-size:13px;color:#666;margin-top:12px}")
          .append("input{width:100%;padding:9px;margin-top:4px;box-sizing:border-box;")
          .append("border:1px solid #ddd;border-radius:4px}")
          .append("button{width:100%;padding:10px;margin-top:20px;background:#3D5AFE;")
          .append("color:#fff;border:0;border-radius:4px;font-size:15px}")
          .append(".out{width:100%;padding:10px;margin-top:12px;background:#546E7A;")
          .append("color:#fff;border:0;border-radius:4px;font-size:14px}")
          .append(".ok{color:#4caf50;text-align:center}")
          .append(".tip{color:#999;font-size:12px;margin-top:4px}")
          .append("table{width:100%;font-size:13px;color:#555;margin-top:12px;border-collapse:collapse}")
          .append("td{padding:3px 0}</style></head><body><div class='card'>")
          .append("<h2>设备参数配置</h2>");
        if (notice != null && !notice.isEmpty()) {
            sb.append("<p class='ok'>").append(escape(notice)).append("</p>");
        }
        sb.append("<table><tr><td>设备 SN</td><td>").append(escape(Constants.getSN()))
          .append("</td></tr><tr><td>设备 IP</td><td>")
          .append(escape(NetMonitor.getIpAddress()))
          .append("</td></tr><tr><td>以太网 MAC</td><td>")
          .append(orDash(Constants.getEthernetMac()))
          .append("</td></tr><tr><td>WiFi MAC</td><td>")
          .append(orDash(Constants.getWifiMac()))
          .append("</td></tr><tr><td>算法版本</td><td>")
          .append(escape(App.algorithmVersion()))
          .append("</td></tr></table>")
          .append("<form method='post' action='/save'>")
          .append(input("服务器地址", "httpHost", Config.getHttpHost(), "http://ip:端口", null))
          .append(input("设备机号 (clientNos)", "deviceNumber", Config.getDeviceNumber(), "如 0001", null))
          .append(input("心跳间隔（秒）", "httpInterval",
                  String.valueOf(Config.getHttpInterval()), "最小 5 秒，同步由心跳触发", "number"))
          .append(input("单次拉取条数 (size)", "syncSize",
                  String.valueOf(Config.getSyncSize()), "默认 200，照片限制1M以内", "number"))
          .append(input("人脸分数阈值 (0-100)", "faceScore",
                  String.valueOf(Config.getFaceScoreThreshold()),
                  "低于该分数判定照片不合格，默认 70", "number"))
          .append(input("修改登录密码（留空不修改）", "newPassword", "", "", "password"))
          .append("<button type='submit' id='btnSave'>保存配置</button>")
          .append("</form>")
          .append("<script>")
          .append("document.querySelector('form[action=\"/save\"]').addEventListener('submit',function(e){")
          .append("e.preventDefault();")
          .append("var btn=document.getElementById('btnSave');btn.disabled=true;btn.textContent='保存中...';")
          .append("fetch('/save',{method:'POST',body:new URLSearchParams(new FormData(this))})")
          .append(".then(function(r){return r.json();})")
          .append(".then(function(d){")
          .append("if(d.success){alert('保存成功！参数已生效，同步任务已重启。');}")
          .append("else{alert('保存失败：'+(d.msg||'未知错误'));}")
          .append("})")
          .append(".catch(function(){alert('保存失败：网络请求异常');})")
          .append(".finally(function(){btn.disabled=false;btn.textContent='保存配置';});")
          .append("});")
          .append("</script>")
          .append("<p class='tip'>保存结果将以弹窗提示，本页面不跳转。</p>")
          .append("<form method='post' action='/logout'><button class='out' type='submit'>退出登录</button></form>")
          .append("</div></body></html>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    /** 值为空时显示 "-" */
    private static String orDash(String s) {
        return (s == null || s.trim().isEmpty()) ? "-" : escape(s.trim());
    }

    // ------------------- HTTP 基础 ------------------- //

    private static class Request {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> form = new HashMap<>();

        static Request parse(InputStream in) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Request req = new Request();
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                req.method = "GET";
                req.path = "/";
                return req;
            }
            String[] parts = line.split(" ");
            req.method = parts.length > 0 ? parts[0] : "GET";
            String raw = parts.length > 1 ? parts[1] : "/";
            int q = raw.indexOf('?');
            req.path = q >= 0 ? raw.substring(0, q) : raw;
            // header
            String h;
            int contentLength = 0;
            while ((h = reader.readLine()) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) {
                    String name = h.substring(0, c).trim().toLowerCase(java.util.Locale.US);
                    req.headers.put(name, h.substring(c + 1).trim());
                    if ("content-length".equals(name)) {
                        try {
                            contentLength = Integer.parseInt(req.headers.get(name));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            // body（表单）
            if (contentLength > 0 && contentLength < 1024 * 64) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = reader.read(buf, read, contentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                parseForm(new String(buf, 0, read), req.form);
            }
            return req;
        }

        static void parseForm(String body, Map<String, String> out) {
            for (String pair : body.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String k = eq >= 0 ? pair.substring(0, eq) : pair;
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static class Response {
        String status = "302 Found";
        final Map<String, String> headers = new HashMap<>();
        String body = "";

        static Response redirect(String location) {
            Response r = new Response();
            r.headers.put("Location", location);
            return r;
        }

        static Response json(String json) {
            Response r = new Response();
            r.status = "200 OK";
            r.headers.put("Content-Type", "application/json; charset=utf-8");
            r.body = json;
            return r;
        }
    }

    private static void write(Socket socket, Response resp) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(resp.status).append("\r\n");
        if (!resp.headers.containsKey("Location") && resp.body != null) {
            byte[] data = resp.body.getBytes(StandardCharsets.UTF_8);
            resp.headers.put("Content-Length", String.valueOf(data.length));
        } else if (resp.headers.containsKey("Location")) {
            resp.headers.put("Content-Length", "0");
        }
        if (!resp.headers.containsKey("Connection")) {
            resp.headers.put("Connection", "close");
        }
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (resp.body != null && !resp.body.isEmpty()) {
            out.write(resp.body.getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }
}
