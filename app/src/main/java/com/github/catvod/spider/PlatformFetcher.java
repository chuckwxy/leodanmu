package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PlatformFetcher {

    private static final String IQIYI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String TENCENT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String YOUKU_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String MANGOTV_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String KAN360_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final String SOGOU_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";

    // Tencent channel_id mapping: filter_key → tab_id
    private static final Map<String, String> TENCENT_TAB_IDS = new HashMap<>();
    static {
        TENCENT_TAB_IDS.put("tx_hot_movie", "100173");
        TENCENT_TAB_IDS.put("tx_hot_tv", "100113");
        TENCENT_TAB_IDS.put("tx_hot_zy", "100109");
        TENCENT_TAB_IDS.put("tx_hot_dm", "100119");
        TENCENT_TAB_IDS.put("ru_movie", "100173");
        TENCENT_TAB_IDS.put("ru_tv", "100113");
        TENCENT_TAB_IDS.put("ru_zy", "100109");
        TENCENT_TAB_IDS.put("ru_dm", "100119");
        TENCENT_TAB_IDS.put("tx_ru", "100173");
        // aliases for type-based calls
        TENCENT_TAB_IDS.put("movie", "100173");
        TENCENT_TAB_IDS.put("tv", "100113");
        TENCENT_TAB_IDS.put("variety", "100109");
        TENCENT_TAB_IDS.put("anime", "100119");
    }

    // Tencent lftxs (sort param) per type in latest context
    // hot context always uses "75"
    private static final Map<String, String> TENCENT_LFTXS_LATEST = new HashMap<>();
    static {
        TENCENT_LFTXS_LATEST.put("movie", "10");
        TENCENT_LFTXS_LATEST.put("tv", "79");
        TENCENT_LFTXS_LATEST.put("variety", "23");
        TENCENT_LFTXS_LATEST.put("anime", "23");
    }

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
            if (TextUtils.isEmpty(body)) {
                Leodanmu.log("platform POST body empty: " + url);
                return null;
            }
            return new JSONObject(body);
        } catch (JSONException e) {
            Leodanmu.log("platform POST JSON err: " + url + " -> " + e.getMessage().substring(0, Math.min(100, e.getMessage().length())));
            return null;
        } catch (Exception e) {
            Leodanmu.log("platform POST err: " + url + " -> " + e.getMessage());
            return null;
        }
    }

    private static String joinKeys(JSONObject obj) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(it.next());
        }
        return sb.toString();
    }

    private static JSONObject tencentPost(String url, String json) {
        try {
            RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
            Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Cookie", "video_platform=2;")
                .post(body)
                .build();
            OkHttpClient client = com.github.catvod.net.OkHttp.client();
            Response res = client.newCall(req).execute();
            String respBody = res.body() != null ? res.body().string() : null;
            if (TextUtils.isEmpty(respBody)) {
                Leodanmu.log("tencentPost body empty");
                return null;
            }
            return new JSONObject(respBody);
        } catch (Exception e) {
            Leodanmu.log("tencentPost err: " + e.getMessage());
            return null;
        }
    }

    private static String optStr(JSONObject obj, String... keys) {
        for (String k : keys) {
            String v = obj.optString(k);
            if (!TextUtils.isEmpty(v)) return v;
        }
        return "";
    }

    // ─── Tencent (POST API) ──────────────────────────────────────────────────
    // JS: _0x288ea5
    // URL: .../getPage?video_appid=3000010
    // Body: {page_context:{page_index}, page_params:{page_id, page_type, channel_id, filter_params, page}, page_bypass_params:{...}}
    // Response: data.CardList[last].children_list.list.cards

    public static JSONArray fetchTencent(String type, int page) {
        return fetchTencent(type, page, "75");
    }

    public static JSONArray fetchTencent(String type, int page, String lftxs) {
        JSONArray items = new JSONArray();
        if (TextUtils.isEmpty(lftxs) || "U".equals(lftxs)) lftxs = "75";
        // Map internal type to JS-compatible lftxc value
        // "75" → hot context: tx_hot_movie/tv/zy/dm
        // other ("10","79","23") → latest context: ru_movie/tv/zy/dm
        // If type already has "tx_" or "ru_" prefix, use as-is
        String lftxc = type;
        if (!type.startsWith("tx_") && !type.startsWith("ru_")) {
            if ("75".equals(lftxs)) {
                if ("movie".equals(type)) lftxc = "tx_hot_movie";
                else if ("tv".equals(type)) lftxc = "tx_hot_tv";
                else if ("variety".equals(type)) lftxc = "tx_hot_zy";
                else if ("anime".equals(type)) lftxc = "tx_hot_dm";
            } else {
                if ("movie".equals(type)) lftxc = "ru_movie";
                else if ("tv".equals(type)) lftxc = "ru_tv";
                else if ("variety".equals(type)) lftxc = "ru_zy";
                else if ("anime".equals(type)) lftxc = "ru_dm";
            }
        }
        String tabId = TENCENT_TAB_IDS.get(lftxc);
        if (tabId == null) return items;

        try {
            // Build URL matching JS: includes lftxs, lftxc, pg in query string
            int pg = page - 1;
            String url = "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage?video_appid=3000010&lftxs=" + lftxs + "&lftxc=" + lftxc + "&pg=" + pg;
            String filterParams = "sort=" + lftxs;

            JSONObject body = new JSONObject();
            JSONObject pageContext = new JSONObject();
            pageContext.put("page_index", pg);
            body.put("page_context", pageContext);

            JSONObject pageParams = new JSONObject();
            pageParams.put("page_id", "channel_list_second_page");
            pageParams.put("page_type", "operation");
            pageParams.put("channel_id", tabId);
            pageParams.put("filter_params", filterParams);
            pageParams.put("page", pg);
            body.put("page_params", pageParams);

            JSONObject bypassParams = new JSONObject();
            JSONObject innerParams = new JSONObject();
            innerParams.put("page_id", "channel_list_second_page");
            innerParams.put("page_type", "operation");
            innerParams.put("channel_id", tabId);
            innerParams.put("filter_params", filterParams);
            innerParams.put("page", pg);
            innerParams.put("caller_id", "3000010");
            innerParams.put("platform_id", "2");
            innerParams.put("data_mode", "default");
            innerParams.put("user_mode", "default");
            bypassParams.put("params", innerParams);
            bypassParams.put("scene", "operation");
            body.put("page_bypass_params", bypassParams);

            Leodanmu.log("腾请求 URL=" + url);
            Leodanmu.log("腾请求 body=" + body.toString());
            JSONObject data = tencentPost(url, body.toString());
            if (data == null) {
                Leodanmu.log("腾POST返回null");
                return items;
            }
            Leodanmu.log("腾POST OK ret=" + data.optInt("ret", -1) + " msg=" + data.optString("msg","") + " hasData=" + data.has("data"));

            JSONObject dataObj = data.optJSONObject("data");
            if (dataObj == null) {
                Leodanmu.log("腾data为空");
                return items;
            }
            JSONArray cardList = dataObj.optJSONArray("CardList");
            if (cardList == null || cardList.length() == 0) {
                Leodanmu.log("腾CardList为空 dataObj keys=" + joinKeys(dataObj));
                return items;
            }
            Leodanmu.log("腾CardList len=" + cardList.length() + " lastCard keys=" + joinKeys(cardList.optJSONObject(cardList.length()-1)));
            JSONObject lastCard = cardList.optJSONObject(cardList.length() - 1);
            if (lastCard == null) return items;
            JSONObject childrenList = lastCard.optJSONObject("children_list");
            if (childrenList == null) {
                Leodanmu.log("腾children_list为空, lastCard keys=" + joinKeys(lastCard));
                return items;
            }
            JSONObject list = childrenList.optJSONObject("list");
            if (list == null) {
                Leodanmu.log("腾list为空, childrenList keys=" + joinKeys(childrenList));
                return items;
            }
            JSONArray cards = list.optJSONArray("cards");
            if (cards == null) {
                Leodanmu.log("腾cards为空, list keys=" + joinKeys(list));
                return items;
            }
            Leodanmu.log("腾cards len=" + cards.length());

            for (int i = 0; i < cards.length(); i++) {
                try {
                    JSONObject card = cards.getJSONObject(i);
                    JSONObject params = card.optJSONObject("params");
                    if (params == null) continue;
                    String vid = params.optString("cid", "");
                    String remark = params.optString("timelong", "");
                    if (TextUtils.isEmpty(remark)) remark = params.optString("publish_date", "");
                    items.put(toVod(
                            "tencent_" + vid,
                            params.optString("title", ""),
                            params.optString("new_pic_vt", ""),
                            remark
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("腾讯视频请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── iQiyi ───────────────────────────────────────────────────────────────
    // JS: _0x5a9578
    // URL: mesh.if.iqiyi.com/portal/videolib/pcw/data?page_id={page}&channel_id={id}&mode=11
    // Response: json.data[] → {tv_id, title, image_url_normal, dq_updatestatus, sns_score}

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
            String url = "https://mesh.if.iqiyi.com/portal/videolib/pcw/data"
                    + "?page_id=" + page + "&channel_id=" + channelId + "&mode=11";
            JSONObject data = safeGet(url, headers(IQIYI_UA, "https://www.iqiyi.com/"));
            if (data == null) return items;

            JSONArray list = data.optJSONArray("data");
            if (list == null) return items;

            for (int i = 0; i < list.length(); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    String remark = item.optString("dq_updatestatus", "");
                    if (TextUtils.isEmpty(remark)) remark = item.optString("sns_score", "");
                    items.put(toVod(
                            "iqiyi_" + item.optString("tv_id", item.optString("id", "")),
                            item.optString("title", ""),
                            item.optString("image_url_normal", item.optString("img", "")),
                            remark
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("爱奇艺请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Youku ───────────────────────────────────────────────────────────────
    // JS: _0x4f73d7
    // URL: www.youku.com/category/data?params=...&pageNo={page}
    // Page 1: no session; page 2+: session restored from previous response
    // Response: data.filterData → {listData[], session, hasMore}

    private static final Map<String, String> YOUKU_TYPES = new HashMap<>();
    static {
        YOUKU_TYPES.put("movie", "电影");
        YOUKU_TYPES.put("tv", "电视剧");
        YOUKU_TYPES.put("variety", "综艺");
        YOUKU_TYPES.put("anime", "动漫");
    }

    private static final Map<String, String> YOUKU_SORTS = new HashMap<>();
    static {
        YOUKU_SORTS.put("hot", "7");
        YOUKU_SORTS.put("new", "1");
        YOUKU_SORTS.put("score", "8");
    }

    private static String youkuSession = null;

    public static JSONArray fetchYouku(String type, int page, String sort) {
        JSONArray items = new JSONArray();
        String typeName = YOUKU_TYPES.get(type);
        if (typeName == null) return items;
        String sortVal = YOUKU_SORTS.containsKey(sort) ? YOUKU_SORTS.get(sort) : "7";

        try {
            String params = "{\"type\":\"" + typeName + "\",\"sort\":" + sortVal + "}";
            String url = "https://www.youku.com/category/data"
                    + "?params=" + URLEncoder.encode(params, "UTF-8")
                    + "&pageNo=" + page;
            if (page == 1) {
                url += "&optionRefresh=1";
            } else if (youkuSession != null) {
                url += "&session=" + URLEncoder.encode(youkuSession, "UTF-8");
            }

            JSONObject data = safeGet(url, headers(YOUKU_UA, "https://www.youku.com/"));
            if (data == null) return items;

            JSONObject dataObj = data.optJSONObject("data");
            if (dataObj == null) return items;
            JSONObject filterData = dataObj.optJSONObject("filterData");
            if (filterData == null) return items;

            // save session for next page
            String session = filterData.optString("session");
            if (!TextUtils.isEmpty(session)) {
                youkuSession = session;
            }

            JSONArray list = filterData.optJSONArray("listData");
            if (list == null) return items;

            for (int i = 0; i < list.length(); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    items.put(toVod(
                            "youku_" + item.optString("videoLink", ""),
                            item.optString("title", ""),
                            item.optString("img", ""),
                            item.optString("summary", "")
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("优酷请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── MangoTV ─────────────────────────────────────────────────────────────
    // JS: _0x15747c
    // URL: pianku.api.mgtv.com/rider/list/pcweb/v3?channelId=3&pn={page}&pc=80&hudong=1&_support=10000000&sort=c2
    // Response: data.hitDocs[] → {clipId, title, img, updateInfo}

    public static JSONArray fetchMangoTV(String channelId, int page) {
        JSONArray items = new JSONArray();
        if (channelId == null) channelId = "3";

        try {
            String url = "https://pianku.api.mgtv.com/rider/list/pcweb/v3"
                    + "?allowedRC=1&platform=pcweb&channelId=" + channelId
                    + "&pn=" + page + "&pc=80&hudong=1&_support=10000000&sort=c2";
            JSONObject data = safeGet(url, headers(MANGOTV_UA, "https://www.mgtv.com/"));
            if (data == null) return items;

            JSONObject dataObj = data.optJSONObject("data");
            if (dataObj == null) return items;
            JSONArray hitDocs = dataObj.optJSONArray("hitDocs");
            if (hitDocs == null) return items;

            for (int i = 0; i < hitDocs.length(); i++) {
                try {
                    JSONObject item = hitDocs.getJSONObject(i);
                    items.put(toVod(
                            "mgtv_" + item.optString("clipId", ""),
                            item.optString("title", ""),
                            item.optString("img", ""),
                            item.optString("updateInfo", "")
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("芒果TV请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── 360kan ──────────────────────────────────────────────────────────────
    // JS: _0x4c8ff5
    // URL: api.web.360kan.com/v1/filter/list?catid={id}&rank=rankhot&size=35&pageno={page}
    // Response: data.movies[] → {id, title, cdncover(//), upinfo, pubdate}

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
            String url = "https://api.web.360kan.com/v1/filter/list?catid=" + catId
                    + "&rank=rankhot&size=35&pageno=" + page;
            JSONObject data = safeGet(url, headers(KAN360_UA, "https://www.360kan.com/"));
            if (data == null) return items;
            JSONObject dataObj = data.optJSONObject("data");
            if (dataObj == null) return items;

            JSONArray movies = dataObj.optJSONArray("movies");
            if (movies == null) return items;

            for (int i = 0; i < movies.length(); i++) {
                try {
                    JSONObject item = movies.getJSONObject(i);
                    String img = item.optString("cdncover", "");
                    if (img.startsWith("//")) img = "https:" + img;
                    String remark = item.optString("upinfo", "");
                    if (!TextUtils.isEmpty(remark)) {
                        remark = "更新至" + remark + "集";
                    } else {
                        remark = item.optString("pubdate", "");
                    }
                    items.put(toVod(
                            "360kan_" + item.optString("id", item.optString("movieId", "")),
                            item.optString("title", item.optString("name", "")),
                            img,
                            remark
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("360kan请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Sogou ───────────────────────────────────────────────────────────────
    // JS: _0x101667
    // URL: shipin.sogou.com/napi/video/classlist?listTab={tab}&start={start}&len=15
    // Response: listData.results[] → {dockey, name, picurl, score}

    private static final Map<String, String> SOGOU_TABS = new HashMap<>();
    static {
        SOGOU_TABS.put("movie", "film");
        SOGOU_TABS.put("tv", "teleplay");
        SOGOU_TABS.put("variety", "show");
        SOGOU_TABS.put("anime", "cartoon");
    }

    public static JSONArray fetchSogou(String listTab, int page) {
        JSONArray items = new JSONArray();
        if (listTab == null) listTab = "film";

        try {
            int start = (page - 1) * 15;
            String url = "https://shipin.sogou.com/napi/video/classlist"
                    + "?abtest=8&spver=0&listTab=" + listTab
                    + "&filter=&start=" + start + "&len=15&emcee=&fr=filter&style=&zone=&year=&fee=&order=";
            JSONObject data = safeGet(url, headers(SOGOU_UA, "https://shipin.sogou.com/list?listTab=" + listTab));
            if (data == null) return items;

            JSONObject listData = data.optJSONObject("listData");
            if (listData == null) return items;
            JSONArray results = listData.optJSONArray("results");
            if (results == null) return items;

            for (int i = 0; i < results.length(); i++) {
                try {
                    JSONObject item = results.getJSONObject(i);
                    items.put(toVod(
                            "sogou_" + item.optString("dockey", ""),
                            item.optString("name", item.optString("title", "")),
                            item.optString("picurl", item.optString("img", "")),
                            item.optString("score", "")
                    ));
                } catch (JSONException ignored) {}
            }
        } catch (Exception e) {
            Leodanmu.log("搜狗视频请求失败: " + e.getMessage());
        }
        return items;
    }

    // ─── Tencent Ranking (v.qq.com HTML parse) ──────────────────────────────
    // JS: _0x9a0c50
    public static java.util.List<JSONObject> fetchTencentRank(String type, String slug, int page) {
        java.util.List<JSONObject> items = new java.util.ArrayList<>();
        try {
            String channel = "tv".equals(type) ? "tv" : "movie";
            String url = "https://v.qq.com/biu/ranks/?t=hotplay&channel=" + channel + "&ct=" + slug;
            String body = OkHttp.string(url, headers(TENCENT_UA, "https://v.qq.com/"));
            if (TextUtils.isEmpty(body)) return items;
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
    // Uses the "videolib" API which returns JSON data with tvid/title/img/mainIndex
    public static JSONArray fetchIqiyiRank(String slug, int page) {
        JSONArray items = new JSONArray();
        try {
            String[] parts = slug.split("/");
            String page_st = parts.length >= 3 ? parts[2] : "2";
            String category_id = parts.length >= 2 ? parts[1] : "1";
            String url = "https://mesh.if.iqiyi.com/portal/pcw/rankList/comSecRankList?page_st=" + page_st + "&category_id=" + category_id + "&pg_num=" + page;
            JSONObject data = safeGet(url, headers(IQIYI_UA, "https://www.iqiyi.com/"));
            if (data == null) return items;
            JSONArray list = parseList(data, new String[]{"data", "items", "0", "contents"});
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    try {
                        JSONObject item = list.getJSONObject(i);
                        String img = item.optString("img", "").replaceAll("\\.jpg$", "_260_360.jpg");
                        items.put(toVod(
                                "iqiyi_rank_" + item.optString("tvid", item.optString("id", "")),
                                item.optString("title", item.optString("name", "")),
                                img,
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

    // ─── Youku Ranking (HTML parse) ───────────────────────────────────────────
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
                                        String img = item.optString("img", "").replaceAll("^//", "https://");
                                        items.put(toVod(
                                                "youku_rank_" + item.optString("videoLink", "").replaceAll(".*?(\\d+).*", "$1"),
                                                item.optString("title", ""),
                                                img,
                                                item.optString("summary", "")
                                        ));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // fallback: use category API with correct response path
            if (items.length() == 0) {
                String typeName = "movie".equals(type) ? "电影" : "电视剧";
                String params = "{\"type\":\"" + typeName + "\",\"sort\":7}";
                String catUrl = "https://www.youku.com/category/data?params=" + URLEncoder.encode(params, "UTF-8") + "&optionRefresh=1&pageNo=" + page;
                JSONObject data = safeGet(catUrl, headers(YOUKU_UA, "https://www.youku.com/"));
                if (data != null) {
                    JSONObject rankData = data.optJSONObject("data");
                    if (rankData == null) return items;
                    JSONObject filterData = rankData.optJSONObject("filterData");
                    JSONArray list = filterData != null ? filterData.optJSONArray("listData") : null;
                    if (list != null) {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject item = list.getJSONObject(i);
                            items.put(toVod(
                                    "youku_rank_" + item.optString("videoLink", ""),
                                    item.optString("title", ""),
                                    item.optString("img", ""),
                                    item.optString("summary", "")
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
            JSONObject data = safeGet(url, headers(KAN360_UA, "https://www.360kan.com/"));
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

    // Tencent latest context lftxs lookup
    public static String tencentLatestLftxs(String typeName) {
        String lftxs = TENCENT_LFTXS_LATEST.get(typeName);
        return lftxs != null ? lftxs : "23";
    }

    public static JSONArray fetchPlatform(String platform, String type, int page, String sort) {
        if ("iqiyi".equals(platform)) {
            return fetchIqiyi(type, page);
        } else if ("tencent".equals(platform)) {
            return fetchTencent(type, page, sort);
        } else if ("youku".equals(platform)) {
            return fetchYouku(type, page, sort);
        } else if ("mgtv".equals(platform)) {
            String channelId = "3";
            if ("tv".equals(type)) channelId = "2";
            else if ("variety".equals(type)) channelId = "1";
            else if ("anime".equals(type)) channelId = "50";
            return fetchMangoTV(channelId, page);
        } else if ("360kan".equals(platform)) {
            String catId = KAN360_CATS.containsKey(type) ? KAN360_CATS.get(type) : "1";
            return fetch360kan(catId, page);
        } else if ("sogou".equals(platform)) {
            String tab = SOGOU_TABS.containsKey(type) ? SOGOU_TABS.get(type) : "film";
            return fetchSogou(tab, page);
        }
        return new JSONArray();
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
                } catch (NumberFormatException ignored) {}
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
