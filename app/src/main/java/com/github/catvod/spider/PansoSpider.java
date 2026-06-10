package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PansoSpider extends Spider {

    private static final String DEFAULT_PANSO_API = "http://192.168.31.77:5888";
    private static final String DEFAULT_PANCHECK_API = "http://192.168.31.77:8989";

    private String pansoApi;
    private String pancheckApi;
    private DriveManager driveManager;
    private DanmakuConfig config;

    private static final Map<String, String> DRIVE_MAP = new HashMap<>();

    static {
        DRIVE_MAP.put("ali", "ali");
        DRIVE_MAP.put("quark", "quark");
        DRIVE_MAP.put("uc", "uc");
        DRIVE_MAP.put("pikpak", "pikpak");
        DRIVE_MAP.put("xunlei", "xunlei");
        DRIVE_MAP.put("a123", "a123");
        DRIVE_MAP.put("a189", "a189");
        DRIVE_MAP.put("a139", "a139");
        DRIVE_MAP.put("a115", "a115");
        DRIVE_MAP.put("baidu", "baidu");
    }

    private static final String[] DRIVE_ORDER = {
        "quark", "baidu", "uc", "a115", "ali", "a123", "a189", "a139", "xunlei", "pikpak"
    };

    @Override
    public void init(Context context, String extend) throws Exception {
        pansoApi = DEFAULT_PANSO_API;
        pancheckApi = DEFAULT_PANCHECK_API;

        if (context != null) {
            config = DanmakuConfigManager.getConfig(context);
            if (config != null) {
                if (!TextUtils.isEmpty(config.getPansouApiUrl())) {
                    pansoApi = config.getPansouApiUrl();
                }
                if (!TextUtils.isEmpty(config.getPancheckApiUrl())) {
                    pancheckApi = config.getPancheckApiUrl();
                }
            }
        }

        if (!TextUtils.isEmpty(extend)) {
            try {
                if (extend.startsWith("{")) {
                    JSONObject obj = new JSONObject(extend);
                    if (obj.has("panso")) pansoApi = obj.optString("panso", pansoApi);
                    if (obj.has("pancheck")) pancheckApi = obj.optString("pancheck", pancheckApi);
                } else if (!extend.startsWith("csp")) {
                    pansoApi = extend.trim();
                }
            } catch (Exception ignored) {}
        }

        driveManager = new DriveManager();
        driveManager.init(config);

        SpiderDebug.log("PansoSpider init: panso=" + pansoApi + " pancheck=" + pancheckApi);
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            JSONObject cls = new JSONObject();
            cls.put("type_id", "1");
            cls.put("type_name", "\u76D8\u641C");
            classes.put(cls);
            JSONObject result = new JSONObject();
            result.put("class", classes);
            result.put("list", new JSONArray());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            JSONObject item = new JSONObject();
            item.put("vod_id", "1");
            item.put("vod_name", "\u7EAF\u641C\u65E0\u5206\u7C7B");
            item.put("vod_pic", "");
            item.put("vod_remarks", "");
            JSONArray list = new JSONArray();
            list.put(item);
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", 1);
            result.put("pagecount", 1);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    private String buildSearchUrl() {
        if (pansoApi.endsWith("/api/search")) return pansoApi;
        String base = pansoApi;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/api/search";
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        if (TextUtils.isEmpty(key)) return "{\"list\":[]}";
        try {
            JSONArray cloudTypes = new JSONArray();
            for (String dk : DRIVE_ORDER) {
                String ct = DRIVE_MAP.get(dk);
                if (ct != null) cloudTypes.put(ct);
            }
            JSONObject req = new JSONObject();
            req.put("kw", key);
            req.put("cloud_types", cloudTypes);
            Leodanmu.log("PansoSpider search: key=" + key + " api=" + buildSearchUrl() + " drives=" + cloudTypes);
            String response = OkHttp.post(buildSearchUrl(), req.toString());
            if (TextUtils.isEmpty(response)) {
                Leodanmu.log("PansoSpider search: empty response");
                return "{\"list\":[]}";
            }
            JSONObject data = new JSONObject(response);
            int code = data.optInt("code");
            if (code != 0) {
                Leodanmu.log("PansoSpider search: api code=" + code);
                return "{\"list\":[]}";
            }
            JSONObject dataObj = data.optJSONObject("data");
            if (dataObj == null) {
                Leodanmu.log("PansoSpider search: no data object");
                return "{\"list\":[]}";
            }
            JSONObject merged = dataObj.optJSONObject("merged_by_type");
            if (merged == null) {
                Leodanmu.log("PansoSpider search: no merged_by_type");
                return "{\"list\":[]}";
            }
            JSONArray results = new JSONArray();
            int total = 0;
            for (String dk : DRIVE_ORDER) {
                String ct = DRIVE_MAP.get(dk);
                if (ct == null) continue;
                JSONArray items = merged.optJSONArray(ct);
                if (items == null) continue;
                int driveCount = 0;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String url = item.optString("url", "");
                    if (TextUtils.isEmpty(url)) continue;
                    String note = item.optString("note", "");
                    JSONArray images = item.optJSONArray("images");
                    String pic = (images != null && images.length() > 0) ? images.optString(0, "").trim() : "";
                    String datetime = item.optString("datetime", "");
                    String source = item.optString("source", "").replaceAll("plugin:", "plg:");
                    String remarks = "";
                    if (!TextUtils.isEmpty(source)) remarks += source;
                    if (!TextUtils.isEmpty(datetime)) {
                        if (!TextUtils.isEmpty(remarks)) remarks += " | ";
                        remarks += datetime.length() >= 10 ? datetime.substring(2, 10) : datetime;
                    }
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", url);
                    vod.put("vod_name", note);
                    vod.put("vod_pic", pic);
                    vod.put("vod_remarks", remarks);
                    vod.put("vod_tag", dk);
                    vod.put("vod_content", "");
                    results.put(vod);
                    driveCount++;
                    total++;
                }
                if (driveCount > 0) Leodanmu.log("PansoSpider search: " + dk + " counts=" + driveCount);
            }
            Leodanmu.log("PansoSpider search: total results=" + total + " for key=" + key);
            JSONObject result = new JSONObject();
            result.put("list", results);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("PansoSpider search error: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String shareUrl = ids.get(0);
        Leodanmu.log("PansoSpider detail: url=" + shareUrl);
        try {
            JSONObject vod = driveManager.getVod(shareUrl);
            if (vod != null) {
                String playUrl = vod.optString("vod_play_url", "");
                String playFrom = vod.optString("vod_play_from", "");
                Leodanmu.log("PansoSpider detail: from=" + playFrom + " playUrl(len)=" + playUrl.length());
                JSONArray list = new JSONArray();
                list.put(vod);
                JSONObject result = new JSONObject();
                result.put("list", list);
                return result.toString();
            }
            Leodanmu.log("PansoSpider detail: driveManager.getVod returned null for " + shareUrl);
        } catch (Exception e) {
            Leodanmu.log("PansoSpider detail error: " + e.getMessage());
        }
        return "";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Leodanmu.log("PansoSpider player: flag=" + flag + " id=" + id);
        if (TextUtils.isEmpty(id)) return "";
        try {
            JSONObject result = driveManager.play(flag, id);
            if (result != null) {
                Object urlObj = result.opt("url");
                String urlStr = urlObj != null ? urlObj.toString() : "null";
                Leodanmu.log("PansoSpider player: result url=" + urlStr.substring(0, Math.min(urlStr.length(), 200)));
                return result.toString();
            }
            Leodanmu.log("PansoSpider player: driveManager.play returned null");
        } catch (Exception e) {
            Leodanmu.log("PansoSpider player error: " + e.getMessage());
        }
        Leodanmu.log("PansoSpider player: falling back to direct url=" + id);
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", id);
            JSONObject header = new JSONObject();
            header.put("User-Agent", "Mozilla/5.0");
            result.put("header", header);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
