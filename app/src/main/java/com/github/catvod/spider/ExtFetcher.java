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

    public static String fetchExtFromOkJson(Context context) {
        resetLastState();
        String ext = fetchExtFromRuntimeConfig();
        if (!TextUtils.isEmpty(ext)) return ext;
        ext = fetchExtFromLiveSubscription(context);
        if (!TextUtils.isEmpty(ext)) return ext;
        return fetchExtFromSubscriptionUrlFallback();
    }

    private static String fetchExtFromRuntimeConfig() {
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

    private static String fetchExtFromLiveSubscription(Context context) {
        String liveUrl = fetchLiveSubscriptionUrlFromPrefs(context);
        if (TextUtils.isEmpty(liveUrl)) {
            lastError = "live-subscription-url-empty";
            return null;
        }

        try {
            String json = fetchJsonByHostHttp(liveUrl);
            if (TextUtils.isEmpty(json)) {
                lastError = "live-subscription-json-empty";
                return null;
            }

            String ext = extractExtFromUnknown(json);
            if (!TextUtils.isEmpty(ext)) {
                markSuccess("live-subscription", "shared_prefs", liveUrl);
                return ext;
            }

            lastError = "ext-not-found-in-live-subscription-json";
            return null;
        } catch (Throwable e) {
            lastError = e.getMessage();
            return null;
        }
    }

    private static String fetchLiveSubscriptionUrlFromPrefs(Context context) {
        if (context == null) return null;
        try {
            String[] prefNames = new String[] {
                    context.getPackageName() + "_preferences",
                    "config",
                    "setting",
                    "settings",
                    "live",
                    "lives",
                    "source",
                    "sources"
            };

            for (String prefName : prefNames) {
                try {
                    SharedPreferences sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                    Map<String, ?> all = sp.getAll();
                    String found = findUrlInMap(prefName, all);
                    if (!TextUtils.isEmpty(found)) return found;
                } catch (Throwable ignored) {
                }
            }

            SharedPreferences sp = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            String found = findUrlInMap(context.getPackageName() + "_preferences", sp.getAll());
            if (!TextUtils.isEmpty(found)) return found;
        } catch (Throwable e) {
            lastError = "pref-scan-ex:" + e.getMessage();
        }
        return null;
    }

    private static String findUrlInMap(String prefName, Map<String, ?> all) {
        if (all == null || all.isEmpty()) return null;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String text = value == null ? "" : String.valueOf(value).trim();
            if (TextUtils.isEmpty(text)) continue;
            if (!looksLikeLiveSubscriptionKey(key)) continue;
            if (!looksLikeUrl(text)) continue;
            scanNotes = prefName + "/" + key + "=" + text;
            return text;
        }
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            Object value = entry.getValue();
            String text = value == null ? "" : String.valueOf(value).trim();
            if (looksLikeUrl(text) && looksLikeLiveSubscriptionValue(text)) {
                scanNotes = prefName + "/" + entry.getKey() + "=" + text;
                return text;
            }
        }
        return null;
    }

    private static boolean looksLikeLiveSubscriptionKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        String k = key.toLowerCase();
        return (k.contains("live") || k.contains("直播") || k.contains("subscribe") || k.contains("source") || k.contains("config"))
                && (k.contains("url") || k.contains("link") || k.contains("home") || k.contains("api") || k.contains("订阅"));
    }

    private static boolean looksLikeLiveSubscriptionValue(String value) {
        String v = value.toLowerCase();
        return v.contains("sub") || v.contains("txt") || v.contains("json") || v.contains("live");
    }

    private static boolean looksLikeUrl(String text) {
        return !TextUtils.isEmpty(text) && (text.startsWith("http://") || text.startsWith("https://"));
    }

    private static String fetchJsonByHostHttp(String url) throws Exception {
        String body = tryHostOkHttp(url);
        if (!TextUtils.isEmpty(body)) {
            scanNotes = appendScanNote(scanNotes, "host-http=ok");
            return body;
        }
        scanNotes = appendScanNote(scanNotes, "host-http=fallback");
        return httpGet(url, 5000);
    }

    private static String tryHostOkHttp(String url) {
        String[] classNames = new String[] {
                "com.github.catvod.net.OkHttp",
                "com.github.tvbox.osc.util.OkHttp",
                "com.github.tvbox.osc.net.OkHttp",
                "com.fongmi.android.tv.net.OkHttp",
                "com.ok.video.net.OkHttp"
        };
        String[] methods = new String[] {"string", "get", "request"};
        for (String className : classNames) {
            try {
                Class<?> clz = Class.forName(className);
                for (String methodName : methods) {
                    try {
                        java.lang.reflect.Method m = clz.getMethod(methodName, String.class);
                        Object result = m.invoke(null, url);
                        if (result instanceof String && !TextUtils.isEmpty((String) result)) {
                            markSuccess("host-http", className, methodName);
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

    private static String appendScanNote(String base, String extra) {
        if (TextUtils.isEmpty(base)) return extra;
        if (TextUtils.isEmpty(extra)) return base;
        return base + " | " + extra;
    }

    private static String fetchExtFromSubscriptionUrlFallback() {
        String subscriptionUrl = getSubscriptionUrl();
        if (TextUtils.isEmpty(subscriptionUrl)) {
            lastError = "subscription-url-empty";
            return null;
        }
        try {
            String json = httpGet(subscriptionUrl, 5000);
            if (TextUtils.isEmpty(json)) {
                lastError = "subscription-json-empty";
                return null;
            }
            String ext = extractExtFromUnknown(json);
            if (!TextUtils.isEmpty(ext)) {
                markSuccess("fallback-url", "subscription", subscriptionUrl);
                return ext;
            }
            lastError = "ext-not-found-in-subscription-json";
            return null;
        } catch (Exception e) {
            lastError = e.getMessage();
            Leodanmu.log("ExtFetcher: fallback失败: " + e.getMessage());
            return null;
        }
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
    }

    private static void markSuccess(String source, String className, String methodName) {
        lastSource = source;
        lastClassName = className == null ? "" : className;
        lastMethodName = methodName == null ? "" : methodName;
        lastError = "";
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
