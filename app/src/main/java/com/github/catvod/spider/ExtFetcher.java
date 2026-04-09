package com.github.catvod.spider;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * 精简版 ext 获取器：
 * 1. 直接从宿主 SharedPreferences 里的 config_1 拿订阅地址/配置
 * 2. 必要时通过订阅 URL 拉一次 JSON 提取 ext
 * 3. 使用内存缓存，避免 TV 端反复 HTTP / JSON 解析
 */
public class ExtFetcher {

    private static String lastSource = "none";
    private static String lastClassName = "";
    private static String lastMethodName = "";
    private static String lastError = "";
    private static final List<String> traceLogs = new ArrayList<>();
    private static int traceStep = 0;
    private static String scanNotes = "";

    private static String cachedSubscriptionUrl = null;
    private static String cachedFetchedExt = null;
    private static int cachedFetchedExtHash = 0;
    private static long lastFetchTime = 0L;
    private static final long FETCH_TTL_MS = 10 * 60 * 1000L;

    private static void trace(String msg) {
        traceStep += 1;
        traceLogs.add("[" + traceStep + "] " + msg);
        if (traceLogs.size() > 80) traceLogs.remove(0);
    }

    public static String getTraceLog() {
        StringBuilder sb = new StringBuilder();
        for (String s : traceLogs) sb.append(s).append("\n");
        return sb.toString();
    }

    public static String fetchExtFromOkJson(Context context) {
        return fetchExtFromSubscription(context);
    }

    public static String fetchExtFromSubscription(Context context) {
        resetLastState();
        trace("enter fetchExtFromSubscription");

        long now = System.currentTimeMillis();
        if (!TextUtils.isEmpty(cachedFetchedExt) && now - lastFetchTime < FETCH_TTL_MS) {
            markSuccess("memory-cache", "ExtFetcher", "fetchExtFromSubscription");
            trace("memory cache hit");
            return cachedFetchedExt;
        }

        if (context == null) {
            lastError = "no-context";
            trace("no context");
            return null;
        }

        String raw = readConfig1(context);
        if (TextUtils.isEmpty(raw)) {
            lastError = TextUtils.isEmpty(lastError) ? "config_1-empty" : lastError;
            trace("config_1 empty");
            return null;
        }

        String ext = null;
        try {
            String value = raw.trim();
            if (looksLikeUrl(value)) {
                cachedSubscriptionUrl = value;
                trace("config_1 is subscription url");
                String json = fetchJsonByHostHttp(value);
                if (!TextUtils.isEmpty(json)) {
                    ext = extractExtFromUnknown(json);
                    if (!TextUtils.isEmpty(ext)) {
                        markSuccess("subscription-url", context.getPackageName(), "config_1");
                    } else {
                        lastError = "subscription-json-no-ext";
                    }
                } else if (TextUtils.isEmpty(lastError)) {
                    lastError = "subscription-fetch-empty";
                }
            } else if (value.startsWith("{") || value.startsWith("[")) {
                trace("config_1 is direct json");
                ext = extractExtFromUnknown(value);
                if (!TextUtils.isEmpty(ext)) {
                    markSuccess("subscription-json", context.getPackageName(), "config_1");
                } else {
                    lastError = "config-json-no-ext";
                }
            } else if (looksLikeBase64Json(value)) {
                trace("config_1 is base64 json");
                try {
                    String decoded = new String(java.util.Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
                    ext = extractExtFromUnknown(decoded);
                    if (!TextUtils.isEmpty(ext)) {
                        markSuccess("subscription-base64", context.getPackageName(), "config_1");
                    } else {
                        lastError = "base64-json-no-ext";
                    }
                } catch (Throwable e) {
                    lastError = "base64-decode-failed:" + e.getMessage();
                }
            } else {
                lastError = "config_1-unsupported-format";
            }
        } catch (Throwable e) {
            lastError = "fetch-ext-ex:" + e.getMessage();
        }

        if (!TextUtils.isEmpty(ext)) {
            cachedFetchedExt = ext;
            cachedFetchedExtHash = ext.hashCode();
            lastFetchTime = now;
            trace("ext fetched ok, hash=" + cachedFetchedExtHash);
        }
        return ext;
    }

    private static String readConfig1(Context context) {
        try {
            String prefName = context.getPackageName() + "_preferences";
            SharedPreferences sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
            String raw = sp.getString("config_1", "");
            if (!TextUtils.isEmpty(raw)) {
                String preview = raw.substring(0, Math.min(raw.length(), 160)).replace("\n", " ");
                scanNotes = appendScanNote(scanNotes, "config_1=" + preview);
                return raw;
            }
        } catch (Throwable e) {
            lastError = "read-config_1-ex:" + e.getMessage();
        }
        return null;
    }

    private static String extractExtFromUnknown(Object source) {
        if (source == null) return null;
        try {
            if (source instanceof JSONObject) {
                return extractExtFromJsonObject((JSONObject) source);
            }
            if (source instanceof JSONArray) {
                return extractExtFromSitesArray((JSONArray) source);
            }
            if (source instanceof String) {
                String text = ((String) source).trim();
                if (TextUtils.isEmpty(text)) return null;
                if (text.startsWith("{")) return extractExtFromJsonObject(new JSONObject(text));
                if (text.startsWith("[")) return extractExtFromSitesArray(new JSONArray(text));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String extractExtFromJsonObject(JSONObject root) {
        if (root == null) return null;
        JSONArray sites = root.optJSONArray("sites");
        if (sites != null) return extractExtFromSitesArray(sites);
        JSONObject site = root.optJSONObject("site");
        if (site != null) return extractExtFromSiteObject(site);
        return extractExtFromSiteObject(root);
    }

    private static String extractExtFromSitesArray(JSONArray sites) {
        if (sites == null) return null;
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            String ext = extractExtFromSiteObject(site);
            if (!TextUtils.isEmpty(ext)) return ext;
        }
        return null;
    }

    private static String extractExtFromSiteObject(JSONObject site) {
        if (site == null) return null;
        String api = site.optString("api", "");
        String key = site.optString("key", "");
        String name = site.optString("name", "");
        if (!isTargetSite(api, key, name)) return null;

        Object ext = site.opt("ext");
        if (ext instanceof JSONObject || ext instanceof JSONArray) return ext.toString();
        if (ext instanceof String && !TextUtils.isEmpty((String) ext)) return (String) ext;
        return null;
    }

    private static boolean isTargetSite(String api, String key, String name) {
        return containsIgnoreCase(api, "Leodanmu")
                || containsIgnoreCase(key, "Leodanmu")
                || containsIgnoreCase(name, "Leodanmu")
                || containsIgnoreCase(api, "csp_Leodanmu")
                || containsIgnoreCase(key, "csp_Leodanmu");
    }

    private static boolean containsIgnoreCase(String text, String keyword) {
        return !TextUtils.isEmpty(text) && text.toLowerCase().contains(keyword.toLowerCase());
    }

    private static boolean looksLikeBase64Json(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 16) return false;
        String v = value.trim();
        if (v.startsWith("eyJ") || v.startsWith("W3si")) return true;
        return v.matches("^[A-Za-z0-9+/=]+$");
    }

    public static String getLastSource() {
        return lastSource;
    }

    public static String getLastClassName() {
        return lastClassName;
    }

    public static String getLastMethodName() {
        return lastMethodName;
    }

    public static String getLastError() {
        if (!TextUtils.isEmpty(scanNotes)) {
            return TextUtils.isEmpty(lastError) ? scanNotes : (lastError + " | " + scanNotes);
        }
        return lastError;
    }

    private static void resetLastState() {
        lastSource = "none";
        lastClassName = "";
        lastMethodName = "";
        lastError = "";
        scanNotes = "";
        traceLogs.clear();
        traceStep = 0;
    }

    private static void markSuccess(String source, String className, String methodName) {
        lastSource = source;
        lastClassName = className == null ? "" : className;
        lastMethodName = methodName == null ? "" : methodName;
        lastError = "";
    }

    private static String appendScanNote(String base, String extra) {
        if (TextUtils.isEmpty(base)) return extra;
        if (TextUtils.isEmpty(extra)) return base;
        return base + " | " + extra;
    }

    private static boolean looksLikeUrl(String text) {
        return !TextUtils.isEmpty(text) && (text.startsWith("http://") || text.startsWith("https://"));
    }

    private static String fetchJsonByHostHttp(String url) throws Exception {
        trace("fetch json via host http: " + url);
        String body = httpGetOnWorker(url, 3000);
        if (!TextUtils.isEmpty(body)) {
            scanNotes = appendScanNote(scanNotes, "host-http=ok");
            return body;
        }
        scanNotes = appendScanNote(scanNotes, "host-http=empty");
        return null;
    }

    public static String getSubscriptionUrlPublic() {
        return cachedSubscriptionUrl;
    }

    private static String httpGetOnWorker(final String urlStr, final int timeoutMs) throws Exception {
        FutureTask<String> task = new FutureTask<>(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return httpGet(urlStr, timeoutMs);
            }
        });
        Thread t = new Thread(task, "LeoDanmu-HttpWorker");
        t.start();
        try {
            return task.get();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    private static String httpGet(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 LeoDanmu/1.0");
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
            int code = conn.getResponseCode();
            if (code != 200) {
                lastError = "http-status-" + code;
                return null;
            }
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
