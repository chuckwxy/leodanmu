package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Proxy {

    private static Method method;
    private static int port;

    public static Object[] proxy(Map<String, String> params) {
        if ("ck".equals(params.get("do"))) return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8))};
        if ("video".equals(params.get("do")) || "quark".equals(params.get("do"))) return proxyVideo(params);
        return null;
    }

    private static Object[] proxyVideo(Map<String, String> params) {
        try {
            String targetUrl = params.get("url");
            if (TextUtils.isEmpty(targetUrl)) return null;

            Request.Builder builder = new Request.Builder().url(targetUrl);

            String cookie = params.get("ck");
            if (!TextUtils.isEmpty(cookie)) {
                builder.header("Cookie", cookie);
            }
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch");
            builder.header("Referer", "https://pan.quark.cn/");

            String range = params.get("range");
            if (!TextUtils.isEmpty(range)) {
                builder.header("Range", range);
            }

            OkHttpClient proxyClient = OkHttp.client().newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
            Response response = proxyClient.newCall(builder.build()).execute();
            int code = response.code();
            String contentType = response.header("Content-Type", "application/octet-stream");
            byte[] data = response.body().bytes();
            return new Object[]{code, contentType, new ByteArrayInputStream(data)};
        } catch (Exception e) {
            SpiderDebug.log("Proxy proxyVideo error: " + e.getMessage());
            return null;
        }
    }

    public static void init() {
        try {
            Class<?> clz = Class.forName("com.github.catvod.Proxy");
            port = (int) clz.getMethod("getPort").invoke(null);
            method = clz.getMethod("getUrl", boolean.class);
            SpiderDebug.log("本地代理端口:" + port);
        } catch (Throwable e) {
            findPort();
        }
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl(String siteKey, String param) {
        return "proxy://do=csp&siteKey=" + siteKey + param;
    }

    public static String getUrl() {
        return getUrl(true);
    }

    public static String getUrl(boolean local) {
        try {
            return (String) method.invoke(null, local);
        } catch (Throwable e) {
            return "http://127.0.0.1:" + port + "/proxy";
        }
    }

    private static void findPort() {
        if (port > 0) return;
        for (int p = 8964; p < 9999; p++) {
            if ("ok".equals(OkHttp.string("http://127.0.0.1:" + p + "/proxy?do=ck", null))) {
                SpiderDebug.log("本地代理端口:" + p);
                port = p;
                break;
            }
        }
    }
}
