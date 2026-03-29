package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 当 init() 收到空 ext 时，主动从订阅 JSON 里解析 sites 拿 ext。
 * 兼容只配了直播订阅的场景。
 */
public class ExtFetcher {

    private static final String[] CONFIG_CLASS_NAMES = {
            "com.fongmi.android.tv.api.config.LiveConfig",
            "com.fongmi.android.tv.api.config.VodConfig",
            // 兼容旧版 TVBox
            "com.fongmi.android.tv.api.LiveConfig",
            "com.fongmi.android.tv.api.VodConfig",
    };

    /**
     * 尝试从订阅 JSON 里找到 csp_Leodanmu 对应 site 的 ext。
     * 找到返回 ext 字符串，找不到返回 null。
     */
    public static String fetchExtFromSubscription(Context context) {
        String subscriptionUrl = getSubscriptionUrl();
        if (TextUtils.isEmpty(subscriptionUrl)) {
            Leodanmu.log("ExtFetcher: 无法获取订阅URL");
            return null;
        }
        Leodanmu.log("ExtFetcher: 从订阅获取ext: " + subscriptionUrl);
        try {
            String json = httpGet(subscriptionUrl, 5000);
            if (TextUtils.isEmpty(json)) return null;
            JSONObject root = new JSONObject(json);
            JSONArray sites = root.optJSONArray("sites");
            if (sites == null) {
                Leodanmu.log("ExtFetcher: 订阅JSON中没有sites字段");
                return null;
            }
            for (int i = 0; i < sites.length(); i++) {
                JSONObject site = sites.getJSONObject(i);
                String api = site.optString("api", "");
                if (api.contains("Leodanmu") || api.contains("leodanmu")) {
                    Object ext = site.opt("ext");
                    if (ext instanceof JSONObject) {
                        String extStr = ext.toString();
                        Leodanmu.log("ExtFetcher: 找到ext: " + extStr);
                        return extStr;
                    } else if (ext instanceof String && !TextUtils.isEmpty((String) ext)) {
                        Leodanmu.log("ExtFetcher: 找到ext(string): " + ext);
                        return (String) ext;
                    }
                }
            }
            Leodanmu.log("ExtFetcher: sites里没有找到Leodanmu对应的site");
        } catch (Exception e) {
            Leodanmu.log("ExtFetcher: 解析失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 通过反射从 TVBox 的 LiveConfig/VodConfig 拿订阅 URL。
     */
    private static String getSubscriptionUrl() {
        for (String className : CONFIG_CLASS_NAMES) {
            try {
                Class<?> clz = Class.forName(className);
                java.lang.reflect.Method m = clz.getMethod("getUrl");
                Object result = m.invoke(null);
                if (result instanceof String && !TextUtils.isEmpty((String) result)) {
                    Leodanmu.log("ExtFetcher: 从 " + className + " 获取到URL: " + result);
                    return (String) result;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String httpGet(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
