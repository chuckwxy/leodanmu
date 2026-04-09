package com.github.catvod.spider;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在 init() 收到空 ext 时，优先从 Ok影视宿主运行时已加载的配置 JSON 里提取 ext；
 * 若拿不到，再兜底回退到订阅 URL 拉取 JSON。
 * 全程静默，不弹 Toast。
 */
public class ExtFetcher {

    private static String lastSource = "none";
    private static String lastClassName = "";
    private static String lastMethodName = "";
    private static String lastError = "";
    private static final List<String> traceLogs = new ArrayList<>();
    private static int traceStep = 0;

    private static final String[] CONFIG_CLASS_NAMES = {
            "com.fongmi.android.tv.api.config.LiveConfig",
            "com.fongmi.android.tv.api.config.VodConfig",
            "com.fongmi.android.tv.api.LiveConfig",
            "com.fongmi.android.tv.api.VodConfig",
            "com.github.tvbox.osc.api.config.LiveConfig",
            "com.github.tvbox.osc.api.config.VodConfig",
            "com.github.tvbox.osc.api.LiveConfig",
            "com.github.tvbox.osc.api.VodConfig",
            "com.github.catvod.api.config.LiveConfig",
            "com.github.catvod.api.config.VodConfig",
            // Ok影视 / 魔改宿主兼容猜测
            "com.ok影视.api.config.LiveConfig",
            "com.ok影视.api.config.VodConfig",
            "com.ok影视.tv.api.config.LiveConfig",
            "com.ok影视.tv.api.config.VodConfig",
            "com.ok.video.api.config.LiveConfig",
            "com.ok.video.api.config.VodConfig",
            "com.oktv.api.config.LiveConfig",
            "com.oktv.api.config.VodConfig",
            "com.player.ok.api.config.LiveConfig",
            "com.player.ok.api.config.VodConfig"
    };

    private static final String[] INSTANCE_METHODS = {
            "get", "getInstance", "instance"
    };

    private static final String[] JSON_METHODS = {
            "getJson", "json", "getConfig", "getConfigJson", "toJson", "getData"
    };

    private static final String[] URL_METHODS = {
            "getUrl", "url", "getConfigUrl"
    };

    private static final String[] SITES_METHODS = {
            "getSites", "sites"
    };

    private static final String[] CLASS_SCAN_CANDIDATES = {
            "Config", "LiveConfig", "VodConfig", "ApiConfig", "SourceConfig", "SiteConfig",
            "Live", "Vod", "Spider", "JarLoader", "BaseLoader", "Loader", "Setting"
    };

    private static final String[] PACKAGE_SCAN_HINTS = {
            "fongmi", "tvbox", "catvod", "ok", "影视", "player", "osc"
    };

    private static String scanNotes = "";

    private static void trace(String msg) {
        traceStep += 1;
        traceLogs.add("[" + traceStep + "] " + msg);
        if (traceLogs.size() > 120) traceLogs.remove(0);
    }

    public static String getTraceLog() {
        StringBuilder sb = new StringBuilder();
        for (String s : traceLogs) sb.append(s).append("\n");
        return sb.toString();
    }

    public static String fetchExtFromOkJson(Context context) {
        resetLastState();
        trace("enter fetchExtFromOkJson");
        String ext = fetchExtFromRuntimeConfig();
        if (!TextUtils.isEmpty(ext)) {
            trace("runtime-config hit");
            return ext;
        }
        trace("runtime-config miss");
        ext = fetchExtFromHostConfigs(context);
        if (!TextUtils.isEmpty(ext)) {
            trace("host-config hit");
            return ext;
        }
        trace("host-config miss");
        return null;
    }

    private static String fetchExtFromRuntimeConfig() {
        trace("try runtime-config");
        for (String className : CONFIG_CLASS_NAMES) {
            try {
                Class<?> clz = Class.forName(className);
                Object instance = getInstance(clz);

                String ext = fetchExtFromJsonMethods(clz, instance);
                if (!TextUtils.isEmpty(ext)) {
                    markSuccess("runtime-json", className, "json-method");
                    Leodanmu.log("ExtFetcher: 从宿主运行时JSON拿到ext");
                    return ext;
                }

                ext = fetchExtFromSitesMethods(clz, instance);
                if (!TextUtils.isEmpty(ext)) {
                    markSuccess("runtime-sites", className, "sites-method");
                    Leodanmu.log("ExtFetcher: 从宿主运行时sites拿到ext");
                    return ext;
                }

                ext = fetchExtFromFields(clz, instance);
                if (!TextUtils.isEmpty(ext)) {
                    markSuccess("runtime-field", className, "field-scan");
                    Leodanmu.log("ExtFetcher: 从宿主运行时字段拿到ext");
                    return ext;
                }
            } catch (Throwable ignored) {
            }
        }
        lastError = "runtime-config-not-found";
        trace("runtime-config no hit");
        return null;
    }

    private static Object getInstance(Class<?> clz) {
        for (String methodName : INSTANCE_METHODS) {
            try {
                Method method = clz.getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String fetchExtFromJsonMethods(Class<?> clz, Object instance) {
        for (String methodName : JSON_METHODS) {
            try {
                Method method = clz.getMethod(methodName);
                method.setAccessible(true);
                Object result = method.invoke(instance);
                String ext = extractExtFromUnknown(result);
                if (!TextUtils.isEmpty(ext)) return ext;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String fetchExtFromSitesMethods(Class<?> clz, Object instance) {
        for (String methodName : SITES_METHODS) {
            try {
                Method method = clz.getMethod(methodName);
                method.setAccessible(true);
                Object result = method.invoke(instance);
                String ext = extractExtFromSitesObject(result);
                if (!TextUtils.isEmpty(ext)) return ext;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String fetchExtFromFields(Class<?> clz, Object instance) {
        Field[] fields = clz.getDeclaredFields();
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                Object value = field.get(instance);
                String ext = extractExtFromUnknown(value);
                if (!TextUtils.isEmpty(ext)) return ext;
            } catch (Throwable ignored) {
            }
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
                if (text.startsWith("{")) {
                    return extractExtFromJsonObject(new JSONObject(text));
                }
                if (text.startsWith("[")) {
                    return extractExtFromSitesArray(new JSONArray(text));
                }
            }
        } catch (Throwable ignored) {
        }

        return extractExtFromPojo(source);
    }

    private static String extractExtFromJsonObject(JSONObject root) {
        if (root == null) return null;

        JSONArray sites = root.optJSONArray("sites");
        if (sites != null) {
            return extractExtFromSitesArray(sites);
        }

        JSONObject site = root.optJSONObject("site");
        if (site != null) {
            return extractExtFromSiteObject(site);
        }

        return extractExtFromSiteObject(root);
    }

    private static String extractExtFromSitesObject(Object sitesObj) {
        if (sitesObj == null) return null;
        try {
            if (sitesObj instanceof JSONArray) {
                return extractExtFromSitesArray((JSONArray) sitesObj);
            }
            if (sitesObj instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) sitesObj;
                for (Object item : list) {
                    String ext = extractExtFromPojo(item);
                    if (!TextUtils.isEmpty(ext)) return ext;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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

    private static String extractExtFromPojo(Object obj) {
        if (obj == null) return null;
        try {
            Class<?> clz = obj.getClass();
            String api = readStringMember(clz, obj, "getApi", "api");
            String key = readStringMember(clz, obj, "getKey", "key");
            String name = readStringMember(clz, obj, "getName", "name");
            if (!isTargetSite(api, key, name)) return null;

            Object ext = readMember(clz, obj, "getExt", "ext");
            if (ext instanceof JSONObject || ext instanceof JSONArray) return ext.toString();
            if (ext instanceof String && !TextUtils.isEmpty((String) ext)) return (String) ext;
        } catch (Throwable ignored) {
        }
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

    private static String readStringMember(Class<?> clz, Object instance, String getterName, String fieldName) {
        Object value = readMember(clz, instance, getterName, fieldName);
        return value == null ? null : String.valueOf(value);
    }

    private static Object readMember(Class<?> clz, Object instance, String getterName, String fieldName) {
        try {
            Method method = clz.getMethod(getterName);
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (Throwable ignored) {
        }
        try {
            Field field = clz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(instance);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String fetchExtFromHostConfigs(Context context) {
        trace("try host config_1");
        if (context == null) {
            lastError = "host-config-no-context";
            trace("host config no context");
            return null;
        }
        String prefName = context.getPackageName() + "_preferences";
        try {
            trace("read pref: " + prefName);
            SharedPreferences sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
            String key = "config_1";
            String raw = sp.getString(key, "");
            trace("read key: " + key);
            if (TextUtils.isEmpty(raw)) {
                lastError = "config_1-empty";
                trace("config_1 empty");
                return null;
            }
            String preview = raw.substring(0, Math.min(raw.length(), 160)).replace("\n", " ");
            scanNotes = appendScanNote(scanNotes, key + "=" + preview);
            trace("preview " + key + ": " + preview);

            String ext = tryExtractExtFromConfigValue(key, raw);
            if (!TextUtils.isEmpty(ext)) {
                markSuccess("host-config", prefName, key);
                trace("ext found from " + key);
                return ext;
            }

            lastError = TextUtils.isEmpty(lastError) ? "config_1-no-hit" : lastError;
            trace("no ext from config_1");
            return null;
        } catch (Throwable e) {
            lastError = "host-config-ex:" + e.getMessage();
            trace("host config exception: " + e.getMessage());
            return null;
        }
    }

    private static String tryExtractExtFromConfigValue(String key, String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        if (TextUtils.isEmpty(value)) return null;

        if (looksLikeUrl(value)) {
            trace(key + " treated as url");
            String json = fetchJsonByHostHttp(value);
            if (TextUtils.isEmpty(json)) {
                trace(key + " url fetched empty");
                return null;
            }
            trace(key + " url fetched ok");
            trace(key + " response preview: " + preview(json));
            String ext = extractExtFromUnknown(json);
            if (!TextUtils.isEmpty(ext)) return ext;
            trace(key + " json has no ext");
            return null;
        }

        if (value.startsWith("{") || value.startsWith("[")) {
            trace(key + " treated as json");
            String ext = extractExtFromUnknown(value);
            if (!TextUtils.isEmpty(ext)) return ext;
            trace(key + " direct json has no ext");
            return null;
        }

        if (looksLikeBase64Json(value)) {
            trace(key + " treated as base64-json");
            try {
                String decoded = new String(java.util.Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
                trace(key + " base64 decoded");
                String ext = extractExtFromUnknown(decoded);
                if (!TextUtils.isEmpty(ext)) return ext;
                trace(key + " decoded json has no ext");
            } catch (Throwable e) {
                trace(key + " base64 decode failed: " + e.getMessage());
            }
        }

        trace(key + " unsupported raw format");
        return null;
    }

    private static boolean looksLikeBase64Json(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 16) return false;
        String v = value.trim();
        if (v.startsWith("eyJ") || v.startsWith("W3si)")) return true;
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
        String body = tryHostOkHttp(url);
        if (!TextUtils.isEmpty(body)) {
            scanNotes = appendScanNote(scanNotes, "host-http=ok");
            trace("host http success");
            trace("host http body preview: " + preview(body));
            return body;
        }
        scanNotes = appendScanNote(scanNotes, "host-http=fallback");
        trace("host http provider miss");
        trace("host http miss, fallback httpGet");
        return httpGet(url, 5000);
    }

    private static String tryHostOkHttp(String url) {
        String[] classNames = new String[] {
                "com.fongmi.android.tv.net.OkHttp",
                "com.github.catvod.net.OkHttp",
                "com.github.tvbox.osc.util.OkHttp",
                "com.github.tvbox.osc.net.OkHttp",
                "com.ok.video.net.OkHttp"
        };
        String[] methods = new String[] {"string", "get", "request"};
        for (String className : classNames) {
            trace("try host provider: " + className);
            try {
                Class<?> clz = Class.forName(className);
                for (String methodName : methods) {
                    try {
                        trace("try host method: " + className + "#" + methodName);
                        java.lang.reflect.Method m = clz.getMethod(methodName, String.class);
                        Object result = m.invoke(null, url);
                        if (result instanceof String && !TextUtils.isEmpty((String) result)) {
                            markSuccess("host-http", className, methodName);
                            trace("host http provider hit: " + className + "#" + methodName);
                            return (String) result;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String preview(String text) {
        if (TextUtils.isEmpty(text)) return "<empty>";
        String s = text.replace("
", " ").replace("", " ").trim();
        return s.substring(0, Math.min(s.length(), 200));
    }

    public static String getSubscriptionUrlPublic() {
        return getSubscriptionUrl();
    }

    private static String getSubscriptionUrl() {
        for (String className : CONFIG_CLASS_NAMES) {
            try {
                Class<?> clz = Class.forName(className);
                Object instance = getInstance(clz);
                for (String methodName : URL_METHODS) {
                    try {
                        Method m = clz.getMethod(methodName);
                        m.setAccessible(true);
                        Object result = m.invoke(instance);
                        if (result instanceof String && !TextUtils.isEmpty((String) result)) {
                            return (String) result;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String httpGet(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            trace("httpGet start: " + urlStr);
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 LeoDanmu/1.0");
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
            int code = conn.getResponseCode();
            trace("httpGet status: " + code);
            String contentType = conn.getContentType();
            trace("httpGet content-type: " + (contentType == null ? "-" : contentType));
            if (code != 200) {
                String location = conn.getHeaderField("Location");
                if (!TextUtils.isEmpty(location)) trace("httpGet location: " + location);
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            String body = sb.toString();
            trace("httpGet body preview: " + preview(body));
            return body;
        } catch (Exception e) {
            trace("httpGet exception type: " + e.getClass().getName());
            trace("httpGet exception message: " + e.getMessage());
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
