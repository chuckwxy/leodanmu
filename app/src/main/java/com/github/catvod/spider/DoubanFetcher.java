package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DoubanFetcher {

    private static final String HOST = "https://frodo.douban.com/api/v2";
    private static final String SEARCH_HOST = "https://movie.douban.com";
    private static final String API_KEY = "0ac44ae016490db2204ce0a042db2916";
    private static final String API_UA = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.143 Safari/537.36 MicroMessenger/7.0.9.501 NetType/WIFI MiniProgramEnv/Windows WindowsWechat";
    private static final String API_REFERER = "https://servicewechat.com/wx2f9b06c1de1ccfca/84/page-frame.html";
    private static final String IMG_REFERER = "https://m.douban.com/";
    private static final String IMG_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36";
    private static final String[] IMG_DOMAINS = {"img1.doubanio.com", "img2.doubanio.com", "img9.doubanio.com"};
    private static final Random RANDOM = new Random();
    private static final Pattern DOUBAN_IMG_PATTERN = Pattern.compile("https://img\\d*\\.doubanio\\.com");
    private static final int COUNT = 40;
    private static boolean sDebugLogged = false;

    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "latest", "movie", "tv", "show", "anime", "hot_movie", "hot_tv", "hot_show", "top_250",
            "movie_filter", "tv_filter"
    ));

    public static boolean isDouban(String tid) {
        return CATEGORIES.contains(tid);
    }

    public static JSONArray getCategories() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(classObj("latest", "\u6700\u8fd1\u66f4\u65b0"));
        arr.put(classObj("movie", "\u8c46\u74e3\u7535\u5f71"));
        arr.put(classObj("tv", "\u8c46\u74e3\u5267\u96c6"));
        arr.put(classObj("show", "\u8c46\u74e3\u7efc\u827a"));
        arr.put(classObj("anime", "\u70ed\u64ad\u52a8\u6f2b"));
        arr.put(classObj("hot_movie", "\u7535\u5f71\u699c\u5355"));
        arr.put(classObj("hot_tv", "\u5267\u96c6\u699c\u5355"));
        arr.put(classObj("hot_show", "\u7efc\u827a\u699c\u5355"));
        arr.put(classObj("top_250", "\u7535\u5f71Top250"));
        arr.put(classObj("movie_filter", "\u7535\u5f71\u7b5b\u9009"));
        arr.put(classObj("tv_filter", "\u7535\u89c6\u7b5b\u9009"));
        return arr;
    }

    public static JSONObject getFilterConfig() throws Exception {
        JSONObject root = new JSONObject();

        root.put("movie", buildFilters(
                filter("类型", "类型", "", new String[][]{
                        {"全部类型", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"动作", "动作"},
                        {"科幻", "科幻"}, {"动画", "动画"}, {"悬疑", "悬疑"}, {"犯罪", "犯罪"},
                        {"惊悚", "惊悚"}, {"恐怖", "恐怖"}, {"纪录片", "纪录片"}
                }),
                filter("地区", "地区", "", new String[][]{
                        {"全部地区", ""}, {"华语", "华语"}, {"欧美", "欧美"}, {"韩国", "韩国"},
                        {"日本", "日本"}, {"中国大陆", "中国大陆"}, {"美国", "美国"}, {"中国香港", "中国香港"}
                }),
                filter("年代", "年代", "", new String[][]{
                        {"全部年代", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                        {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"},
                        {"2010年代", "2010年代"}, {"2000年代", "2000年代"}, {"90年代", "90年代"}, {"80年代", "80年代"}
                }),
                filter("sort", "排序", "U", new String[][]{
                        {"近期热度", "U"}, {"综合排序", "T"}, {"首映时间", "R"}, {"高分优先", "S"}
                })
        ));

        root.put("tv", buildFilters(
                filter("形式", "形式", "", new String[][]{
                        {"全部类型", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"悬疑", "悬疑"},
                        {"武侠", "武侠"}, {"古装", "古装"}, {"历史", "历史"}, {"剧情", "剧情"}
                }),
                filter("地区", "地区", "", new String[][]{
                        {"全部地区", ""}, {"华语", "华语"}, {"欧美", "欧美"}, {"韩国", "韩国"},
                        {"日本", "日本"}, {"中国大陆", "中国大陆"}, {"美国", "美国"}, {"英国", "英国"}, {"中国香港", "中国香港"}
                }),
                filter("年代", "年代", "", new String[][]{
                        {"全部年代", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                        {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"},
                        {"2010年代", "2010年代"}
                }),
                filter("平台", "平台", "", new String[][]{
                        {"全部平台", ""}, {"腾讯视频", "腾讯视频"}, {"爱奇艺", "爱奇艺"},
                        {"优酷", "优酷"}, {"Netflix", "Netflix"}, {"HBO", "HBO"}
                }),
                filter("sort", "排序", "U", new String[][]{
                        {"近期热度", "U"}, {"综合排序", "T"}, {"首播时间", "R"}, {"高分优先", "S"}
                })
        ));

        root.put("show", buildFilters(
                filter("类型", "类型", "", new String[][]{
                        {"全部类型", ""}, {"真人秀", "真人秀"}, {"脱口秀", "脱口秀"},
                        {"音乐", "音乐"}, {"喜剧", "喜剧"}, {"纪实", "纪实"}
                }),
                filter("地区", "地区", "", new String[][]{
                        {"全部地区", ""}, {"中国大陆", "中国大陆"}, {"韩国", "韩国"},
                        {"港台", "港台"}, {"欧美", "欧美"}
                }),
                filter("年代", "年代", "", new String[][]{
                        {"全部年代", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                        {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"}
                }),
                filter("sort", "排序", "U", new String[][]{
                        {"近期热度", "U"}, {"综合排序", "T"}, {"首播时间", "R"}, {"高分优先", "S"}
                })
        ));

        root.put("hot_movie", buildFilters(
                filter("slug", "榜单", "all", new String[][]{
                        {"全部榜单", "all"}, {"实时热门电影", "movie_real_time_hotest"}, {"一周口碑电影榜", "movie_weekly_best"}
                })
        ));

        root.put("hot_tv", buildFilters(
                filter("slug", "榜单", "all", new String[][]{
                        {"全部榜单", "all"}, {"实时热门剧集", "tv_real_time_hotest"},
                        {"华语口碑剧集榜", "tv_chinese_best_weekly"}, {"全球口碑剧集榜", "tv_global_best_weekly"},
                        {"综艺热播榜", "tv_variety_show"}
                })
        ));

        root.put("hot_show", buildFilters(
                filter("slug", "榜单", "all", new String[][]{
                        {"全部榜单", "all"}, {"近期热门综艺", "tv_variety_show"}, {"国内口碑综艺榜", "show_chinese_best_weekly"},
                        {"国外口碑综艺榜", "show_global_best_weekly"}
                })
        ));

        root.put("top_250", buildFilters(
                filter("slug", "榜单", "movie_top250", new String[][]{
                        {"豆瓣电影Top250", "movie_top250"}
                })
        ));

        root.put("latest", buildFilters(
                filter("sort", "排序", "R", new String[][]{
                        {"最近上映", "R"}, {"近期热度", "U"}, {"综合排序", "T"}, {"高分优先", "S"}
                })
        ));

        root.put("anime", buildFilters(
                filter("类型", "类型", "", new String[][]{
                        {"全部类型", ""}, {"动画电影", "动画"}, {"日本动漫", "日本动漫"}, {"国产动漫", "国产动漫"}
                }),
                filter("地区", "地区", "", new String[][]{
                        {"全部地区", ""}, {"日本", "日本"}, {"中国大陆", "中国大陆"}, {"美国", "美国"}, {"欧洲", "欧洲"}
                }),
                filter("年代", "年代", "", new String[][]{
                        {"全部年代", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"}, {"2023", "2023"},
                        {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"}, {"2010年代", "2010年代"}, {"2000年代", "2000年代"}
                }),
                filter("sort", "排序", "U", new String[][]{
                        {"近期热度", "U"}, {"综合排序", "T"}, {"首映时间", "R"}, {"高分优先", "S"}
                })
        ));

        root.put("movie_filter", buildFilters(
                filter("tag", "类型", "", new String[][]{
                        {"全部类型", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"动作", "动作"},
                        {"科幻", "科幻"}, {"动画", "动画"}, {"悬疑", "悬疑"}, {"犯罪", "犯罪"},
                        {"惊悚", "惊悚"}, {"恐怖", "恐怖"}, {"纪录片", "纪录片"}, {"剧情", "剧情"},
                        {"战争", "战争"}, {"奇幻", "奇幻"}, {"冒险", "冒险"}, {"武侠", "武侠"},
                        {"古装", "古装"}, {"历史", "历史"}, {"运动", "运动"}, {"歌舞", "歌舞"},
                        {"音乐", "音乐"}, {"家庭", "家庭"}, {"儿童", "儿童"}, {"青春", "青春"}
                }),
                filter("sort", "排序", "time", new String[][]{
                        {"最新", "time"}, {"评分", "rank"}, {"热度", "recommend"}
                })
        ));

        root.put("tv_filter", buildFilters(
                filter("tag", "类型", "", new String[][]{
                        {"全部类型", ""}, {"国产剧", "国产剧"}, {"海外剧", "海外剧"}, {"综艺", "综艺"},
                        {"动漫", "动漫"}, {"纪录片", "纪录片"}, {"喜剧", "喜剧"}, {"悬疑", "悬疑"},
                        {"爱情", "爱情"}, {"古装", "古装"}, {"剧情", "剧情"}, {"动作", "动作"},
                        {"科幻", "科幻"}, {"奇幻", "奇幻"}, {"犯罪", "犯罪"}
                }),
                filter("sort", "排序", "time", new String[][]{
                        {"最新", "time"}, {"评分", "rank"}, {"热度", "recommend"}
                })
        ));

        return root;
    }

    public static JSONArray fetchHomeList() {
        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();

        List<String> sources = Arrays.asList("latest", "hot_movie", "hot_tv", "hot_show");
        for (String id : sources) {
            try {
                JSONObject data = fetchCategoryInternal(id, 1, null);
                if (data == null) continue;
                JSONArray list = data.optJSONArray("list");
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    String vid = item.optString("vod_id");
                    if (TextUtils.isEmpty(vid) || seen.contains(vid)) continue;
                    seen.add(vid);
                    merged.put(item);
                    if (merged.length() >= 120) return merged;
                }
            } catch (Exception ignored) {
            }
        }

        return merged;
    }

    public static JSONObject fetchCategory(String id, int page, Map<String, String> filters) {
        try {
            if (!isDouban(id)) return null;
            JSONObject result = fetchCategoryInternal(id, page, filters);
            if (result == null) {
                result = new JSONObject();
                result.put("list", new JSONArray());
                result.put("page", page);
                result.put("pagecount", 1);
                result.put("limit", COUNT);
                result.put("total", 0);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject fetchCategoryInternal(String id, int page, Map<String, String> filters) throws Exception {
        int pg = Math.max(page, 1);
        int start = (pg - 1) * COUNT;
        String slug = (filters != null && filters.containsKey("slug")) ? filters.get("slug") : "all";
        String sort = (filters != null && filters.containsKey("sort")) ? filters.get("sort") : "U";

        JSONArray items = new JSONArray();
        int total = 0;

        if ("hot_movie".equals(id) || "hot_tv".equals(id) || "hot_show".equals(id)) {
            List<String> slugs;
            if ("hot_movie".equals(id)) slugs = Arrays.asList("movie_real_time_hotest", "movie_weekly_best");
            else if ("hot_tv".equals(id)) slugs = Arrays.asList("tv_real_time_hotest", "tv_chinese_best_weekly", "tv_global_best_weekly", "tv_variety_show");
            else slugs = Arrays.asList("tv_variety_show", "show_chinese_best_weekly", "show_global_best_weekly");

            if ("all".equals(slug)) {
                for (String s : slugs) {
                    JSONObject data = requestDouban(HOST + "/subject_collection/" + s + "/items?start=" + start + "&count=" + COUNT);
                    if (data != null) mergeItems(items, data.optJSONArray("subject_collection_items"));
                }
            } else {
                JSONObject data = requestDouban(HOST + "/subject_collection/" + slug + "/items?start=" + start + "&count=" + COUNT);
                if (data != null) {
                    mergeItems(items, data.optJSONArray("subject_collection_items"));
                    JSONObject coll = data.optJSONObject("subject_collection");
                    total = coll != null ? coll.optInt("total", 100) : 100;
                }
            }
            if (total <= 0) total = Math.max(items.length(), COUNT);

        } else if ("top_250".equals(id)) {
            JSONObject data = requestDouban(HOST + "/subject_collection/movie_top250/items?start=" + start + "&count=" + COUNT);
            if (data != null) {
                mergeItems(items, data.optJSONArray("subject_collection_items"));
                total = data.optInt("total", 250);
            }
            if (total <= 0) total = 250;

        } else if ("latest".equals(id)) {
            JSONObject movieData = requestDouban(HOST + "/movie/recommend?sort=R&start=" + start + "&count=" + COUNT);
            if (movieData != null) {
                mergeItems(items, movieData.optJSONArray("items"));
                total += movieData.optInt("total", 200);
            }
            JSONObject tvData = requestDouban(HOST + "/tv/recommend?sort=R&start=" + start + "&count=" + COUNT);
            if (tvData != null) {
                mergeItems(items, tvData.optJSONArray("items"));
                total += tvData.optInt("total", 200);
            }
            if (total <= 0) total = items.length() + COUNT;

        } else if ("anime".equals(id)) {
            String animeType = (filters != null) ? filters.get("类型") : null;
            if (TextUtils.isEmpty(animeType)) animeType = "动画";
            String animeRegion = (filters != null) ? filters.get("地区") : null;
            String animeYear = (filters != null) ? filters.get("年代") : null;
            StringBuilder tags = new StringBuilder(animeType);
            appendTag(tags, animeRegion);
            appendTag(tags, animeYear);
            String tagStr = tags.toString();
            JSONObject movieData = requestDouban(HOST + "/movie/recommend?tags=" + URLEncoder.encode(tagStr, "UTF-8") + "&sort=" + sort + "&start=" + start + "&count=" + COUNT);
            if (movieData != null) {
                mergeItems(items, movieData.optJSONArray("items"));
                total += movieData.optInt("total", 100);
            }
            JSONObject tvData = requestDouban(HOST + "/tv/recommend?tags=" + URLEncoder.encode(tagStr + ",日本动漫,动漫", "UTF-8") + "&sort=" + sort + "&start=" + start + "&count=" + COUNT);
            if (tvData != null) {
                mergeItems(items, tvData.optJSONArray("items"));
                total += tvData.optInt("total", 100);
            }
            if (total <= 0) total = items.length() + COUNT;

        } else if ("movie_filter".equals(id) || "tv_filter".equals(id)) {
            String type = "movie_filter".equals(id) ? "movie" : "tv";
            String tag = (filters != null && filters.containsKey("tag")) ? filters.get("tag") : "";
            String searchSort = (filters != null && filters.containsKey("sort")) ? filters.get("sort") : "time";
            JSONObject data = requestMovieSearch(type, tag, start, COUNT, searchSort);
            if (data != null && data.has("subjects")) {
                JSONArray subs = data.optJSONArray("subjects");
                for (int i = 0; i < subs.length(); i++) {
                    items.put(subs.getJSONObject(i));
                }
                total = items.length() + COUNT;
            }
            if (total <= 0) total = COUNT;

        } else if ("movie".equals(id) || "tv".equals(id) || "show".equals(id)) {
            String typeStr = "";
            if ("tv".equals(id)) typeStr = "电视剧";
            if ("show".equals(id)) typeStr = "综艺";

            String tagType = (filters != null) ? filters.get("类型") : null;
            if (TextUtils.isEmpty(tagType)) tagType = (filters != null) ? filters.get("形式") : null;
            String tagRegion = (filters != null) ? filters.get("地区") : null;
            String tagYear = (filters != null) ? filters.get("年代") : null;
            String tagPlatform = (filters != null) ? filters.get("平台") : null;

            StringBuilder tags = new StringBuilder();
            appendTag(tags, tagType);
            appendTag(tags, tagRegion);
            appendTag(tags, tagYear);
            appendTag(tags, tagPlatform);
            appendTag(tags, typeStr);

            String ep = ("tv".equals(id) || "show".equals(id)) ? "tv/recommend" : "movie/recommend";
            String url = HOST + "/" + ep + "?tags=" + URLEncoder.encode(tags.toString(), "UTF-8") + "&sort=" + sort + "&start=" + start + "&count=" + COUNT;
            JSONObject data = requestDouban(url);
            if (data != null) {
                mergeItems(items, data.optJSONArray("items"));
                total = data.optInt("total", 999);
            }
            if (total <= 0) total = 999;
        }

        int offset = start + COUNT;
        for (int round = 0; round < 3 && items.length() < COUNT; round++) {
            int before = items.length();
            if ("hot_movie".equals(id) || "hot_tv".equals(id) || "hot_show".equals(id)) {
                if ("all".equals(slug)) {
                    List<String> slugs;
                    if ("hot_movie".equals(id)) slugs = Arrays.asList("movie_real_time_hotest", "movie_weekly_best");
                    else if ("hot_tv".equals(id)) slugs = Arrays.asList("tv_real_time_hotest", "tv_chinese_best_weekly", "tv_global_best_weekly", "tv_variety_show");
                    else slugs = Arrays.asList("tv_variety_show", "show_chinese_best_weekly", "show_global_best_weekly");
                    for (String s : slugs) {
                        JSONObject data = requestDouban(HOST + "/subject_collection/" + s + "/items?start=" + offset + "&count=" + COUNT);
                        if (data != null) mergeItems(items, data.optJSONArray("subject_collection_items"));
                    }
                } else {
                    JSONObject data = requestDouban(HOST + "/subject_collection/" + slug + "/items?start=" + offset + "&count=" + COUNT);
                    if (data != null) mergeItems(items, data.optJSONArray("subject_collection_items"));
                }
            } else if ("top_250".equals(id)) {
                JSONObject data = requestDouban(HOST + "/subject_collection/movie_top250/items?start=" + offset + "&count=" + COUNT);
                if (data != null) mergeItems(items, data.optJSONArray("subject_collection_items"));
            } else if ("latest".equals(id)) {
                JSONObject md = requestDouban(HOST + "/movie/recommend?sort=R&start=" + offset + "&count=" + COUNT);
                if (md != null) mergeItems(items, md.optJSONArray("items"));
                JSONObject td = requestDouban(HOST + "/tv/recommend?sort=R&start=" + offset + "&count=" + COUNT);
                if (td != null) mergeItems(items, td.optJSONArray("items"));
            } else if ("anime".equals(id)) {
                String animeType2 = (filters != null) ? filters.get("类型") : null;
                if (TextUtils.isEmpty(animeType2)) animeType2 = "动画";
                String animeRegion2 = (filters != null) ? filters.get("地区") : null;
                String animeYear2 = (filters != null) ? filters.get("年代") : null;
                StringBuilder tags2 = new StringBuilder(animeType2);
                appendTag(tags2, animeRegion2);
                appendTag(tags2, animeYear2);
                String tagStr2 = tags2.toString();
                JSONObject md = requestDouban(HOST + "/movie/recommend?tags=" + URLEncoder.encode(tagStr2, "UTF-8") + "&sort=" + sort + "&start=" + offset + "&count=" + COUNT);
                if (md != null) mergeItems(items, md.optJSONArray("items"));
                JSONObject td = requestDouban(HOST + "/tv/recommend?tags=" + URLEncoder.encode(tagStr2 + ",日本动漫,动漫", "UTF-8") + "&sort=" + sort + "&start=" + offset + "&count=" + COUNT);
                if (td != null) mergeItems(items, td.optJSONArray("items"));
            } else if ("movie_filter".equals(id) || "tv_filter".equals(id)) {
                String type2 = "movie_filter".equals(id) ? "movie" : "tv";
                String tag2 = (filters != null && filters.containsKey("tag")) ? filters.get("tag") : "";
                String searchSort2 = (filters != null && filters.containsKey("sort")) ? filters.get("sort") : "time";
                JSONObject data = requestMovieSearch(type2, tag2, offset, COUNT, searchSort2);
                if (data != null && data.has("subjects")) {
                    JSONArray subs = data.optJSONArray("subjects");
                    for (int i = 0; i < subs.length(); i++) {
                        items.put(subs.getJSONObject(i));
                    }
                }
            } else if ("movie".equals(id) || "tv".equals(id) || "show".equals(id)) {
                String ep = ("tv".equals(id) || "show".equals(id)) ? "tv/recommend" : "movie/recommend";
                String tagStr = "";
                String tagType2 = (filters != null) ? filters.get("类型") : null;
                if (TextUtils.isEmpty(tagType2)) tagType2 = (filters != null) ? filters.get("形式") : null;
                String tagRegion2 = (filters != null) ? filters.get("地区") : null;
                String tagYear2 = (filters != null) ? filters.get("年代") : null;
                String tagPlatform2 = (filters != null) ? filters.get("平台") : null;
                StringBuilder tags2 = new StringBuilder();
                appendTag(tags2, tagType2);
                appendTag(tags2, tagRegion2);
                appendTag(tags2, tagYear2);
                appendTag(tags2, tagPlatform2);
                tagStr = tags2.toString();
                String url = HOST + "/" + ep + "?tags=" + URLEncoder.encode(tagStr, "UTF-8") + "&sort=" + sort + "&start=" + offset + "&count=" + COUNT;
                JSONObject data = requestDouban(url);
                if (data != null) mergeItems(items, data.optJSONArray("items"));
            }
            if (items.length() == before) break;
            offset += COUNT;
        }

        JSONArray mapped = mapItems(items);

        Set<String> seen = new HashSet<>();
        JSONArray finalList = new JSONArray();
        for (int i = 0; i < mapped.length(); i++) {
            JSONObject v = mapped.optJSONObject(i);
            if (v == null) continue;
            String vid = v.optString("vod_id");
            if (seen.contains(vid)) continue;
            seen.add(vid);
            finalList.put(v);
            if (finalList.length() >= COUNT) break;
        }

        JSONObject result = new JSONObject();
        result.put("list", finalList);
        result.put("page", pg);
        result.put("pagecount", Math.max((int) Math.ceil((double) Math.max(total, finalList.length()) / COUNT), pg + 1));
        result.put("limit", COUNT);
        result.put("total", Math.max(total, finalList.length()));
        return result;
    }

    private static void appendTag(StringBuilder sb, String tag) {
        if (!TextUtils.isEmpty(tag)) {
            if (sb.length() > 0) sb.append(",");
            sb.append(tag);
        }
    }

    private static void mergeItems(JSONArray target, JSONArray source) {
        if (source == null) return;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < target.length(); i++) {
            JSONObject item = target.optJSONObject(i);
            if (item != null) {
                String id = item.optString("id");
                if (!TextUtils.isEmpty(id)) seen.add(id);
            }
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (TextUtils.isEmpty(id) || seen.contains(id)) continue;
            seen.add(id);
            target.put(item);
        }
    }

    private static JSONArray mapItems(JSONArray items) {
        JSONArray list = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            try {
                JSONObject raw = items.getJSONObject(i);
                if (!sDebugLogged) {
                    Leodanmu.log("豆瓣item结构: " + getJsonKeys(raw));
                    Object coverObj = raw.opt("cover");
                    if (coverObj instanceof JSONObject) {
                        JSONObject cover = (JSONObject) coverObj;
                        Leodanmu.log("豆瓣cover字段: " + getJsonKeys(cover) + " url空=" + TextUtils.isEmpty(cover.optString("url", "")) + " normal空=" + TextUtils.isEmpty(cover.optString("normal", "")) + " large空=" + TextUtils.isEmpty(cover.optString("large", "")));
                    } else {
                        Leodanmu.log("豆瓣cover类型: " + (coverObj != null ? coverObj.getClass().getSimpleName() : "null"));
                    }
                    Object picObj = raw.opt("pic");
                    Leodanmu.log("豆瓣pic类型: " + (picObj != null ? picObj.getClass().getSimpleName() : "null"));
                    if (picObj instanceof JSONObject) {
                        JSONObject picJO = (JSONObject) picObj;
                        Leodanmu.log("豆瓣pic字段: " + getJsonKeys(picJO));
                        for (String k : new String[]{"large","normal","small","url","original"}) {
                            Object v = picJO.opt(k);
                            if (v != null) Leodanmu.log("豆瓣pic." + k + "类型=" + v.getClass().getSimpleName() + " 值=" + (v instanceof String ? (String)v : (v instanceof JSONObject ? getJsonKeys((JSONObject)v) : v)));
                        }
                    }
                    JSONObject sub = raw.optJSONObject("subject");
                    if (sub != null) {
                        Object subPic = sub.opt("pic");
                        Leodanmu.log("豆瓣subject.pic类型: " + (subPic != null ? subPic.getClass().getSimpleName() : "null"));
                        if (subPic instanceof JSONObject) {
                            Leodanmu.log("豆瓣subject.pic字段: " + getJsonKeys((JSONObject) subPic));
                        }
                    }
                    sDebugLogged = true;
                }

                String title = raw.optString("title", "");
                if (TextUtils.isEmpty(title)) title = "未知";

                String rawId = raw.optString("id", "");
                if (TextUtils.isEmpty(rawId)) continue;

                // cover is a direct string (search_subjects format) or nested object (frodo format)
                String pic = "";
                Object coverVal = raw.opt("cover");
                if (coverVal instanceof String) {
                    pic = (String) coverVal;
                } else {
                    pic = extractImage(raw, "cover");
                }
                if (TextUtils.isEmpty(pic)) {
                    Object picVal = raw.opt("pic");
                    if (picVal instanceof String) {
                        pic = (String) picVal;
                    } else {
                        pic = extractImage(raw, "pic");
                    }
                }
                JSONObject sub = raw.optJSONObject("subject");
                if (TextUtils.isEmpty(pic) && sub != null) pic = extractImage(sub, "pic");
                pic = processImageUrl(pic);

                double ratingVal = 0;
                Object rateVal = raw.opt("rate");
                if (rateVal instanceof String) {
                    try { ratingVal = Double.parseDouble((String) rateVal); } catch (Exception ignored) {}
                }
                JSONObject ratingObj = raw.optJSONObject("rating");
                if (ratingObj == null && sub != null) ratingObj = sub.optJSONObject("rating");
                if (ratingObj != null && ratingVal <= 0) ratingVal = ratingObj.optDouble("value", 0);

                String pubdate = "";
                if (sub != null) {
                    pubdate = sub.optString("pubdate", "");
                    if (TextUtils.isEmpty(pubdate)) pubdate = sub.optString("release_date", "");
                }
                if (TextUtils.isEmpty(pubdate)) pubdate = raw.optString("pubdate", "");
                if (TextUtils.isEmpty(pubdate)) pubdate = raw.optString("release_date", "");

                String yearStr = "";
                if (TextUtils.isEmpty(pubdate)) {
                    if (sub != null) yearStr = sub.optString("year", "");
                    if (TextUtils.isEmpty(yearStr)) yearStr = raw.optString("year", "");
                }

                if (!TextUtils.isEmpty(pubdate) || !TextUtils.isEmpty(yearStr)) {
                    String dateStr = !TextUtils.isEmpty(pubdate) ? pubdate : yearStr;
                    Matcher m = Pattern.compile("\\d{4}").matcher(dateStr);
                    if (m.find()) pubdate = m.group();
                }

                String remarks;
                if (ratingVal > 0) {
                    remarks = "评分: " + String.format("%.1f", ratingVal) + "分";
                    if (!TextUtils.isEmpty(pubdate)) remarks += " · " + pubdate;
                } else {
                    remarks = "暂无评分";
                    if (!TextUtils.isEmpty(pubdate)) remarks += " · " + pubdate;
                }

                JSONObject vod = new JSONObject();
                vod.put("vod_id", rawId.trim());
                vod.put("vod_name", title);
                vod.put("vod_pic", pic);
                vod.put("vod_remarks", remarks);
                if (list.length() == 0) Leodanmu.log("豆瓣第一个vod: " + vod.getString("vod_id") + " | " + vod.getString("vod_name"));
                vod.put("goSearch", true);
                list.put(vod);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private static JSONObject classObj(String id, String name) throws Exception {
        JSONObject cls = new JSONObject();
        cls.put("type_id", id);
        cls.put("type_name", name);
        return cls;
    }

    private static JSONArray buildFilters(JSONObject... filters) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject f : filters) arr.put(f);
        return arr;
    }

    private static JSONObject filter(String key, String name, String init, String[][] values) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("key", key);
        obj.put("name", name);
        obj.put("init", init);
        JSONArray arr = new JSONArray();
        for (String[] v : values) {
            JSONObject item = new JSONObject();
            item.put("n", v[0]);
            item.put("v", v[1]);
            arr.put(item);
        }
        obj.put("value", arr);
        return obj;
    }

    private static String getJsonKeys(JSONObject obj) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (sb.length() > 0) sb.append(",");
            sb.append(key).append("=").append(obj.opt(key) != null ? obj.opt(key).getClass().getSimpleName() : "null");
        }
        return sb.toString();
    }

    private static String extractImage(JSONObject obj, String field) {
        Object val = obj.opt(field);
        if (val instanceof String) return (String) val;
        if (val instanceof JSONObject) {
            JSONObject jo = (JSONObject) val;
            String v = jo.optString("url", "");
            if (!TextUtils.isEmpty(v)) return v;
            v = jo.optString("normal", "");
            if (!TextUtils.isEmpty(v)) return v;
            for (String key : new String[]{"large", "medium", "small", "original"}) {
                v = jo.optString(key, "");
                if (!TextUtils.isEmpty(v)) return v;
            }
            Object any = jo.opt("large");
            if (any instanceof JSONObject) {
                v = ((JSONObject) any).optString("url", "");
                if (!TextUtils.isEmpty(v)) return v;
            }
        }
        return "";
    }

    private static int randomInt(int min, int max) {
        return RANDOM.nextInt(max - min + 1) + min;
    }

    private static String processImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Matcher m = DOUBAN_IMG_PATTERN.matcher(url);
        if (m.find()) {
            if (!url.contains("img2")) {
                int idx = randomInt(0, 2);
                url = url.replaceAll("https://img\\d*\\.doubanio\\.com", "https://" + IMG_DOMAINS[idx]);
            }
            url += "@Referer=" + IMG_REFERER + "@User-Agent=" + IMG_UA;
        }
        return url;
    }

    private static JSONObject requestMovieSearch(String type, String tag, int start, int count, String sort) {
        try {
            StringBuilder url = new StringBuilder();
            url.append(SEARCH_HOST).append("/j/search_subjects?type=").append(type);
            if (!TextUtils.isEmpty(tag)) url.append("&tag=").append(URLEncoder.encode(tag, "UTF-8"));
            url.append("&page_limit=").append(count).append("&page_start=").append(start).append("&sort=").append(sort);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36");
            headers.put("Referer", "https://movie.douban.com/");
            String body = OkHttp.string(url.toString(), headers);
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            Leodanmu.log("豆瓣搜索请求失败: " + e.getMessage());
            return null;
        }
    }

    private static JSONObject requestDouban(String url) {
        try {
            String separator = url.contains("?") ? "&" : "?";
            String finalUrl = url + separator + "apikey=" + API_KEY;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", API_UA);
            headers.put("Referer", API_REFERER);
            String body = OkHttp.string(finalUrl, headers);
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            Leodanmu.log("豆瓣请求失败: " + e.getMessage());
            return null;
        }
    }
}
