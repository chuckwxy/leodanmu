package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 弹幕配置实体类
 */
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
    private String quarkCookie = "";
    private String ucCookie = "";
    private String baiduCookie = "";
    private String aliRefreshToken = "";
    private String pan115Cookie = "";
    private String pan123Username = "";
    private String pan123Password = "";
    private String xunleiUsername = "";
    private String xunleiPassword = "";
    private String pikpakUsername = "";
    private String pikpakPassword = "";
    private String tianyiAccount = "";
    private int proxyType = 0; // 0=自动 1=Go代理 2=Java代理
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
        SourceProxyConfig ali = new SourceProxyConfig(8, 256);
        proxySourceConfig.put("ali", ali);
        SourceProxyConfig quark = new SourceProxyConfig(10, 256);
        proxySourceConfig.put("quark", quark);
        SourceProxyConfig uc = new SourceProxyConfig(10, 256);
        proxySourceConfig.put("uc", uc);
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
        return quarkCookie;
    }

    public void setQuarkCookie(String quarkCookie) {
        this.quarkCookie = quarkCookie != null ? quarkCookie.trim() : "";
    }

    public String getUcCookie() {
        return ucCookie;
    }

    public void setUcCookie(String ucCookie) {
        this.ucCookie = ucCookie != null ? ucCookie.trim() : "";
    }

    public String getBaiduCookie() {
        return baiduCookie;
    }

    public void setBaiduCookie(String baiduCookie) {
        this.baiduCookie = baiduCookie != null ? baiduCookie.trim() : "";
    }

    public String getAliRefreshToken() {
        return aliRefreshToken;
    }

    public void setAliRefreshToken(String aliRefreshToken) {
        this.aliRefreshToken = aliRefreshToken != null ? aliRefreshToken.trim() : "";
    }

    public String getPan115Cookie() {
        return pan115Cookie;
    }

    public void setPan115Cookie(String pan115Cookie) {
        this.pan115Cookie = pan115Cookie != null ? pan115Cookie.trim() : "";
    }

    public String getPan123Username() {
        return pan123Username;
    }

    public void setPan123Username(String pan123Username) {
        this.pan123Username = pan123Username != null ? pan123Username.trim() : "";
    }

    public String getPan123Password() {
        return pan123Password;
    }

    public void setPan123Password(String pan123Password) {
        this.pan123Password = pan123Password != null ? pan123Password.trim() : "";
    }

    public String getXunleiUsername() {
        return xunleiUsername;
    }

    public void setXunleiUsername(String xunleiUsername) {
        this.xunleiUsername = xunleiUsername != null ? xunleiUsername.trim() : "";
    }

    public String getXunleiPassword() {
        return xunleiPassword;
    }

    public void setXunleiPassword(String xunleiPassword) {
        this.xunleiPassword = xunleiPassword != null ? xunleiPassword.trim() : "";
    }

    public String getPikpakUsername() {
        return pikpakUsername;
    }

    public void setPikpakUsername(String pikpakUsername) {
        this.pikpakUsername = pikpakUsername != null ? pikpakUsername.trim() : "";
    }

    public String getPikpakPassword() {
        return pikpakPassword;
    }

    public void setPikpakPassword(String pikpakPassword) {
        this.pikpakPassword = pikpakPassword != null ? pikpakPassword.trim() : "";
    }

    public String getTianyiAccount() {
        return tianyiAccount;
    }

    public void setTianyiAccount(String tianyiAccount) {
        this.tianyiAccount = tianyiAccount != null ? tianyiAccount.trim() : "";
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

    /**
     * 从JSON对象更新配置
     * @param json JSON对象，可包含 apiUrls、autoPushEnabled、lpWidth、lpHeight、lpAlpha、pushToastEnabled、theme
     */
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
                // 兼容单字符串形式
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
        if (json.has("quarkCookie")) {
            setQuarkCookie(json.optString("quarkCookie"));
        }
        if (json.has("ucCookie")) {
            setUcCookie(json.optString("ucCookie"));
        }
        if (json.has("baiduCookie")) {
            setBaiduCookie(json.optString("baiduCookie"));
        }
        if (json.has("aliRefreshToken")) {
            setAliRefreshToken(json.optString("aliRefreshToken"));
        }
        if (json.has("pan115Cookie")) {
            setPan115Cookie(json.optString("pan115Cookie"));
        }
        if (json.has("pan123Username")) {
            setPan123Username(json.optString("pan123Username"));
        }
        if (json.has("pan123Password")) {
            setPan123Password(json.optString("pan123Password"));
        }
        if (json.has("xunleiUsername")) {
            setXunleiUsername(json.optString("xunleiUsername"));
        }
        if (json.has("xunleiPassword")) {
            setXunleiPassword(json.optString("xunleiPassword"));
        }
        if (json.has("pikpakUsername")) {
            setPikpakUsername(json.optString("pikpakUsername"));
        }
        if (json.has("pikpakPassword")) {
            setPikpakPassword(json.optString("pikpakPassword"));
        }
        if (json.has("tianyiAccount")) {
            setTianyiAccount(json.optString("tianyiAccount"));
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
                for (String key : new String[]{"ali", "quark", "uc"}) {
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