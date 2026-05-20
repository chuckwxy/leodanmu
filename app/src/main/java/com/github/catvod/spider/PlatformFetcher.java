package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class PlatformFetcher {

    private static final String IQIYI_HOST = "https://mesh.if.iqiyi.com";
    private static final String IQIYI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String TENCENT_HOST = "https://pbaccess.video.qq.com";
    private static final String TENCENT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String YOUKU_HOST = "https://www.youku.com";
    private static final String YOUKU_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String MANGOTV_HOST = "https://pianku.api.mgtv.com";
    private static final String MANGOTV_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String KAN360_HOST = "https://api.web.360kan.com";
    private static final String SOGOU_HOST = "https://shipin.sogou.com";
    private static final int PAGE_SIZE = 40;

    private static Map<String, String> headers(String ua, String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", ua);
        if (!TextUtils.isEmpty(referer)) h.put("Referer", referer);
        return h;
    }

    private static JSONObject safeGet(String url, Map<String, String> headers) {
        try {
            String body = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            Leodanmu.log("平台请求失败: " + url + " -> " + e.getMessage());
            return null;
        }
    }

    private static JSONObject safePost(String url, String bodyJson, Map<String, String> headers) {
        try {
            String body = OkHttp.post(url, bodyJson, headers).getBody();
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            Leodanmu.log("平台POST请求失败: " + url + " -> " + e.getMessage());
            return null;
        }
    }

    // ─── iQiyi ──────────────────────────────────────────────────────────────

    private static final Map<String, String> IQIYI_CHANNELS = new HashMap<>();
    static {
        IQIYI_CHANNELS.put("movie", "1");
        IQIYI_CHANNELS.put("tv", "2");
        IQIYI_CHANNELS.put("variety", "4");
        IQIYI_CHANNELS.put("anime", "7");
    }

    public static JSONArray fetchIqiyi(String type, int page) {
        JSONArray items = new JSONArray();
        String channelId = IQIYI_CHANNELS.get(type);
        if (channelId == null) return items;

        try {
            String url = IQIYI_HOST + "/portal/videolib/pcw/data?page_id=" + channelId
                    + "&page_num=" + page + "&page_size=" + PAGE_SIZE;
            JSONObject data = safeGet(url, headers(IQIYI_UA, "https://www.iqiyi.com/"));
            if (data == null) return items;

            JSONArray list = parseList(data, new String[]{"data", "vlist"}, new String[]{"data", "list"});
            if (list == null) list = data.optJSONArray("data");
            if (list == null) list = data.optJSONArray("vlist");

            for (int i = 0; i < (list != null ? list.length() : 0); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    items.put(toVod(
                            "iqiyi_" + item.optString("vid", item.optString("id", "")),
                            item.optString("name", item.optString("title", "")),
                            item.optString("pic", item.optString("img", "")),
                            item.optString("score", item.optString("rating", ""))
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("爱奇艺请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Tencent Video (pbaccess POST API) ─────────────────────────────────

    private static final Map<String, String> TENCENT_TABS = new HashMap<>();
    static {
        TENCENT_TABS.put("movie", "100001");
        TENCENT_TABS.put("tv", "100002");
        TENCENT_TABS.put("variety", "100003");
        TENCENT_TABS.put("anime", "100119");
    }

    public static JSONArray fetchTencent(String type, int page) {
        JSONArray items = new JSONArray();
        String tabId = TENCENT_TABS.get(type);
        if (tabId == null) return items;

        try {
            String url = TENCENT_HOST + "/trpc.vector_layout.page_view.PageService/getPage"
                    + "?video_appid=3000010&lftxs=lftxc="
                    + "&page_id=channel_" + tabId + "_list"
                    + "&page_num=" + page + "&page_size=" + PAGE_SIZE;
            String body = "{\"page_context\":{},\"page_params\":{\"page_id\":\"channel_" + tabId + "_list\",\"page_type\":\"channel_list\",\"page_size\":" + PAGE_SIZE + ",\"page_num\":" + page + "}}";

            JSONObject data = safePost(url, body, headers(TENCENT_UA, "https://v.qq.com/"));
            if (data == null) return items;

            JSONArray list = parseList(data, new String[]{"data", "card_item_list"}, new String[]{"data", "list"});
            if (list == null) {
                JSONObject dataObj = data.optJSONObject("data");
                if (dataObj != null) list = dataObj.optJSONArray("card_item_list");
            }

            for (int i = 0; i < (list != null ? list.length() : 0); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    JSONObject resource = item.optJSONObject("resource");
                    if (resource == null) resource = item;
                    JSONObject cover = resource.optJSONObject("cover");
                    String img = "";
                    if (cover != null) {
                        img = cover.optString("url", "");
                        if (TextUtils.isEmpty(img)) img = cover.optString("h", "");
                    }
                    if (TextUtils.isEmpty(img)) img = resource.optString("pic", "");
                    items.put(toVod(
                            "tencent_" + resource.optString("vid", resource.optString("id", "")),
                            resource.optString("title", item.optString("name", "")),
                            img,
                            resource.optString("score", resource.optString("mark", ""))
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("腾讯视频请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Youku ───────────────────────────────────────────────────────────────

    private static final Map<String, String> YOUKU_TYPES = new HashMap<>();
    static {
        YOUKU_TYPES.put("movie", "电影");
        YOUKU_TYPES.put("tv", "电视剧");
        YOUKU_TYPES.put("variety", "综艺");
        YOUKU_TYPES.put("anime", "动漫");
    }

    private static final Map<String, String> YOUKU_SORTS = new HashMap<>();
    static {
        YOUKU_SORTS.put("hot", "1");
        YOUKU_SORTS.put("new", "7");
        YOUKU_SORTS.put("score", "8");
    }

    public static JSONArray fetchYouku(String type, int page, String sort) {
        JSONArray items = new JSONArray();
        String typeName = YOUKU_TYPES.get(type);
        if (typeName == null) return items;
        String sortVal = YOUKU_SORTS.containsKey(sort) ? YOUKU_SORTS.get(sort) : "1";

        try {
            String params = "{\"type\":\"" + typeName + "\",\"sort\":" + sortVal + "}";
            String url = YOUKU_HOST + "/category/data"
                    + "?params=" + URLEncoder.encode(params, "UTF-8")
                    + "&optionRefresh=1&pageNo=" + page;
            JSONObject data = safeGet(url, headers(YOUKU_UA, "https://www.youku.com/"));
            if (data == null) return items;

            JSONObject dataObj = data.optJSONObject("data");
            JSONArray list = null;
            if (dataObj != null) list = dataObj.optJSONArray("list");
            if (list == null) list = data.optJSONArray("list");
            if (list == null) list = data.optJSONArray("results");
            if (list == null) list = data.optJSONArray("videos");

            for (int i = 0; i < (list != null ? list.length() : 0); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    items.put(toVod(
                            "youku_" + item.optString("id", item.optString("videoId", "")),
                            item.optString("title", item.optString("name", "")),
                            item.optString("pic", item.optString("img", item.optString("poster", ""))),
                            item.optString("score", item.optString("rating", ""))
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("优酷请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── MangoTV ─────────────────────────────────────────────────────────────

    private static final Map<String, String> MANGOTV_CHANNELS = new HashMap<>();
    static {
        MANGOTV_CHANNELS.put("movie", "3");
        MANGOTV_CHANNELS.put("tv", "2");
        MANGOTV_CHANNELS.put("variety", "1");
        MANGOTV_CHANNELS.put("anime", "50");
    }

    public static JSONArray fetchMangoTV(String channelId, int page) {
        JSONArray items = new JSONArray();
        if (channelId == null) channelId = "1";

        try {
            String url = MANGOTV_HOST + "/rider/list/pcweb/v3"
                    + "?allowedRC=1&platform=pcweb&channelId=" + channelId
                    + "&pn=" + page;
            JSONObject data = safeGet(url, headers(MANGOTV_UA, "https://www.mgtv.com/"));
            if (data == null) return items;

            JSONObject dataObj = data.optJSONObject("data");
            JSONArray list = null;
            if (dataObj != null) list = dataObj.optJSONArray("list");
            if (list == null) list = data.optJSONArray("list");

            for (int i = 0; i < (list != null ? list.length() : 0); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    items.put(toVod(
                            "mgtv_" + item.optString("id", item.optString("clipId", "")),
                            item.optString("name", item.optString("title", "")),
                            item.optString("pic", item.optString("img", item.optString("poster", ""))),
                            item.optString("score", item.optString("rating", ""))
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("芒果TV请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── 360kan ──────────────────────────────────────────────────────────────

    private static final Map<String, String> KAN360_CATS = new HashMap<>();
    static {
        KAN360_CATS.put("movie", "1");
        KAN360_CATS.put("tv", "2");
        KAN360_CATS.put("variety", "3");
        KAN360_CATS.put("anime", "4");
    }

    public static JSONArray fetch360kan(String catId, int page) {
        JSONArray items = new JSONArray();

        try {
            String url = KAN360_HOST + "/v1/filter/list?catid=" + catId
                    + "&rank=ranklatest&size=" + PAGE_SIZE + "&pageno=" + page;
            JSONObject data = safeGet(url, headers(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                    "https://www.360kan.com/"));
            if (data == null) return items;

            JSONObject result = data.optJSONObject("data");
            JSONArray list = null;
            if (result != null) list = result.optJSONArray("list");
            if (list == null) list = data.optJSONArray("list");
            if (list == null) list = data.optJSONArray("result");

            for (int i = 0; i < (list != null ? list.length() : 0); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    String img = item.optString("pic", item.optString("img", ""));
                    if (TextUtils.isEmpty(img)) img = item.optString("cover", "");
                    items.put(toVod(
                            "360kan_" + item.optString("id", item.optString("movieId", "")),
                            item.optString("name", item.optString("title", "")),
                            img,
                            item.optString("score", item.optString("rating", ""))
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("360kan请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Sogou ───────────────────────────────────────────────────────────────

    private static final Map<String, String> SOGOU_TABS = new HashMap<>();
    static {
        SOGOU_TABS.put("movie", "movie");
        SOGOU_TABS.put("tv", "teleplay");
        SOGOU_TABS.put("variety", "show");
        SOGOU_TABS.put("anime", "cartoon");
    }

    public static JSONArray fetchSogou(String listTab, int page) {
        JSONArray items = new JSONArray();
        if (listTab == null) listTab = "movie";

        try {
            String url = SOGOU_HOST + "/napi/video/classlist"
                    + "?abtest=8&spver=0&listTab=" + listTab
                    + "&filter=&start=" + (page - 1) * PAGE_SIZE;
            JSONObject data = safeGet(url, headers(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                    "https://shipin.sogou.com/"));
            if (data == null) return items;

            JSONObject result = data.optJSONObject("data");
            JSONObject resultData = null;
            JSONArray list = null;
            if (result != null) {
                resultData = result.optJSONObject("result");
                if (resultData != null) list = resultData.optJSONArray("list");
            }
            if (list == null) list = data.optJSONArray("list");

            for (int i = 0; i < (list != null ? list.length() : 0); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    items.put(toVod(
                            "sogou_" + item.optString("id", item.optString("vid", "")),
                            item.optString("name", item.optString("title", "")),
                            item.optString("pic", item.optString("img", item.optString("cover", ""))),
                            item.optString("score", item.optString("rating", ""))
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("搜狗视频请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Tencent Ranking ──────────────────────────────────────────────────────
    public static java.util.List<JSONObject> fetchTencentRank(String type, String slug, int page) {
        java.util.List<JSONObject> items = new java.util.ArrayList<>();
        try {
            String channel = "tv".equals(type) ? "tv" : "movie";
            String url = "https://v.qq.com/biu/ranks/?t=hotplay&channel=" + channel + "&ct=" + slug;
            String body = OkHttp.string(url, headers(TENCENT_UA, "https://v.qq.com/"));
            if (TextUtils.isEmpty(body)) return items;
            // Parse using regex to extract JSON
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<div class=\"mod_sunmenu_box\"[\\s\\S]*?</div>").matcher(body);
            if (m.find()) {
                String block = m.group();
                java.util.regex.Matcher itemM = java.util.regex.Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>\\s*<img[^>]*alt=\"([^\"]*)\"[^>]*src=\"([^\"]*)\"").matcher(block);
                while (itemM.find()) {
                    String href = itemM.group(1);
                    String alt = itemM.group(2);
                    String src = itemM.group(3);
                    if (TextUtils.isEmpty(href)) continue;
                    String vid = href.replaceAll(".*?/(\\w+)\\.html$", "$1");
                    if (vid.equals(href)) vid = href;
                    String img = src.startsWith("//") ? "https:" + src : src;
                    JSONObject vod = toVod("tencent_rank_" + vid, alt, img, "");
                    items.add(vod);
                }
            }
        } catch (Exception e) {
            Leodanmu.log("腾讯榜单请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── iQiyi Ranking ────────────────────────────────────────────────────────
    // slug format: "ranks1/{category_id}/{page_st}" e.g. "ranks1/1/2"
    public static JSONArray fetchIqiyiRank(String slug, int page) {
        JSONArray items = new JSONArray();
        try {
            String[] parts = slug.split("/");
            String page_st = parts.length >= 3 ? parts[2] : "2";
            String category_id = parts.length >= 2 ? parts[1] : "1";
            String url = IQIYI_HOST + "/portal/pcw/rankList/comSecRankList?page_st=" + page_st + "&category_id=" + category_id + "&pg_num=" + page;
            JSONObject data = safeGet(url, headers(IQIYI_UA, "https://www.iqiyi.com/"));
            if (data == null) return items;
            JSONArray list = parseList(data, new String[]{"data", "items", "0", "contents"});
            if (list == null) list = data.optJSONArray("data");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    try {
                        JSONObject item = list.getJSONObject(i);
                        items.put(toVod(
                                "iqiyi_rank_" + item.optString("tvid", item.optString("id", "")),
                                item.optString("title", item.optString("name", "")),
                                item.optString("img", "").replaceAll("\\.jpg$", "_260_360.jpg"),
                                "热度:" + item.optString("mainIndex", "")
                        ));
                    } catch (JSONException ignored) {}
                }
            }
        } catch (Exception e) {
            Leodanmu.log("爱奇艺榜单请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Youku Ranking ────────────────────────────────────────────────────────
    public static JSONArray fetchYoukuRank(String type, int index, int page) {
        JSONArray items = new JSONArray();
        try {
            int moduleIdx = "movie".equals(type) ? 3 : 5;
            String channel = "movie".equals(type) ? "webmovie" : "webtv";
            String url = "https://www.youku.com/channel/" + channel;
            String body = OkHttp.string(url, headers(YOUKU_UA, "https://www.youku.com/"));
            if (TextUtils.isEmpty(body)) return items;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("window\\.__INITIAL_DATA__ =\\s*(\\{[\\s\\S]*?\\});\\s*window\\.__PAGE_CONF").matcher(body);
            if (m.find()) {
                String jsonStr = m.group(1);
                jsonStr = jsonStr.replaceAll("/undefined/g", "\"\"");
                JSONObject data = new JSONObject(jsonStr);
                JSONArray moduleList = data.optJSONArray("moduleList");
                if (moduleList != null && moduleList.length() > moduleIdx) {
                    JSONObject module = moduleList.optJSONObject(moduleIdx);
                    if (module != null) {
                        JSONArray components = module.optJSONArray("components");
                        if (components != null && components.length() > index) {
                            JSONObject comp = components.optJSONObject(index);
                            if (comp != null) {
                                JSONArray itemList = comp.optJSONArray("itemList");
                                if (itemList != null) {
                                    for (int i = 0; i < itemList.length(); i++) {
                                        JSONObject item = itemList.getJSONObject(i);
                                        items.put(toVod(
                                                "youku_rank_" + item.optString("videoLink", "").replaceAll(".*?(\\d+).*", "$1"),
                                                item.optString("title", ""),
                                                item.optString("img", "").replaceAll("^//", "https://"),
                                                item.optString("summary", "")
                                        ));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // fallback: use category API
            if (items.length() == 0) {
                String typeName = "movie".equals(type) ? "电影" : "电视剧";
                String params = "{\"type\":\"" + typeName + "\",\"sort\":1}";
                String catUrl = YOUKU_HOST + "/category/data?params=" + URLEncoder.encode(params, "UTF-8") + "&optionRefresh=1&pageNo=" + page;
                JSONObject data = safeGet(catUrl, headers(YOUKU_UA, "https://www.youku.com/"));
                if (data != null) {
                    JSONObject dataObj = data.optJSONObject("data");
                    JSONArray list = null;
                    if (dataObj != null) list = dataObj.optJSONArray("list");
                    if (list == null) list = data.optJSONArray("list");
                    if (list != null) {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject item = list.getJSONObject(i);
                            items.put(toVod(
                                    "youku_rank_" + item.optString("id", item.optString("videoId", "")),
                                    item.optString("title", item.optString("name", "")),
                                    item.optString("pic", item.optString("img", item.optString("poster", ""))),
                                    item.optString("score", item.optString("rating", ""))
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Leodanmu.log("优酷榜单请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── 360kan Ranking ───────────────────────────────────────────────────────
    public static JSONArray fetch360kanRank(String catId) {
        JSONArray items = new JSONArray();
        try {
            String url = "https://api.web.360kan.com/v1/rank?cat=" + catId;
            JSONObject data = safeGet(url, headers(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                    "https://www.360kan.com/"));
            if (data == null) return items;
            JSONArray list = data.optJSONArray("data");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    items.put(toVod(
                            "360kan_rank_" + item.optString("ent_id", ""),
                            item.optString("title", ""),
                            item.optString("cover", ""),
                            item.optString("pubdate", "")
                    ));
                }
            }
        } catch (Exception e) {
            Leodanmu.log("360kan榜单请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Unified category dispatcher ─────────────────────────────────────────

    public static JSONArray fetchPlatform(String platform, String type, int page, String sort) {
        JSONArray items = new JSONArray();

        if ("iqiyi".equals(platform)) {
            items = fetchIqiyi(type, page);
        } else if ("tencent".equals(platform)) {
            items = fetchTencent(type, page);
        } else if ("youku".equals(platform)) {
            items = fetchYouku(type, page, sort);
        } else if ("mgtv".equals(platform)) {
            String channelId = "1";
            if ("movie".equals(type)) channelId = "3";
            else if ("tv".equals(type)) channelId = "2";
            else if ("variety".equals(type)) channelId = "1";
            else if ("anime".equals(type)) channelId = "50";
            items = fetchMangoTV(channelId, page);
        } else if ("360kan".equals(platform)) {
            String catId = KAN360_CATS.containsKey(type) ? KAN360_CATS.get(type) : "1";
            items = fetch360kan(catId, page);
        } else if ("sogou".equals(platform)) {
            String tab = SOGOU_TABS.containsKey(type) ? SOGOU_TABS.get(type) : "movie";
            items = fetchSogou(tab, page);
        }

        return items;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static JSONArray parseList(JSONObject data, String[]... paths) {
        for (String[] path : paths) {
            try {
                JSONObject obj = data;
                JSONArray arr = null;
                for (int i = 0; i < path.length; i++) {
                    if (i == path.length - 1) {
                        arr = obj.optJSONArray(path[i]);
                    } else {
                        obj = obj.optJSONObject(path[i]);
                        if (obj == null) break;
                    }
                }
                if (arr != null && arr.length() > 0) return arr;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static JSONObject toVod(String id, String name, String pic, String remark) {
        JSONObject vod = new JSONObject();
        try {
            if (TextUtils.isEmpty(id)) id = "plat_" + System.currentTimeMillis();
            if (TextUtils.isEmpty(name)) name = "未知";
            if (TextUtils.isEmpty(pic)) pic = "";
            if (!TextUtils.isEmpty(remark) && !remark.contains("分")) {
                try {
                    double score = Double.parseDouble(remark);
                    if (score > 0) remark = "评分: " + String.format("%.1f", score) + "分";
                } catch (NumberFormatException ignored) {
                    // keep original
                }
            }
            if (TextUtils.isEmpty(remark)) remark = "暂无评分";
            vod.put("vod_id", id.trim());
            vod.put("vod_name", name);
            vod.put("vod_pic", pic);
            vod.put("vod_remarks", remark);
            vod.put("goSearch", true);
        } catch (JSONException ignored) {}
        return vod;
    }
}
