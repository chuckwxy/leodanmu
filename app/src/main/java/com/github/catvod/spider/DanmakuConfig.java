package com.github.catvod.spider;

import com.google.gson.annotations.SerializedName;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DanmakuConfig {
    public Set<String> apiUrls;
    public float lpWidth;
    public float lpHeight;
    public float lpAlpha;
    public boolean autoPushEnabled;
    private boolean pushToastEnabled = true;
    private int theme = 0;
    private int danmakuTimeOffsetMs = 0;
    private String pansouApiUrl = "http://192.168.31.77:5888";
    private String pancheckApiUrl = "http://192.168.31.77:8989";

    @SerializedName(value = "leoQuarkCookie", alternate = {"quarkCookie"})
    private String leoQuarkCookie = "";
    @SerializedName(value = "leoUcCookie", alternate = {"ucCookie"})
    private String leoUcCookie = "";
    @SerializedName(value = "leoBaiduCookie", alternate = {"baiduCookie"})
    private String leoBaiduCookie = "";
    @SerializedName(value = "leoAliRefreshToken", alternate = {"aliRefreshToken"})
    private String leoAliRefreshToken = "";
    @SerializedName(value = "leoPan115Cookie", alternate = {"pan115Cookie"})
    private String leoPan115Cookie = "";
    @SerializedName(value = "leoPan123Username", alternate = {"pan123Username"})
    private String leoPan123Username = "";
    @SerializedName(value = "leoPan123Password", alternate = {"pan123Password"})
    private String leoPan123Password = "";
    @SerializedName(value = "leoXunleiUsername", alternate = {"xunleiUsername"})
    private String leoXunleiUsername = "";
    @SerializedName(value = "leoXunleiPassword", alternate = {"xunleiPassword"})
    private String leoXunleiPassword = "";
    @SerializedName(value = "leoPikpakUsername", alternate = {"pikpakUsername"})
    private String leoPikpakUsername = "";
    @SerializedName(value = "leoPikpakPassword", alternate = {"pikpakPassword"})
    private String leoPikpakPassword = "";
    @SerializedName(value = "leoTianyiAccount", alternate = {"tianyiAccount"})
    private String leoTianyiAccount = "";

    private int proxyType = 0;
    private boolean enableAutoTune = true;
    private int proxyThread = 8;
    private int proxyChunkSize = 256;
    private Map<String, SourceProxyConfig> proxySourceConfig = new HashMap<>();
    private String httpProxyUrl = "";
    private String ylhjHost = "";
    private String ylhjToken = "";

    public static class SourceProxyConfig {
        public int thread = 8;
        public int chunkSize = 256;
        public SourceProxyConfig() {}
        public SourceProxyConfig(int thread, int chunkSize) {
            this.thread = thread;
            this.chunkSize = chunkSize;
        }
    }

    public DanmakuConfig() {
        apiUrls = new HashSet<>();
        lpWidth = 1.0f;
        lpHeight = 1.0f;
        lpAlpha = 1.0f;
        autoPushEnabled = true;
        proxySourceConfig.put("ali", new SourceProxyConfig(8, 256));
        proxySourceConfig.put("quark", new SourceProxyConfig(10, 256));
        proxySourceConfig.put("uc", new SourceProxyConfig(10, 256));
        proxySourceConfig.put("a115", new SourceProxyConfig(5, 256));
        proxySourceConfig.put("a123", new SourceProxyConfig(5, 256));
        proxySourceConfig.put("a189", new SourceProxyConfig(5, 256));
        proxySourceConfig.put("a139", new SourceProxyConfig(5, 256));
        proxySourceConfig.put("xunlei", new SourceProxyConfig(5, 256));
        proxySourceConfig.put("pikpak", new SourceProxyConfig(5, 256));
        proxySourceConfig.put("baidu", new SourceProxyConfig(5, 256));
    }

    public String getPansouApiUrl() {
        return pansouApiUrl;
    }

    public void setPansouApiUrl(String pansouApiUrl) {
        this.pansouApiUrl = pansouApiUrl != null ? pansouApiUrl.trim() : "";
    }

    public String getPancheckApiUrl() {
        return pancheckApiUrl;
    }

    public void setPancheckApiUrl(String pancheckApiUrl) {
        this.pancheckApiUrl = pancheckApiUrl != null ? pancheckApiUrl.trim() : "";
    }

    public String getQuarkCookie() {
        return leoQuarkCookie;
    }

    public void setQuarkCookie(String leoQuarkCookie) {
        this.leoQuarkCookie = leoQuarkCookie != null ? leoQuarkCookie.trim() : "";
    }

    public String getUcCookie() {
        return leoUcCookie;
    }

    public void setUcCookie(String leoUcCookie) {
        this.leoUcCookie = leoUcCookie != null ? leoUcCookie.trim() : "";
    }

    public String getBaiduCookie() {
        return leoBaiduCookie;
    }

    public void setBaiduCookie(String leoBaiduCookie) {
        this.leoBaiduCookie = leoBaiduCookie != null ? leoBaiduCookie.trim() : "";
    }

    public String getAliRefreshToken() {
        return leoAliRefreshToken;
    }

    public void setAliRefreshToken(String leoAliRefreshToken) {
        this.leoAliRefreshToken = leoAliRefreshToken != null ? leoAliRefreshToken.trim() : "";
    }

    public String getPan115Cookie() {
        return leoPan115Cookie;
    }

    public void setPan115Cookie(String leoPan115Cookie) {
        this.leoPan115Cookie = leoPan115Cookie != null ? leoPan115Cookie.trim() : "";
    }

    public String getPan123Username() {
        return leoPan123Username;
    }

    public void setPan123Username(String leoPan123Username) {
        this.leoPan123Username = leoPan123Username != null ? leoPan123Username.trim() : "";
    }

    public String getPan123Password() {
        return leoPan123Password;
    }

    public void setPan123Password(String leoPan123Password) {
        this.leoPan123Password = leoPan123Password != null ? leoPan123Password.trim() : "";
    }

    public String getXunleiUsername() {
        return leoXunleiUsername;
    }

    public void setXunleiUsername(String leoXunleiUsername) {
        this.leoXunleiUsername = leoXunleiUsername != null ? leoXunleiUsername.trim() : "";
    }

    public String getXunleiPassword() {
        return leoXunleiPassword;
    }

    public void setXunleiPassword(String leoXunleiPassword) {
        this.leoXunleiPassword = leoXunleiPassword != null ? leoXunleiPassword.trim() : "";
    }

    public String getPikpakUsername() {
        return leoPikpakUsername;
    }

    public void setPikpakUsername(String leoPikpakUsername) {
        this.leoPikpakUsername = leoPikpakUsername != null ? leoPikpakUsername.trim() : "";
    }

    public String getPikpakPassword() {
        return leoPikpakPassword;
    }

    public void setPikpakPassword(String leoPikpakPassword) {
        this.leoPikpakPassword = leoPikpakPassword != null ? leoPikpakPassword.trim() : "";
    }

    public String getTianyiAccount() {
        return leoTianyiAccount;
    }

    public void setTianyiAccount(String leoTianyiAccount) {
        this.leoTianyiAccount = leoTianyiAccount != null ? leoTianyiAccount.trim() : "";
    }

    public Set<String> getApiUrls() {
        return apiUrls;
    }

    public void setApiUrls(Set<String> apiUrls) {
        this.apiUrls = apiUrls;
    }

    public float getLpWidth() {
        return lpWidth;
    }

    public void setLpWidth(float lpWidth) {
        this.lpWidth = lpWidth;
    }

    public float getLpHeight() {
        return lpHeight;
    }

    public void setLpHeight(float lpHeight) {
        this.lpHeight = lpHeight;
    }

    public float getLpAlpha() {
        return lpAlpha;
    }

    public void setLpAlpha(float lpAlpha) {
        this.lpAlpha = lpAlpha;
    }

    public boolean isAutoPushEnabled() {
        return autoPushEnabled;
    }

    public void setAutoPushEnabled(boolean autoPushEnabled) {
        this.autoPushEnabled = autoPushEnabled;
    }

    public boolean isPushToastEnabled() {
        return pushToastEnabled;
    }

    public void setPushToastEnabled(boolean pushToastEnabled) {
        this.pushToastEnabled = pushToastEnabled;
    }

    public int getTheme() {
        return theme;
    }

    public void setTheme(int theme) {
        this.theme = theme;
    }

    public int getDanmakuTimeOffsetMs() {
        return danmakuTimeOffsetMs;
    }

    public void setDanmakuTimeOffsetMs(int danmakuTimeOffsetMs) {
        this.danmakuTimeOffsetMs = danmakuTimeOffsetMs;
    }

    public int getProxyType() {
        return proxyType;
    }

    public void setProxyType(int proxyType) {
        this.proxyType = proxyType;
    }

    public boolean isEnableAutoTune() {
        return enableAutoTune;
    }

    public void setEnableAutoTune(boolean enableAutoTune) {
        this.enableAutoTune = enableAutoTune;
    }

    public int getProxyThread() {
        return proxyThread;
    }

    public void setProxyThread(int proxyThread) {
        this.proxyThread = proxyThread;
    }

    public int getProxyChunkSize() {
        return proxyChunkSize;
    }

    public void setProxyChunkSize(int proxyChunkSize) {
        this.proxyChunkSize = proxyChunkSize;
    }

    public Map<String, SourceProxyConfig> getProxySourceConfig() {
        return proxySourceConfig;
    }

    public void setProxySourceConfig(Map<String, SourceProxyConfig> proxySourceConfig) {
        this.proxySourceConfig = proxySourceConfig != null ? proxySourceConfig : new HashMap<>();
    }

    public String getHttpProxyUrl() {
        return httpProxyUrl;
    }

    public void setHttpProxyUrl(String httpProxyUrl) {
        this.httpProxyUrl = httpProxyUrl != null ? httpProxyUrl.trim() : "";
    }

    public String getYlhjHost() {
        return ylhjHost;
    }

    public void setYlhjHost(String ylhjHost) {
        this.ylhjHost = ylhjHost != null ? ylhjHost.trim() : "";
    }

    public String getYlhjToken() {
        return ylhjToken;
    }

    public void setYlhjToken(String ylhjToken) {
        this.ylhjToken = ylhjToken != null ? ylhjToken.trim() : "";
    }

    public void updateFromJson(JSONObject json) {
        if (json.has("apiUrls")) {
            Object urlsObj = json.opt("apiUrls");
            Set<String> newUrls = new HashSet<>();
            if (urlsObj instanceof JSONArray) {
                JSONArray urlsArray = (JSONArray) urlsObj;
                for (int i = 0; i < urlsArray.length(); i++) {
                    String url = urlsArray.optString(i);
                    if (!url.isEmpty()) {
                        newUrls.add(url);
                    }
                }
            } else if (urlsObj instanceof String) {
                String url = (String) urlsObj;
                if (!url.isEmpty()) {
                    newUrls.add(url);
                }
            }
            if (!newUrls.isEmpty()) {
                setApiUrls(newUrls);
            }
        }
        if (json.has("autoPushEnabled")) {
            setAutoPushEnabled(json.optBoolean("autoPushEnabled"));
        }
        if (json.has("lpWidth")) {
            setLpWidth((float) json.optDouble("lpWidth", 1.0));
        }
        if (json.has("lpHeight")) {
            setLpHeight((float) json.optDouble("lpHeight", 1.0));
        }
        if (json.has("lpAlpha")) {
            setLpAlpha((float) json.optDouble("lpAlpha", 1.0));
        }
        if (json.has("pushToastEnabled")) {
            setPushToastEnabled(json.optBoolean("pushToastEnabled"));
        }
        if (json.has("theme")) {
            setTheme(json.optInt("theme"));
        }
        if (json.has("danmakuTimeOffsetMs")) {
            setDanmakuTimeOffsetMs(json.optInt("danmakuTimeOffsetMs"));
        }
        if (json.has("pansouApiUrl")) {
            setPansouApiUrl(json.optString("pansouApiUrl"));
        }
        if (json.has("pancheckApiUrl")) {
            setPancheckApiUrl(json.optString("pancheckApiUrl"));
        }
        if (json.has("leoQuarkCookie") || json.has("quarkCookie")) {
            setQuarkCookie(json.optString(json.has("leoQuarkCookie") ? "leoQuarkCookie" : "quarkCookie"));
        }
        if (json.has("leoUcCookie") || json.has("ucCookie")) {
            setUcCookie(json.optString(json.has("leoUcCookie") ? "leoUcCookie" : "ucCookie"));
        }
        if (json.has("leoBaiduCookie") || json.has("baiduCookie")) {
            setBaiduCookie(json.optString(json.has("leoBaiduCookie") ? "leoBaiduCookie" : "baiduCookie"));
        }
        if (json.has("leoAliRefreshToken") || json.has("aliRefreshToken")) {
            setAliRefreshToken(json.optString(json.has("leoAliRefreshToken") ? "leoAliRefreshToken" : "aliRefreshToken"));
        }
        if (json.has("leoPan115Cookie") || json.has("pan115Cookie")) {
            setPan115Cookie(json.optString(json.has("leoPan115Cookie") ? "leoPan115Cookie" : "pan115Cookie"));
        }
        if (json.has("leoPan123Username") || json.has("pan123Username")) {
            setPan123Username(json.optString(json.has("leoPan123Username") ? "leoPan123Username" : "pan123Username"));
        }
        if (json.has("leoPan123Password") || json.has("pan123Password")) {
            setPan123Password(json.optString(json.has("leoPan123Password") ? "leoPan123Password" : "pan123Password"));
        }
        if (json.has("leoXunleiUsername") || json.has("xunleiUsername")) {
            setXunleiUsername(json.optString(json.has("leoXunleiUsername") ? "leoXunleiUsername" : "xunleiUsername"));
        }
        if (json.has("leoXunleiPassword") || json.has("xunleiPassword")) {
            setXunleiPassword(json.optString(json.has("leoXunleiPassword") ? "leoXunleiPassword" : "xunleiPassword"));
        }
        if (json.has("leoPikpakUsername") || json.has("pikpakUsername")) {
            setPikpakUsername(json.optString(json.has("leoPikpakUsername") ? "leoPikpakUsername" : "pikpakUsername"));
        }
        if (json.has("leoPikpakPassword") || json.has("pikpakPassword")) {
            setPikpakPassword(json.optString(json.has("leoPikpakPassword") ? "leoPikpakPassword" : "pikpakPassword"));
        }
        if (json.has("leoTianyiAccount") || json.has("tianyiAccount")) {
            setTianyiAccount(json.optString(json.has("leoTianyiAccount") ? "leoTianyiAccount" : "tianyiAccount"));
        }
        if (json.has("proxyType")) {
            setProxyType(json.optInt("proxyType", 0));
        }
        if (json.has("enableAutoTune")) {
            setEnableAutoTune(json.optBoolean("enableAutoTune"));
        }
        if (json.has("proxyThread")) {
            setProxyThread(json.optInt("proxyThread", 8));
        }
        if (json.has("proxyChunkSize")) {
            setProxyChunkSize(json.optInt("proxyChunkSize", 256));
        }
        if (json.has("proxySourceConfig")) {
            JSONObject srcObj = json.optJSONObject("proxySourceConfig");
            if (srcObj != null) {
                Map<String, SourceProxyConfig> map = new HashMap<>();
                for (String key : new String[]{"ali", "quark", "uc", "a115", "a123", "a189", "a139", "xunlei", "pikpak", "baidu"}) {
                    if (srcObj.has(key)) {
                        JSONObject sc = srcObj.optJSONObject(key);
                        if (sc != null) {
                            map.put(key, new SourceProxyConfig(
                                    sc.optInt("thread", 8),
                                    sc.optInt("chunkSize", 256)
                            ));
                        }
                    }
                }
                if (!map.isEmpty()) setProxySourceConfig(map);
            }
        }
        if (json.has("httpProxyUrl")) {
            setHttpProxyUrl(json.optString("httpProxyUrl"));
        }
        if (json.has("ylhjHost")) {
            setYlhjHost(json.optString("ylhjHost"));
        }
        if (json.has("ylhjToken")) {
            setYlhjToken(json.optString("ylhjToken"));
        }
    }
}
