package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
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
    private String pansouApiUrl = "";
    private String quarkCookie = "";
    private String ucCookie = "";
    private String baiduCookie = "";

    public DanmakuConfig() {
        apiUrls = new HashSet<>();
        lpWidth = 1.0f;
        lpHeight = 1.0f;
        lpAlpha = 1.0f;
        autoPushEnabled = true;
    }

    public String getPansouApiUrl() {
        return pansouApiUrl;
    }

    public void setPansouApiUrl(String pansouApiUrl) {
        this.pansouApiUrl = pansouApiUrl != null ? pansouApiUrl.trim() : "";
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
        if (json.has("quarkCookie")) {
            setQuarkCookie(json.optString("quarkCookie"));
        }
        if (json.has("ucCookie")) {
            setUcCookie(json.optString("ucCookie"));
        }
        if (json.has("baiduCookie")) {
            setBaiduCookie(json.optString("baiduCookie"));
        }
    }
}