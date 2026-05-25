package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DoubanFetcher {

    private static final String HOST = "https://frodo.douban.com/api/v2";
    private static final String REXXAR_HOST = "https://m.douban.com/rexxar/api/v2";
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

    // ─── 签名 API (豆瓣片单用) ──────────────────────────────────────────────
    private static final String DOUBAN_SIGN_SECRET = "bf7dddc7c9cfe6f7";
    private static final String DOUBAN_APP_KEY = "0dad551ec0f84ed02907ff5c42e8ec70";
    private static final String DOUBAN_DEVICE_ID = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    private static final String DOUBAN_APP_UA = "Rexxar-Core/0.1.3 api-client/1 com.douban.frodo/7.9.0(216) Android/28 product/Xiaomi11 rom/android network/wifi udid/" + DOUBAN_DEVICE_ID + " platform/mobile com.douban.frodo/7.9.0(216) Rexxar/1.2.151 platform/mobile 1.2.151";

    private static boolean sDebugLogged = false;

    // ─── TMDB ───────────────────────────────────────────────────────────────
    private static final String TMDB_API_KEY = "c2d8bcc1ff01b54ee5039d5b70f2b67e";
    private static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500";

    // ─── 熔断器（TMDB 用）──────────────────────────────────────────────────
    private static class CircuitBreaker {
        int failures = 0;
        final int threshold;
        final long timeoutMs;
        long openedAt = 0;

        CircuitBreaker(int threshold, long timeoutMs) {
            this.threshold = threshold;
            this.timeoutMs = timeoutMs;
        }

        boolean isOpen() {
            if (openedAt == 0) return false;
            if (System.currentTimeMillis() - openedAt >= timeoutMs) {
                reset();
                return false;
            }
            return true;
        }

        void onSuccess() { reset(); }
        void onFailure() {
            failures++;
            if (failures >= threshold) openedAt = System.currentTimeMillis();
        }
        private void reset() { failures = 0; openedAt = 0; }
    }

    private static final CircuitBreaker tmdbBreaker = new CircuitBreaker(2, 30 * 1000);

    // ─── 分页缓存 ──────────────────────────────────────────────────────────
    private static final long CACHE_CLEAN_INTERVAL = 24 * 60 * 60 * 1000;
    private static final long HOME_LIST_CACHE_TTL = 5 * 60 * 1000;
    private static final ConcurrentHashMap<String, PageCacheEntry> pageCache = new ConcurrentHashMap<>();
    private static volatile boolean sCacheInitialized = false;
    private static JSONArray sCachedHomeList = null;
    private static long sHomeListCacheTime = 0;

    private static class PageCacheEntry {
        final JSONObject data;
        final long lastAccess;
        PageCacheEntry(JSONObject data, long lastAccess) {
            this.data = data;
            this.lastAccess = lastAccess;
        }
    }

    private static String getPageCacheKey(String id, int page, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) return id + "_" + page;
        StringBuilder sb = new StringBuilder(id);
        sb.append('_').append(page);
        for (Map.Entry<String, String> e : filters.entrySet()) {
            if (!TextUtils.isEmpty(e.getValue())) {
                sb.append('_').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static void initCache() {
        if (sCacheInitialized) return;
        sCacheInitialized = true;
        Thread cacheThread = new Thread("douban-cache") {
            @Override
            public void run() {
                try {
                    backgroundRefreshTask();
                    while (true) {
                        Thread.sleep(CACHE_CLEAN_INTERVAL);
                        backgroundRefreshTask();
                    }
                } catch (InterruptedException ignored) {
                }
            }
        };
        cacheThread.setDaemon(true);
        cacheThread.start();
    }

    private static void backgroundRefreshTask() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, PageCacheEntry> e : pageCache.entrySet()) {
                if (now - e.getValue().lastAccess > CACHE_CLEAN_INTERVAL) {
                    pageCache.remove(e.getKey());
                }
            }
            String[][] coreTargets = {
                {"hot_movie", null}, {"hot_tv", null}, {"hot_show", null},
                {"movie", "U"}, {"tv", "U"}, {"show", "U"}, {"anime", "U"},
                {"douban_anime", null}, {"top_250", null}
            };
            // 前3个并行预热（最常用的榜单）
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < 3 && i < coreTargets.length; i++) {
                String[] target = coreTargets[i];
                Thread t = new Thread(() -> {
                    try {
                        String id = target[0];
                        Map<String, String> f = new HashMap<>();
                        if ("U".equals(target[1])) f.put("排序", "U");
                        JSONObject data = fetchCategoryInternal(id, 1, f);
                        if (data != null) {
                            pageCache.put(getPageCacheKey(id, 1, f), new PageCacheEntry(data, System.currentTimeMillis()));
                        }
                    } catch (Exception ignored) {}
                });
                t.start();
                workers.add(t);
            }
            for (Thread t : workers) t.join();
            // 剩余分类串行，间隔300ms
            for (int i = 3; i < coreTargets.length; i++) {
                try {
                    String id = coreTargets[i][0];
                    Map<String, String> f = new HashMap<>();
                    if ("U".equals(coreTargets[i][1])) f.put("排序", "U");
                    JSONObject data = fetchCategoryInternal(id, 1, f);
                    if (data != null) {
                        pageCache.put(getPageCacheKey(id, 1, f), new PageCacheEntry(data, System.currentTimeMillis()));
                    }
                    Thread.sleep(300);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {}
    }

    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "latest", "douban_hot", "movie", "tv", "show", "anime", "douban_anime",
            "hot_movie", "hot_tv", "hot_show",
            "movie_filter", "tv_filter", "top_250", "douban_playlist",
            "anime_tmdb"
    ));

    // ─── JS tag ↔ sort map ─────────────────────────────────────────────────
    private static final Map<String, String> SORT_MAP = new HashMap<>();
    static {
        SORT_MAP.put("U", "U");  // 近期热度
        SORT_MAP.put("T", "T");  // 综合排序
        SORT_MAP.put("R", "R");  // 首映/首播时间
        SORT_MAP.put("S", "S");  // 高分优先
        // old keys (from movie_filter/tv_filter)
        SORT_MAP.put("recommend", "U");
        SORT_MAP.put("time", "R");
        SORT_MAP.put("rank", "S");
    }

    // ─── Chinese type → English type map ─────────────────────────────────────
    private static final Map<String, String> CONTENT_TYPE_MAP = new HashMap<>();
    static {
        CONTENT_TYPE_MAP.put("电影", "movie");
        CONTENT_TYPE_MAP.put("电视剧", "tv");
        CONTENT_TYPE_MAP.put("综艺", "variety");
        CONTENT_TYPE_MAP.put("动漫", "anime");
    }
    // ─── ru_ prefix type map ─────────────────────────────────────────────────
    private static final Map<String, String> RU_TYPE_MAP = new HashMap<>();
    static {
        RU_TYPE_MAP.put("ru_movie", "movie");
        RU_TYPE_MAP.put("ru_tv", "tv");
        RU_TYPE_MAP.put("ru_zy", "variety");
        RU_TYPE_MAP.put("ru_dm", "anime");
    }

    // ─── Platform detection & mapping ──────────────────────────────────────
    private static final Set<String> PLATFORM_PREFIXES = new HashSet<>(Arrays.asList(
            "iqy_", "tx_", "youku_", "mgtv_", "360ys_", "sogousp_"
    ));
    private static boolean isKnownPlatform(String val) {
        if (TextUtils.isEmpty(val)) return false;
        for (String p : PLATFORM_PREFIXES) {
            if (val.startsWith(p)) return true;
        }
        return false;
    }
    private static String prefixToPlatform(String val) {
        if (val.startsWith("iqy_")) return "iqiyi";
        if (val.startsWith("tx_")) return "tencent";
        if (val.startsWith("youku_")) return "youku";
        if (val.startsWith("mgtv_")) return "mgtv";
        if (val.startsWith("360ys_")) return "360kan";
        if (val.startsWith("sogousp_")) return "sogou";
        return "douban";
    }

    public static boolean isDouban(String tid) {
        if (CATEGORIES.contains(tid)) return true;
        if (tid != null && (tid.startsWith("cr_") || tid.startsWith("dl_"))) return true;
        return false;
    }

    public static void prewarm() {
        CATEGORIES.size();
        initCache();
    }

    public static void clearCache() {
        pageCache.clear();
        Leodanmu.log("DoubanFetcher: cache cleared");
    }

    public static int getCacheSize() {
        return pageCache.size();
    }

    // ─── Categories ─────────────────────────────────────────────────────────
    public static JSONArray getCategories() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(classObj("latest", "\u6700\u8fd1\u66f4\u65b0"));
        arr.put(classObj("douban_hot", "\u8c46\u74e3\u70ed\u64ad"));
        arr.put(classObj("movie", "\u70ed\u95e8\u7535\u5f71"));
        arr.put(classObj("tv", "\u70ed\u95e8\u5267\u96c6"));
        arr.put(classObj("show", "\u70ed\u64ad\u7efc\u827a"));
        arr.put(classObj("anime", "\u70ed\u64ad\u52a8\u6f2b"));
        arr.put(classObj("douban_anime", "\u8c46\u74e3\u52a8\u6f2b"));
        arr.put(classObj("hot_movie", "\u7535\u5f71\u699c\u5355"));
        arr.put(classObj("hot_tv", "\u5267\u96c6\u699c\u5355"));
        arr.put(classObj("hot_show", "\u7efc\u827a\u699c\u5355"));
        arr.put(classObj("movie_filter", "\u7535\u5f71\u7b5b\u9009"));
        arr.put(classObj("tv_filter", "\u7535\u89c6\u7b5b\u9009"));
        arr.put(classObj("top_250", "\u8c46\u74e3\u7535\u5f71Top250"));
        arr.put(classObj("douban_playlist", "\u8c46\u74e3\u7247\u5355"));
        arr.put(classObj("anime_tmdb", "\ud83c\udf1f TMDB\u52a8\u6f2b\u699c"));
        return arr;
    }

    // ─── Filter Config ─────────────────────────────────────────────────────
    public static JSONObject getFilterConfig() throws Exception {
        JSONObject root = new JSONObject();

        // ── 最近更新 ──────────────────────────────────────────────────────
        root.put("latest", buildFilters(
                filter("类型", "类型", "", new String[][]{
                        {"电影", "ru_movie"}, {"电视剧", "ru_tv"}, {"综艺", "ru_zy"}, {"动漫", "ru_dm"}
                }),
                filter("数据源", "数据源", "", new String[][]{
                        {"豆瓣", "douban_ru"}, {"爱奇艺", "iqy_ru"}, {"腾讯", "tx_ru"}, {"优酷", "youku_ru"},
                        {"芒果TV", "mgtv_ru"}, {"360影视", "360ys_ru"}, {"搜狗视频", "sogousp_ru"}
                })
        ));

        // ── 豆瓣热播 ────────────────────────────────────────────────────
        root.put("douban_hot", buildFilters(
                filter("分类", "分类", "movie/hot_gaia", new String[][]{
                        {"电视剧", "subject_collection/tv_hot/items"},
                        {"电影", "movie/hot_gaia"},
                        {"国产剧", "subject_collection/tv_domestic/items"},
                        {"美剧", "subject_collection/tv_american/items"},
                        {"日剧", "subject_collection/tv_japanese/items"},
                        {"韩剧", "subject_collection/tv_korean/items"},
                        {"动漫", "subject_collection/tv_animation/items"},
                        {"综艺", "subject_collection/show_hot/items"}
                })
        ));

        // ── 热门电影 ─────────────────────────────────────────────────────
        root.put("movie", buildFilters(
                filter("平台", "平台", "", new String[][]{
                        {"爱奇艺", "iqy_hot_movie"}, {"腾讯", "tx_hot_movie"}, {"优酷", "youku_hot_movie"},
                        {"芒果TV", "mgtv_hot_movie"}, {"360影视", "360ys_hot_movie"}, {"搜狗视频", "sogousp_hot_movie"},
                        {"豆瓣", "豆瓣"}, {"最新", "最新"}, {"高分", "高分"},
                        {"冷门佳片", "冷门佳片"}, {"华语", "华语"}, {"欧美", "欧美"},
                        {"韩国", "韩国"}, {"日本", "日本"}
                })
        ));

        // ── 热门剧集 ─────────────────────────────────────────────────────
        root.put("tv", buildFilters(
                filter("平台", "平台", "", new String[][]{
                        {"爱奇艺", "iqy_hot_tv"}, {"腾讯", "tx_hot_tv"}, {"优酷", "youku_hot_tv"},
                        {"芒果TV", "mgtv_hot_tv"}, {"360影视", "360ys_hot_tv"}, {"搜狗视频", "sogousp_hot_tv"},
                        {"豆瓣", "豆瓣"}, {"国产剧", "国产剧"}, {"美剧", "美剧"},
                        {"日剧", "日剧"}, {"韩剧", "韩剧"}, {"日本动画", "日本动画"},
                        {"纪录片", "纪录片"}
                })
        ));

        // ── 热播综艺 ─────────────────────────────────────────────────────
        root.put("show", buildFilters(
                filter("平台", "平台", "", new String[][]{
                        {"爱奇艺", "iqy_hot_zy"}, {"腾讯", "tx_hot_zy"}, {"优酷", "youku_hot_zy"},
                        {"360影视", "360ys_hot_zy"}, {"芒果TV", "mgtv_hot_zy"}, {"搜狗视频", "sogousp_hot_zy"},
                        {"豆瓣", "zy_all"}, {"国内", "zy_cn"}, {"国外", "zy_other"}
                })
        ));

        // ── 热播动漫 ─────────────────────────────────────────────────────
        root.put("anime", buildFilters(
                filter("平台", "平台", "", new String[][]{
                        {"爱奇艺", "iqy_hot_dm"}, {"腾讯", "tx_hot_dm"}, {"优酷", "youku_hot_dm"},
                        {"芒果TV", "mgtv_hot_dm"}, {"360影视", "360ys_hot_dm"}, {"搜狗视频", "sogousp_hot_dm"}
                })
        ));

        // ── 电影榜单 ─────────────────────────────────────────────────────
        root.put("hot_movie", buildFilters(
                filter("榜单", "榜单", "", new String[][]{
                        {"豆瓣实时热门电影榜", "movie_real_time_hotest"},
                        {"豆瓣一周口碑电影榜", "movie_weekly_best"},
                        {"豆瓣电影Top250", "top250"},
                        {"360影视电影榜", "rank?cat=2"},
                        {"爱奇艺热播榜", "ranks1/1/0"},
                        {"爱奇艺飙升榜", "ranks1/1/-1"},
                        {"爱奇艺必看榜", "ranks1/1/-6"},
                        {"爱奇艺上新榜", "ranks1/1/-5"},
                        {"爱奇艺好评榜", "ranks1/1/-4"},
                        {"爱奇艺枪战榜", "ranks1/1/8201844980650933"},
                        {"爱奇艺青春榜", "ranks1/1/8902937931540733"},
                        {"爱奇艺奇幻榜", "ranks1/1/8035796650176933"},
                        {"爱奇艺恐怖榜", "ranks1/1/7128547076428333"},
                        {"爱奇艺战争榜", "ranks1/1/4705204050526433"},
                        {"腾讯剧情榜", "rank_hot_movie_story"},
                        {"腾讯口碑经典榜", "rank_hot_movie_classic"},
                        {"腾讯喜剧片榜", "rank_hot_movie_comedy"},
                        {"腾讯爱情榜", "rank_hot_movie_love"},
                        {"腾讯科幻片榜", "rank_hot_movie_fiction"},
                        {"优酷热度榜", "youku_movie_0"},
                        {"优酷上新榜", "youku_movie_1"},
                        {"优酷高分榜", "youku_movie_2"},
                        {"优酷免费榜", "youku_movie_3"},
                })
        ));

        // ── 剧集榜单 ─────────────────────────────────────────────────────
        root.put("hot_tv", buildFilters(
                filter("榜单", "榜单", "", new String[][]{
                        {"豆瓣实时热门电视榜", "tv_real_time_hotest"},
                        {"豆瓣华语口碑剧集榜", "tv_chinese_best_weekly"},
                        {"豆瓣全球口碑剧集榜", "tv_global_best_weekly"},
                        {"豆瓣国内口碑综艺榜", "show_chinese_best_weekly"},
                        {"豆瓣国外口碑综艺榜", "show_global_best_weekly"},
                        {"360影视电视剧榜", "rank?cat=4"},
                        {"爱奇艺热播榜", "ranks1/2/0"},
                        {"爱奇艺飙升榜", "ranks1/2/-1"},
                        {"爱奇艺必看榜", "ranks1/2/-6"},
                        {"爱奇艺警匪榜", "ranks1/2/7245663290192433"},
                        {"爱奇艺古装榜", "ranks1/2/2289882683101933"},
                        {"爱奇艺偶像榜", "ranks1/2/4069086533300333"},
                        {"爱奇艺历史榜", "ranks1/2/7174270529747133"},
                        {"爱奇艺战争榜", "ranks1/2/4705204050526533"},
                        {"爱奇艺奇幻榜", "ranks1/2/8035796650176933"},
                        {"爱奇艺武侠榜", "ranks1/2/7045121828267433"},
                        {"腾讯古装剧榜", "rank_hot_tv_ancient"},
                        {"腾讯家庭剧榜", "rank_hot_tv_family"},
                        {"腾讯韩剧榜", "rank_hot_tv_korea"},
                        {"腾讯喜剧榜", "rank_hot_tv_comedy"},
                        {"腾讯谍战剧榜", "rank_hot_tv_spy"},
                        {"优酷独播榜", "youku_tv_0"},
                        {"优酷高分榜", "youku_tv_1"},
                        {"优酷古装榜", "youku_tv_2"},
                        {"优酷短剧榜", "youku_tv_3"},
                        {"优酷港剧榜", "youku_tv_4"},
                        {"优酷悬疑榜", "youku_tv_5"},
                        {"优酷高清榜", "youku_tv_6"}
                })
        ));

        // ── 综艺榜单 ─────────────────────────────────────────────────────
        root.put("hot_show", buildFilters(
                filter("榜单", "榜单", "", new String[][]{
                        {"豆瓣综艺热播榜", "tv_variety_show"},
                        {"豆瓣国内口碑综艺榜", "show_chinese_best_weekly"},
                        {"豆瓣国外口碑综艺榜", "show_global_best_weekly"}
                })
        ));

        // ── 电影筛选 ─────────────────────────────────────────────────────
        root.put("movie_filter", buildFilters(
                filter("类型", "类型", "", new String[][]{
                        {"全部类型", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"动作", "动作"},
                        {"科幻", "科幻"}, {"动画", "动画"}, {"悬疑", "悬疑"}, {"犯罪", "犯罪"},
                        {"惊悚", "惊悚"}, {"冒险", "冒险"}, {"音乐", "音乐"}, {"历史", "历史"},
                        {"奇幻", "奇幻"}, {"恐怖", "恐怖"}, {"战争", "战争"}, {"传记", "传记"},
                        {"歌舞", "歌舞"}, {"武侠", "武侠"}, {"情色", "情色"}, {"灾难", "灾难"},
                        {"西部", "西部"}, {"纪录片", "纪录片"}, {"短片", "短片"}
                }),
                filter("地区", "地区", "", new String[][]{
                        {"全部地区", ""}, {"华语", "华语"}, {"欧美", "欧美"}, {"韩国", "韩国"},
                        {"日本", "日本"}, {"中国大陆", "中国大陆"}, {"美国", "美国"},
                        {"中国香港", "中国香港"}, {"中国台湾", "中国台湾"}, {"英国", "英国"},
                        {"法国", "法国"}, {"德国", "德国"}, {"意大利", "意大利"}, {"西班牙", "西班牙"},
                        {"印度", "印度"}, {"泰国", "泰国"}, {"俄罗斯", "俄罗斯"}, {"加拿大", "加拿大"},
                        {"澳大利亚", "澳大利亚"}, {"爱尔兰", "爱尔兰"}, {"瑞典", "瑞典"},
                        {"巴西", "巴西"}, {"丹麦", "丹麦"}
                }),
                filter("年代", "年代", "", new String[][]{
                        {"全部年代", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                        {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"},
                        {"2019", "2019"}, {"2018", "2018"}, {"2020年代", "2020年代"},
                        {"2010年代", "2010年代"}, {"2000年代", "2000年代"}, {"90年代", "90年代"},
                        {"80年代", "80年代"}, {"70年代", "70年代"}, {"60年代", "60年代"}, {"更早", "更早"}
                }),
                filter("标签", "标签", "", new String[][]{
                        {"全部标签", ""}, {"经典", "经典"}, {"青春", "青春"}, {"艺术", "艺术"},
                        {"搞笑", "搞笑"}, {"黑色电影", "黑色电影"}, {"励志", "励志"}, {"西部冒险", "西部冒险"},
                        {"温情", "温情"}, {"推理", "推理"}, {"黑色幽默", "黑色幽默"}, {"暴力", "暴力"},
                        {"古装", "古装"}, {"伦理", "伦理"}, {"限制级", "限制级"}, {"动作", "动作"},
                        {"歌舞", "歌舞"}, {"浪漫", "浪漫"}, {"生活", "生活"}, {"情色", "情色"},
                        {"运动", "运动"}, {"荒诞", "荒诞"}, {"科幻", "科幻"}, {"惊悚", "惊悚"},
                        {"奥斯卡", "奥斯卡"}, {"历史", "历史"}, {"悬疑", "悬疑"}, {"奇幻", "奇幻"},
                        {"冒险", "冒险"}, {"战争", "战争"}, {"邵氏", "邵氏"}, {"动画", "动画"},
                        {"漫画改编", "漫画改编"}, {"黑帮", "黑帮"}, {"爱情", "爱情"}, {"恐怖", "恐怖"},
                        {"灾难", "灾难"}, {"喜剧", "喜剧"}, {"人性", "人性"}, {"剧场版", "剧场版"},
                        {"家庭", "家庭"}, {"超级英雄", "超级英雄"}, {"动物", "动物"}, {"定格动画", "定格动画"},
                        {"侦探", "侦探"}, {"犯罪", "犯罪"}, {"传记", "传记"}, {"真实事件改编", "真实事件改编"},
                        {"人生", "人生"}, {"政治", "政治"}, {"警匪", "警匪"}, {"成长", "成长"},
                        {"音乐剧", "音乐剧"}, {"文艺", "文艺"}, {"戏曲", "戏曲"}, {"军事", "军事"},
                        {"宗教", "宗教"}, {"二战", "二战"}, {"儿童", "儿童"}, {"小说改编", "小说改编"},
                        {"武侠", "武侠"}, {"古装", "古装"}, {"美国动画", "美国动画"}, {"怪物", "怪物"},
                        {"丧尸", "丧尸"}, {"黑白", "黑白"}, {"欧洲", "欧洲"}, {"音乐", "音乐"},
                        {"真人秀", "真人秀"}, {"设计", "设计"}, {"宇宙", "宇宙"}, {"建筑", "建筑"},
                        {"治愈", "治愈"}, {"港片", "港片"}, {"新浪潮", "新浪潮"}, {"国家地理", "国家地理"},
                        {"名著改编", "名著改编"}, {"魔幻", "魔幻"}, {"老电影", "老电影"}, {"网络电影", "网络电影"},
                        {"亲情", "亲情"}, {"动画短片", "动画短片"}, {"吸血鬼", "吸血鬼"}, {"默片", "默片"},
                        {"宝莱坞", "宝莱坞"}, {"北欧", "北欧"}
                }),
                filter("排序", "排序", "", new String[][]{
                        {"综合排序", "U"}, {"近期热度", "T"}, {"首映时间", "R"}, {"高分优先", "S"}
                })
        ));

        // ── 电视筛选 ─────────────────────────────────────────────────────
        root.put("tv_filter", buildFilters(
                filter("类型", "类型", "", new String[][]{
                        {"全部类型", ""}, {"电视剧", "电视剧"}, {"综艺", "综艺"}
                }),
                filter("剧集", "剧集", "", new String[][]{
                        {"全部剧集", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"悬疑", "悬疑"},
                        {"动画", "动画"}, {"武侠", "武侠"}, {"古装", "古装"}, {"家庭", "家庭"},
                        {"犯罪", "犯罪"}, {"科幻", "科幻"}, {"恐怖", "恐怖"}, {"历史", "历史"},
                        {"战争", "战争"}, {"动作", "动作"}, {"冒险", "冒险"}, {"传记", "传记"},
                        {"剧情", "剧情"}, {"奇幻", "奇幻"}, {"惊悚", "惊悚"}, {"宅男", "宅男"},
                        {"歌舞", "歌舞"}, {"音乐", "音乐"}
                }),
                filter("综艺", "综艺", "", new String[][]{
                        {"全部综艺", ""}, {"真人秀", "真人秀"}, {"脱口秀", "脱口秀"}, {"音乐", "音乐"}, {"歌舞", "歌舞"}
                }),
                filter("地区", "地区", "", new String[][]{
                        {"全部地区", ""}, {"华语", "华语"}, {"欧美", "欧美"}, {"韩国", "韩国"},
                        {"日本", "日本"}, {"中国大陆", "中国大陆"}, {"美国", "美国"},
                        {"中国香港", "中国香港"}, {"中国台湾", "中国台湾"}, {"英国", "英国"},
                        {"法国", "法国"}, {"德国", "德国"}, {"意大利", "意大利"}, {"西班牙", "西班牙"},
                        {"印度", "印度"}, {"泰国", "泰国"}, {"俄罗斯", "俄罗斯"}, {"加拿大", "加拿大"},
                        {"澳大利亚", "澳大利亚"}, {"爱尔兰", "爱尔兰"}, {"瑞典", "瑞典"},
                        {"巴西", "巴西"}, {"丹麦", "丹麦"}
                }),
                filter("年代", "年代", "", new String[][]{
                        {"全部年代", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                        {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"},
                        {"2019", "2019"}, {"2018", "2018"}, {"2020年代", "2020年代"},
                        {"2010年代", "2010年代"}, {"2000年代", "2000年代"}, {"90年代", "90年代"},
                        {"80年代", "80年代"}, {"70年代", "70年代"}, {"60年代", "60年代"}, {"更早", "更早"}
                }),
                filter("平台", "平台", "", new String[][]{
                        {"全部平台", ""}, {"腾讯视频", "腾讯视频"}, {"爱奇艺", "爱奇艺"},
                        {"优酷", "优酷"}, {"湖南卫视", "湖南卫视"}, {"Netflix", "Netflix"},
                        {"HBO", "HBO"}, {"BBC", "BBC"}, {"NHK", "NHK"}, {"CBS", "CBS"},
                        {"NBC", "NBC"}, {"tvN", "tvN"}
                }),
                filter("标签", "标签", "", new String[][]{
                        {"全部标签", ""}, {"经典", "经典"}, {"青春", "青春"}, {"艺术", "艺术"},
                        {"搞笑", "搞笑"}, {"黑色电影", "黑色电影"}, {"励志", "励志"}, {"西部冒险", "西部冒险"},
                        {"温情", "温情"}, {"推理", "推理"}, {"黑色幽默", "黑色幽默"}, {"暴力", "暴力"},
                        {"古装", "古装"}, {"伦理", "伦理"}, {"限制级", "限制级"}, {"动作", "动作"},
                        {"歌舞", "歌舞"}, {"浪漫", "浪漫"}, {"生活", "生活"}, {"情色", "情色"},
                        {"运动", "运动"}, {"荒诞", "荒诞"}, {"科幻", "科幻"}, {"惊悚", "惊悚"},
                        {"奥斯卡", "奥斯卡"}, {"历史", "历史"}, {"悬疑", "悬疑"}, {"奇幻", "奇幻"},
                        {"冒险", "冒险"}, {"战争", "战争"}, {"邵氏", "邵氏"}, {"动画", "动画"},
                        {"漫画改编", "漫画改编"}, {"黑帮", "黑帮"}, {"爱情", "爱情"}, {"恐怖", "恐怖"},
                        {"灾难", "灾难"}, {"喜剧", "喜剧"}, {"人性", "人性"}, {"剧场版", "剧场版"},
                        {"家庭", "家庭"}, {"超级英雄", "超级英雄"}, {"动物", "动物"}, {"定格动画", "定格动画"},
                        {"侦探", "侦探"}, {"犯罪", "犯罪"}, {"传记", "传记"}, {"真实事件改编", "真实事件改编"},
                        {"人生", "人生"}, {"政治", "政治"}, {"警匪", "警匪"}, {"成长", "成长"},
                        {"音乐剧", "音乐剧"}, {"文艺", "文艺"}, {"戏曲", "戏曲"}, {"军事", "军事"},
                        {"宗教", "宗教"}, {"二战", "二战"}, {"儿童", "儿童"}, {"小说改编", "小说改编"},
                        {"武侠", "武侠"}, {"美国动画", "美国动画"}, {"怪物", "怪物"},
                        {"丧尸", "丧尸"}, {"欧洲", "欧洲"}, {"真人秀", "真人秀"},
                        {"设计", "设计"}, {"宇宙", "宇宙"}, {"建筑", "建筑"},
                        {"治愈", "治愈"}, {"港片", "港片"}, {"新浪潮", "新浪潮"}, {"国家地理", "国家地理"},
                        {"名著改编", "名著改编"}, {"魔幻", "魔幻"}, {"老电影", "老电影"}, {"网络电影", "网络电影"},
                        {"亲情", "亲情"}, {"动画短片", "动画短片"}, {"吸血鬼", "吸血鬼"}, {"默片", "默片"},
                        {"宝莱坞", "宝莱坞"}, {"北欧", "北欧"}
                }),
                filter("排序", "排序", "", new String[][]{
                        {"综合排序", "U"}, {"近期热度", "T"}, {"首播时间", "R"}, {"高分优先", "S"}
                })
        ));

        // ── 豆瓣电影Top250 ───────────────────────────────────────────────
        root.put("top_250", buildFilters(
                filter("榜单", "榜单", "movie_top250", new String[][]{
                        {"豆瓣电影Top250", "movie_top250"}
                })
        ));

        // ── 豆瓣片单 ─────────────────────────────────────────────────────
        root.put("douban_playlist", buildFilters(
                filter("类型", "类型", "", new String[][]{
                        {"全部", ""}, {"电影", "movie"}, {"电视剧", "tv"}
                }),
                filter("分类", "分类", "all", new String[][]{
                        {"全部", "all"}, {"豆瓣片单", "official"}, {"精选", "selected"},
                        {"经典", "classical"}, {"获奖", "prize"}, {"高分", "high_score"},
                        {"榜单", "movie_list"}, {"冷门佳片", "dark_horse"},
                        {"主题", "topic"}, {"导演", "director"}, {"演员", "actor"},
                        {"系列", "series"}
                })
        ));

        // ── TMDB 动漫榜 ─────────────────────────────────────────────────
        root.put("anime_tmdb", buildFilters(
                filter("榜单", "榜单", "hot_following", new String[][]{
                        {"全球趋势", "global_trending"},
                        {"高热追更", "hot_following"},
                        {"最新首播", "latest_premiere"},
                        {"热门口碑", "popular_reputation"}
                }),
                filter("年份", "年份", "all", buildYearFilterValues()),
                filter("地区", "地区", "all", new String[][]{
                        {"全部", "all"}, {"中国", "cn"}, {"日本", "jp"}, {"美国", "us"}, {"其他", "other"}
                })
        ));

        // ── 豆瓣动漫（合并动漫剧集+动漫电影）────────────────────────────
        root.put("douban_anime", buildFilters(
                filter("榜单", "榜单", "anime_recent", new String[][]{
                        {"最新动漫", "anime_recent"},
                        {"近期热门动漫", "anime_hot"},
                        {"高分动漫", "anime_best"}
                }),
                filter("类型", "类型", "", new String[][]{
                        {"全部类型", ""}, {"动画", "动画"}, {"日本动画", "日本动画"},
                        {"国产动画", "国产动画"}, {"欧美动画", "欧美动画"},
                        {"剧场版", "剧场版"}, {"番剧", "番剧"}
                }),
                filter("地区", "地区", "", new String[][]{
                        {"全部地区", ""}, {"日本", "日本"}, {"中国大陆", "中国大陆"},
                        {"美国", "美国"}, {"欧美", "欧美"}
                }),
                filter("年代", "年代", "", new String[][]{
                        {"全部年代", ""}, {"2026", "2026"}, {"2025", "2025"},
                        {"2024", "2024"}, {"2023", "2023"}, {"2022", "2022"},
                        {"2021", "2021"}, {"2020", "2020"}
                }),
                filter("排序", "排序", "U", new String[][]{
                        {"近期热度", "U"}, {"综合排序", "T"},
                        {"首播时间", "R"}, {"高分优先", "S"}
                })
        ));

        return root;
    }

    // ─── Home feed ──────────────────────────────────────────────────────────
    private static void mergeItems(JSONArray target, JSONArray source, Set<String> seen, int max) {
        if (source == null) return;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String vid = item.optString("vod_id");
            if (TextUtils.isEmpty(vid) || seen.contains(vid)) continue;
            seen.add(vid);
            target.put(item);
            if (max > 0 && target.length() >= max) return;
        }
    }

    public static JSONArray fetchHomeList() {
        long now = System.currentTimeMillis();
        if (sCachedHomeList != null && now - sHomeListCacheTime < HOME_LIST_CACHE_TTL) {
            return sCachedHomeList;
        }
        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();

        // 1. 豆瓣实时热门（每个 home 页都重新拉，不通过缓存）
        JSONObject db = requestDouban(HOST + "/subject_collection/subject_real_time_hotest/items?start=0&count=50&updated_at=&items_only=1&for_mobile=1");
        if (db != null) {
            mergeItems(merged, mapItems(db.optJSONArray("subject_collection_items")), seen, 80);
        }

        // 2. 智能混合配比 (参考 JS 版 pattern: [电影, 电影, 剧集, 剧集, 综艺])
        JSONArray[] doubanPools = new JSONArray[3];
        int[] doubanCursors = new int[3];
        int[] doubanPages = new int[]{1, 1, 1};
        boolean[] doubanNoMore = new boolean[3];
        String[] doubanIds = {"hot_movie", "hot_tv", "hot_show"};

        int[] pattern = {0, 0, 1, 1, 2};

        for (int round = 0; round < 100 && merged.length() < 120; round++) {
            boolean added = false;
            for (int pi : pattern) {
                JSONObject item = null;
                // try primary → fallback (电影→剧集→综艺)
                for (int fi = 0; fi < 3; fi++) {
                    int srcIdx = (pi + fi) % 3;
                    if (doubanNoMore[srcIdx]) continue;
                    // ensure at least 1 item available
                    while (doubanPools[srcIdx] == null
                            || doubanCursors[srcIdx] >= doubanPools[srcIdx].length()) {
                        try {
                            JSONObject data = _category(doubanIds[srcIdx], doubanPages[srcIdx], null);
                            doubanPages[srcIdx]++;
                            if (data == null || data.optJSONArray("list").length() == 0) {
                                doubanNoMore[srcIdx] = true;
                                break;
                            }
                            doubanPools[srcIdx] = data.optJSONArray("list");
                            doubanCursors[srcIdx] = 0;
                            if (doubanPages[srcIdx] > data.optInt("pagecount", 1)) {
                                doubanNoMore[srcIdx] = true;
                            }
                        } catch (Exception e) {
                            doubanNoMore[srcIdx] = true;
                            break;
                        }
                    }
                    if (doubanNoMore[srcIdx]) continue;
                    // take next unseen item
                    while (doubanCursors[srcIdx] < doubanPools[srcIdx].length()) {
                        JSONObject candidate = doubanPools[srcIdx].optJSONObject(doubanCursors[srcIdx]++);
                        if (candidate != null && !seen.contains(candidate.optString("vod_id"))) {
                            seen.add(candidate.optString("vod_id"));
                            item = candidate;
                            break;
                        }
                    }
                    if (item != null) break;
                }
                if (item != null) {
                    merged.put(item);
                    added = true;
                }
                if (merged.length() >= 120) break;
            }
            if (!added) break;
        }

        // 3. 其他豆瓣分类 (填充剩余)
        List<String> doubanSources = Arrays.asList("douban_anime", "latest", "top_250", "show", "anime", "movie", "tv");
        for (String id : doubanSources) {
            if (merged.length() >= 200) break;
            try {
                JSONObject data = _category(id, 1, null);
                if (data == null) continue;
                mergeItems(merged, data.optJSONArray("list"), seen, 200);
            } catch (Exception ignored) {}
        }

        // 4. 腾讯
        if (merged.length() < 200) mergeItems(merged, PlatformFetcher.fetchTencent("movie", 1, "75"), seen, 200);
        if (merged.length() < 200) mergeItems(merged, PlatformFetcher.fetchTencent("tv", 1, "75"), seen, 200);

        // 5. 芒果TV
        if (merged.length() < 200) mergeItems(merged, PlatformFetcher.fetchMangoTV("3", 1), seen, 200);
        if (merged.length() < 200) mergeItems(merged, PlatformFetcher.fetchMangoTV("2", 1), seen, 200);

        // 6. 360影视
        if (merged.length() < 200) mergeItems(merged, PlatformFetcher.fetch360kan("1", 1), seen, 200);
        if (merged.length() < 200) mergeItems(merged, PlatformFetcher.fetch360kan("2", 1), seen, 200);

        // 首页追加 TMDB 集数信息
        if (!tmdbBreaker.isOpen()) {
            batchAppendTmdbEpisodeInfo(merged);
        }

        sCachedHomeList = merged;
        sHomeListCacheTime = System.currentTimeMillis();
        return merged;
    }

    // ─── Cache-aware category fetch ─────────────────────────────────────────
    private static JSONObject _category(String id, int page, Map<String, String> filters) {
        String cacheKey = getPageCacheKey(id, page, filters);
        PageCacheEntry cached = pageCache.get(cacheKey);
        if (cached != null) {
            // Leodanmu.log("[豆瓣缓存] 命中: " + cacheKey);
            return cached.data;
        }
        // Leodanmu.log("[豆瓣缓存] 未命中，实时抓取: " + cacheKey);
        try {
            JSONObject data = fetchCategoryInternal(id, page, filters);
            if (data != null) {
                pageCache.put(cacheKey, new PageCacheEntry(data, System.currentTimeMillis()));
            } else {
                // 实时抓取返回空 → 尝试返回过期缓存（降级）
                PageCacheEntry stale = pageCache.get(cacheKey);
                if (stale != null) {
                    Leodanmu.log("[豆瓣缓存] 降级返回过期缓存: " + cacheKey);
                    return stale.data;
                }
            }
            return data;
        } catch (Exception e) {
            // Leodanmu.log("[豆瓣缓存] 抓取异常: " + e.getMessage());
            // 降级：返回过期缓存
            PageCacheEntry stale = pageCache.get(cacheKey);
            if (stale != null) {
                Leodanmu.log("[豆瓣缓存] 异常降级返回过期缓存: " + cacheKey);
                return stale.data;
            }
            return null;
        }
    }

    // ─── Category fetch (public entry) ──────────────────────────────────────
    public static JSONObject fetchCategory(String id, int page, Map<String, String> filters) {
        try {
            if (!isDouban(id)) return null;
            JSONObject result = _category(id, page, filters);
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

    // ─── Internal category dispatch ────────────────────────────────────────
    private static JSONObject fetchCategoryInternal(String id, int page, Map<String, String> filters) throws Exception {
        int pg = Math.max(page, 1);
        int start = (pg - 1) * COUNT;
        JSONArray items = new JSONArray();
        int total = 0;

        String sort = getFilter(filters, "排序", "U");
        sort = SORT_MAP.containsKey(sort) ? SORT_MAP.get(sort) : sort;

        // ── 最近更新 ──────────────────────────────────────────────────────
        if ("latest".equals(id)) {
            String contentType = getFilter(filters, "类型", "");
            String platform = getFilter(filters, "数据源", "");
            fetchLatest(platform, contentType, pg, sort, items);
            total = items.length() + COUNT;

        // ── 豆瓣热播 ────────────────────────────────────────────────────
        } else if ("douban_hot".equals(id)) {
            String u = getFilter(filters, "分类", "movie/hot_gaia");
            String safeU = u.replaceAll("^/+", "");
            String url;
            if (safeU.startsWith("subject_collection/")) {
                url = HOST + "/" + safeU + "?start=" + start + "&count=" + COUNT;
            } else {
                url = HOST + "/" + safeU + "?area=\u5168\u90e8&sort=recommend&playable=0&loc_id=0&start=" + start + "&count=" + COUNT;
            }
            JSONObject data = requestDouban(url);
            if (data != null) {
                JSONArray arr = data.optJSONArray("subject_collection_items");
                if (arr != null) {
                    mergeItems(items, arr);
                } else {
                    mergeItems(items, data.optJSONArray("items"));
                }
                total = data.optInt("total", items.length() + COUNT);
            }
            // 不足40条时自动翻页补齐（最多3轮）
            int offset = start + COUNT;
            for (int round = 0; round < 3 && items.length() < COUNT; round++) {
                int before = items.length();
                String fillUrl;
                if (safeU.startsWith("subject_collection/")) {
                    fillUrl = HOST + "/" + safeU + "?start=" + offset + "&count=" + COUNT;
                } else {
                    fillUrl = HOST + "/" + safeU + "?area=\u5168\u90e8&sort=recommend&playable=0&loc_id=0&start=" + offset + "&count=" + COUNT;
                }
                JSONObject fillData = requestDouban(fillUrl);
                if (fillData != null) {
                    JSONArray fillArr = fillData.optJSONArray("subject_collection_items");
                    if (fillArr != null) {
                        mergeItems(items, fillArr);
                    } else {
                        mergeItems(items, fillData.optJSONArray("items"));
                    }
                }
                if (items.length() == before) break;
                offset += COUNT;
            }
            total = Math.max(total, items.length());

        // ── 热门电影 ──────────────────────────────────────────────────────
        } else if ("movie".equals(id)) {
            String platTag = getFilter(filters, "平台", "热门");
            fetchHotMovie(platTag, pg, items);
            total = items.length() + COUNT;

        // ── 热门剧集 ──────────────────────────────────────────────────────
        } else if ("tv".equals(id)) {
            String platTag = getFilter(filters, "平台", "热门");
            fetchHotTv(platTag, pg, items);
            total = items.length() + COUNT;

        // ── 热播综艺 ──────────────────────────────────────────────────────
        } else if ("show".equals(id)) {
            String platTag = getFilter(filters, "平台", "");
            fetchHotZy(platTag, pg, items);
            total = items.length() + COUNT;

        // ── 热播动漫 ──────────────────────────────────────────────────────
        } else if ("anime".equals(id)) {
            String platTag = getFilter(filters, "平台", "");
            fetchHotDm(platTag, pg, items);
            total = items.length() + COUNT;

        // ── 电影榜单（翻页守卫：榜单API不支持翻页）───────────────────────
        } else if ("hot_movie".equals(id)) {
            if (pg == 1) {
                String slug = getFilter(filters, "榜单", "");
                String tag = getFilter(filters, "标签", "");
                if (!TextUtils.isEmpty(tag)) {
                    String url = SEARCH_HOST + "/j/search_subjects?type=movie&tag=" + URLEncoder.encode(tag, "UTF-8") + "&page_limit=50&page_start=" + ((pg - 1) * 50);
                    JSONObject data = requestDoubanSearch(url);
                    if (data != null) mergeItems(items, data.optJSONArray("subjects"));
                } else {
                    if (TextUtils.isEmpty(slug)) slug = "movie_real_time_hotest";
                    fetchMovielist(slug, pg, items);
                }
                total = items.length() + 20;
            }

        // ── 剧集榜单（翻页守卫：榜单API不支持翻页）───────────────────────
        } else if ("hot_tv".equals(id)) {
            if (pg == 1) {
                String slug = getFilter(filters, "榜单", "");
                String tag = getFilter(filters, "标签", "");
                if (!TextUtils.isEmpty(tag)) {
                    String url = SEARCH_HOST + "/j/search_subjects?type=tv&tag=" + URLEncoder.encode(tag, "UTF-8") + "&page_limit=50&page_start=" + ((pg - 1) * 50);
                    JSONObject data = requestDoubanSearch(url);
                    if (data != null) mergeItems(items, data.optJSONArray("subjects"));
                } else {
                    if (TextUtils.isEmpty(slug)) slug = "tv_real_time_hotest";
                    fetchTvlist(slug, pg, items);
                }
                total = items.length() + 20;
            }

        // ── 综艺榜单 ─────────────────────────────────────────────────────
        } else if ("hot_show".equals(id)) {
            if (pg == 1) {
                String slug = getFilter(filters, "榜单", "");
                if (TextUtils.isEmpty(slug)) slug = "tv_variety_show";
                JSONObject data = requestDouban(HOST + "/subject_collection/" + slug + "/items?start=" + (pg - 1) * COUNT + "&count=" + COUNT);
                if (data != null) {
                    mergeItems(items, data.optJSONArray("subject_collection_items"));
                }
                total = items.length() + 20;
            }

        // ── 电影筛选 ──────────────────────────────────────────────────────
        } else if ("movie_filter".equals(id)) {
            String tagType = getFilter(filters, "类型", "");
            String tagRegion = getFilter(filters, "地区", "");
            String tagYear = getFilter(filters, "年代", "");
            String tagLabel = getFilter(filters, "标签", "");
            String tagSort = getFilter(filters, "排序", "U");
            tagSort = SORT_MAP.containsKey(tagSort) ? SORT_MAP.get(tagSort) : "U";
            StringBuilder tags = new StringBuilder();
            appendTag(tags, tagType);
            appendTag(tags, tagRegion);
            appendTag(tags, tagYear);
            appendTag(tags, tagLabel);
            fetchFiltered("movie", tags.toString(), tagSort, start, items);
            total = items.length() + COUNT;

        // ── 电视筛选 ──────────────────────────────────────────────────────
        } else if ("tv_filter".equals(id)) {
            String tagType = getFilter(filters, "类型", "");
            String tagTv = getFilter(filters, "剧集", "");
            String tagZy = getFilter(filters, "综艺", "");
            String tagRegion = getFilter(filters, "地区", "");
            String tagYear = getFilter(filters, "年代", "");
            String tagPlatform = getFilter(filters, "平台", "");
            String tagLabel = getFilter(filters, "标签", "");
            String tagSort = getFilter(filters, "排序", "U");
            tagSort = SORT_MAP.containsKey(tagSort) ? SORT_MAP.get(tagSort) : "U";

            String effectiveType = tagType;
            String effectiveSub = "";
            if ("电视剧".equals(tagType)) effectiveSub = tagTv;
            else if ("综艺".equals(tagType)) effectiveSub = tagZy;

            StringBuilder tags = new StringBuilder();
            if (TextUtils.isEmpty(effectiveSub)) {
                appendTag(tags, effectiveType);
            } else {
                String mainType = "电视剧".equals(tagType) ? "电视剧" : "综艺".equals(tagType) ? "综艺" : "";
                appendTag(tags, mainType);
                appendTag(tags, effectiveSub);
            }
            appendTag(tags, tagRegion);
            appendTag(tags, tagYear);
            appendTag(tags, tagPlatform);
            appendTag(tags, tagLabel);

            fetchFiltered("tv", tags.toString(), tagSort, start, items);
            total = items.length() + COUNT;

        // ── 豆瓣动漫（合并剧集+电影）─────────────────────────────────────
        } else if ("douban_anime".equals(id)) {
            String slug = getFilter(filters, "榜单", "anime_recent");
            String tagType = getFilter(filters, "类型", "");
            String tagRegion = getFilter(filters, "地区", "");
            String tagYear = getFilter(filters, "年代", "");
            String tagSort = getFilter(filters, "排序", "U");
            String partSort = SORT_MAP.containsKey(tagSort) ? SORT_MAP.get(tagSort) : tagSort;

            // slug → sort mapping (when no explicit sort)
            Map<String, String> slugSort = new HashMap<>();
            slugSort.put("anime_recent", "R");
            slugSort.put("anime_hot", "U");
            slugSort.put("anime_best", "S");
            String effectiveSort = slugSort.containsKey(slug) ? slugSort.get(slug) : partSort;

            StringBuilder tags = new StringBuilder("动画");
            if (!TextUtils.isEmpty(tagType) && !"动画".equals(tagType)) {
                appendTag(tags, tagType);
            }
            appendTag(tags, tagRegion);
            appendTag(tags, tagYear);

            // fetch from both movie and tv recommend
            for (String ep : new String[]{"/movie/recommend", "/tv/recommend"}) {
                String url = HOST + ep + "?sort=" + effectiveSort + "&start=" + start + "&count=" + COUNT;
                if (tags.length() > 0) {
                    url += "&tags=" + URLEncoder.encode(tags.toString(), "UTF-8");
                }
                JSONObject data = requestDouban(url);
                if (data != null && data.optJSONArray("items") != null) {
                    mergeItems(items, data.optJSONArray("items"));
                }
            }
            total = Math.max(items.length(), COUNT);

        // ── 豆瓣电影Top250 ──────────────────────────────────────────────
        } else if ("top_250".equals(id)) {
            String slug = getFilter(filters, "榜单", "movie_top250");
            JSONObject data = requestDouban(HOST + "/subject_collection/" + slug + "/items?start=" + start + "&count=" + COUNT);
            if (data != null) {
                mergeItems(items, data.optJSONArray("subject_collection_items"));
            }
            total = items.length() + COUNT;

        // ── TMDB 动漫榜 ─────────────────────────────────────────────────
        } else if ("anime_tmdb".equals(id)) {
            fetchTmdbAnimeRanking(pg, filters, items);
            total = items.length() + 20;
            if (total < COUNT) total = COUNT;

        // ── 豆瓣片单 ────────────────────────────────────────────────────
        } else if ("douban_playlist".equals(id)) {
            String type = getFilter(filters, "类型", "");
            String cate = getFilter(filters, "分类", "all");
            String plUrl = HOST + "/skynet/new_playlists?subject_type=" + URLEncoder.encode(type, "UTF-8")
                    + "&category=" + URLEncoder.encode(cate, "UTF-8") + "&loc_id=0&start=" + start + "&count=" + COUNT;
            JSONObject plData = requestDoubanSigned(plUrl);
            if (plData != null) {
                JSONArray rows = plData.optJSONArray("items");
                if (rows == null) {
                    JSONArray dataArr = plData.optJSONArray("data");
                    if (dataArr != null && dataArr.length() > 0) {
                        JSONObject first = dataArr.optJSONObject(0);
                        if (first != null) rows = first.optJSONArray("items");
                    }
                }
                if (rows == null) {
                    JSONObject dataObj = plData.optJSONObject("data");
                    if (dataObj != null) rows = dataObj.optJSONArray("items");
                }
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject vod = rows.optJSONObject(i);
                        if (vod == null) continue;
                        JSONObject folderItem = new JSONObject();
                        String prefix = vod.has("owner") ? "dl_" : "cr_";
                        folderItem.put("vod_id", prefix + vod.optString("id", ""));
                        folderItem.put("vod_name", vod.optString("title", "\u672a\u77e5"));
                        folderItem.put("vod_pic", vod.optString("cover_url", ""));
                        folderItem.put("vod_remarks", "");
                        folderItem.put("vod_tag", "folder");
                        JSONObject style = new JSONObject();
                        style.put("type", "rect");
                        style.put("ratio", 1);
                        folderItem.put("style", style);
                        items.put(folderItem);
                    }
                }
                total = plData.optInt("total", items.length() + COUNT);
            }

        // ── 片单二级：cr_=subject_collection, dl_=doulist ─────────────
        } else if (id.startsWith("cr_")) {
            String cid = id.substring(3);
            JSONObject data = requestDouban(HOST + "/subject_collection/" + cid + "/items?start=" + start + "&count=" + COUNT + "&updated_at=&items_only=1");
            if (data != null) {
                JSONArray arr = data.optJSONArray("subject_collection_items");
                if (arr == null) arr = data.optJSONArray("items");
                mergeItems(items, arr);
                total = data.optInt("total", items.length() + COUNT);
            } else {
                total = items.length() + COUNT;
            }

        } else if (id.startsWith("dl_")) {
            String did = id.substring(3);
            JSONObject data = requestDouban(HOST + "/doulist/" + did + "/posts?start=" + start + "&count=" + COUNT + "&updated_at=&items_only=1");
            if (data != null) {
                JSONArray rows = data.optJSONArray("items");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        JSONObject subject = row.optJSONObject("content");
                        if (subject != null) subject = subject.optJSONObject("subject");
                        if (subject != null) items.put(subject);
                    }
                }
                total = data.optInt("total", items.length() + COUNT);
            } else {
                total = items.length() + COUNT;
            }
        }

        JSONArray mapped = mapItems(items);

        // 剧集/动漫/综艺分类追加 TMDB 集数信息
        if (TV_CATEGORIES.contains(id) && !tmdbBreaker.isOpen()) {
            batchAppendTmdbEpisodeInfo(mapped);
        }

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

    // ─── Latest (最近更新) ─────────────────────────────────────────────────
    private static void fetchLatest(String platform, String contentType, int pg, String sort, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platform)) platform = "douban_ru";
        if (TextUtils.isEmpty(contentType)) contentType = "ru_movie";

        if (!"douban_ru".equals(platform)) {
            String platName = prefixToPlatform(platform);
            if (isKnownPlatform(platform) && platName != null && !"douban".equals(platName)) {
                String typeName = RU_TYPE_MAP.containsKey(contentType) ? RU_TYPE_MAP.get(contentType) : "movie";
                String lftxs = "tencent".equals(platName) ? PlatformFetcher.tencentLatestLftxs(typeName) : "U";
                mergeItems(items, PlatformFetcher.fetchPlatform(platName, typeName, pg, lftxs));
            }
            return;
        }
        // douban branch — frodo默认排序（无sort=参数）更接近rexxar的推荐算法
        String year = new SimpleDateFormat("yyyy", Locale.getDefault()).format(new Date());
        String ep;
        String typeTag;
        if ("ru_movie".equals(contentType)) {
            ep = "movie/recommend";
            typeTag = year;
        } else {
            ep = "tv/recommend";
            if ("ru_tv".equals(contentType)) {
                typeTag = "电视剧," + year;
            } else if ("ru_zy".equals(contentType)) {
                typeTag = "综艺," + year;
            } else { // ru_dm or fallback
                typeTag = "动漫," + year;
            }
        }
        String url = HOST + "/" + ep + "?start=" + (pg - 1) * COUNT + "&count=" + COUNT
            + "&tags=" + URLEncoder.encode(typeTag, "UTF-8");
        JSONObject data = requestDouban(url);
        if (data != null) {
            JSONArray rawItems = data.optJSONArray("items");
            if (rawItems != null) {
                // 检查frodo API是否返回type字段；若无则跳过类型过滤（都来自正确的endpoint）
                boolean hasTypeField = rawItems.length() > 0 && rawItems.optJSONObject(0) != null && rawItems.optJSONObject(0).has("type");
                String filterType = "ru_movie".equals(contentType) ? "movie" : "tv";
                for (int i = 0; i < rawItems.length(); i++) {
                    JSONObject item = rawItems.optJSONObject(i);
                    if (item != null && (!hasTypeField || filterType.equals(item.optString("type")))) {
                        items.put(item);
                    }
                }
            }
        }
    }

    // ─── Hot Movie (热门电影) ──────────────────────────────────────────────
    private static void fetchHotMovie(String platTag, int pg, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platTag)) platTag = "热门";
        if (isKnownPlatform(platTag)) {
            mergeItems(items, PlatformFetcher.fetchPlatform(prefixToPlatform(platTag), "movie", pg, "75"));
            return;
        }
        String url = SEARCH_HOST + "/j/search_subjects?type=movie&tag=" + URLEncoder.encode(platTag, "UTF-8") + "&page_limit=50&page_start=" + ((pg - 1) * 50);
        JSONObject data = requestDoubanSearch(url);
        if (data != null) mergeItems(items, data.optJSONArray("subjects"));
    }

    // ─── Hot TV (热门剧集) ─────────────────────────────────────────────────
    private static void fetchHotTv(String platTag, int pg, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platTag)) platTag = "热门";
        if (isKnownPlatform(platTag)) {
            mergeItems(items, PlatformFetcher.fetchPlatform(prefixToPlatform(platTag), "tv", pg, "75"));
            return;
        }
        String url = SEARCH_HOST + "/j/search_subjects?type=tv&tag=" + URLEncoder.encode(platTag, "UTF-8") + "&page_limit=50&page_start=" + ((pg - 1) * 50);
        JSONObject data = requestDoubanSearch(url);
        if (data != null) mergeItems(items, data.optJSONArray("subjects"));
    }

    // ─── Hot Variety (热播综艺) ────────────────────────────────────────────
    private static void fetchHotZy(String platTag, int pg, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platTag)) platTag = "zy_all";

        if (isKnownPlatform(platTag)) {
            mergeItems(items, PlatformFetcher.fetchPlatform(prefixToPlatform(platTag), "variety", pg, "75"));
            return;
        }

        // 豆瓣: fetch from douban subject_collection show_hot
        JSONObject data = requestDouban(HOST + "/subject_collection/show_hot/items?start=0&count=50&updated_at=&items_only=1&for_mobile=1");
        if (data != null) {
            JSONArray arr = data.optJSONArray("subject_collection_items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String subTitle = item.optString("card_subtitle", "");
                    if ("zy_cn".equals(platTag) && !subTitle.contains("中国")) continue;
                    if ("zy_other".equals(platTag) && subTitle.contains("中国")) continue;
                    // For zy_all, include all
                    items.put(item);
                }
            }
        }
    }

    // ─── Hot Anime (热播动漫) ──────────────────────────────────────────────
    private static void fetchHotDm(String platTag, int pg, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platTag)) platTag = "tx_hot_dm";
        if (isKnownPlatform(platTag)) {
            String[] fallbacks = {platTag, "iqy_hot_dm", "youku_hot_dm", "mgtv_hot_dm", "360ys_hot_dm", "sogousp_hot_dm"};
            Set<String> tried = new HashSet<>();
            for (String tag : fallbacks) {
                if (tried.contains(tag)) continue;
                tried.add(tag);
                String platName = prefixToPlatform(tag);
                JSONArray result = PlatformFetcher.fetchPlatform(platName, "anime", pg, "75");
                if (result != null && result.length() > 0) {
                    mergeItems(items, result);
                    return;
                }
            }
        }
    }

    // ─── Movie List (电影榜单) ─────────────────────────────────────────────
    private static void fetchMovielist(String slug, int pg, JSONArray items) throws Exception {
        if (slug.startsWith("rank_hot_movie_")) {
            // Tencent rank
            List<JSONObject> list = PlatformFetcher.fetchTencentRank("movie", slug, pg);
            for (JSONObject v : list) items.put(v);
        } else if (slug.startsWith("ranks1/")) {
            // iQiyi rank (slug e.g. "ranks1/1/2" for movie, "ranks1/2/2" for tv)
            mergeItems(items, PlatformFetcher.fetchIqiyiRank(slug, pg));
        } else if (slug.startsWith("youku_movie_")) {
            // Youku rank
            int idx = Integer.parseInt(slug.replace("youku_movie_", ""));
            mergeItems(items, PlatformFetcher.fetchYoukuRank("movie", idx, pg));
        } else if (slug.startsWith("rank?cat=")) {
            // 360 rank
            mergeItems(items, PlatformFetcher.fetch360kanRank(slug.replace("rank?cat=", "")));
        } else if ("top250".equals(slug)) {
            JSONObject data = requestDouban(HOST + "/subject_collection/movie_top250/items?start=" + (pg - 1) * 25 + "&count=25");
            if (data != null) {
                mergeItems(items, data.optJSONArray("subject_collection_items"));
            }
        } else {
            // Douban subject collection
            JSONObject data = requestDouban(HOST + "/subject_collection/" + slug + "/items?updated_at=&items_only=1&for_mobile=1");
            if (data != null) {
                mergeItems(items, data.optJSONArray("subject_collection_items"));
            }
        }
    }

    // ─── TV List (剧集榜单) ───────────────────────────────────────────────
    private static void fetchTvlist(String slug, int pg, JSONArray items) throws Exception {
        if (slug.startsWith("rank_hot_tv_")) {
            List<JSONObject> list = PlatformFetcher.fetchTencentRank("tv", slug, pg);
            for (JSONObject v : list) items.put(v);
        } else if (slug.startsWith("ranks1/")) {
            mergeItems(items, PlatformFetcher.fetchIqiyiRank(slug, pg));
        } else if (slug.startsWith("youku_tv_")) {
            int idx = Integer.parseInt(slug.replace("youku_tv_", ""));
            mergeItems(items, PlatformFetcher.fetchYoukuRank("tv", idx, pg));
        } else if (slug.startsWith("rank?cat=")) {
            mergeItems(items, PlatformFetcher.fetch360kanRank(slug.replace("rank?cat=", "")));
        } else {
            JSONObject data = requestDouban(HOST + "/subject_collection/" + slug + "/items?updated_at=&items_only=1&for_mobile=1");
            if (data != null) {
                mergeItems(items, data.optJSONArray("subject_collection_items"));
            }
        }
    }

    // ─── Filtered search (电影/电视 筛选) ─────────────────────────────────
    private static void fetchFiltered(String type, String tags, String sort, int start, JSONArray items) throws Exception {
        String url = HOST + "/" + type + "/recommend?sort=" + sort + "&start=" + start + "&count=" + COUNT;
        if (!TextUtils.isEmpty(tags)) {
            url += "&tags=" + URLEncoder.encode(tags, "UTF-8");
        }
        JSONObject data = requestDouban(url);
        if (data != null) {
            mergeItems(items, data.optJSONArray("items"));
        }
    }

    // ─── Helper: extract filter with default ───────────────────────────────
    private static String getFilter(Map<String, String> filters, String key, String def) {
        if (filters == null) return def;
        String v = filters.get(key);
        return v != null ? v : def;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────
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
                if (TextUtils.isEmpty(id)) id = item.optString("vod_id");
                if (!TextUtils.isEmpty(id)) seen.add(id);
            }
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (TextUtils.isEmpty(id)) id = item.optString("vod_id");
            if (TextUtils.isEmpty(id) || seen.contains(id)) continue;
            seen.add(id);
            target.put(item);
        }
    }

    // ─── Map raw items → vod format ────────────────────────────────────────
    private static JSONArray mapItems(JSONArray items) {
        JSONArray list = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            try {
                JSONObject raw = items.getJSONObject(i);
                if (raw.has("vod_id") && raw.has("vod_name")) {
                    list.put(raw);
                    continue;
                }

                String title = raw.optString("title", "");
                if (TextUtils.isEmpty(title)) title = "未知";

                String rawId = raw.optString("id", "");
                if (TextUtils.isEmpty(rawId)) continue;

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
                    remarks = "\u8bc4\u5206: " + String.format("%.1f", ratingVal) + "\u5206";
                    if (!TextUtils.isEmpty(pubdate)) remarks += " \u00b7 " + pubdate;
                } else {
                    remarks = "\u6682\u65e0\u8bc4\u5206";
                    if (!TextUtils.isEmpty(pubdate)) remarks += " \u00b7 " + pubdate;
                }

                JSONObject vod = new JSONObject();
                vod.put("vod_id", rawId.trim());
                vod.put("vod_name", title);
                vod.put("vod_pic", pic);
                vod.put("vod_remarks", remarks);
                vod.put("goSearch", true);
                list.put(vod);
            } catch (Exception ignored) {}
        }
        return list;
    }

    // ─── Utility methods ────────────────────────────────────────────────────
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

    private static int randomInt(int min, int max) {
        return RANDOM.nextInt(max - min + 1) + min;
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
            return null;
        }
    }

    // ─── 签名 API 请求（豆瓣片单用）──────────────────────────────────────────
    private static JSONObject requestDoubanSigned(String url) {
        try {
            String withBase = url + (url.contains("?") ? "&" : "?")
                    + "udid=" + DOUBAN_DEVICE_ID + "&uuid=" + DOUBAN_DEVICE_ID + "&rom=android&apikey=" + DOUBAN_APP_KEY
                    + "&s=rexxar_new&channel=Yingyongbao_Market&timezone=Asia/Shanghai&device_id=" + DOUBAN_DEVICE_ID
                    + "&os_rom=android&apple=c52fbb99b908be4d026954cc4374f16d&mooncake=0f607264fc6318a92b9e13c65db7cd3c&sugar=0";
            java.net.URL u = new java.net.URL(withBase);
            String pathname = u.getPath();
            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            String signText = "GET&" + URLEncoder.encode(pathname, "UTF-8") + "&" + ts;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(DOUBAN_SIGN_SECRET.getBytes("UTF-8"), "HmacSHA1");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(signText.getBytes("UTF-8"));
            String sig = URLEncoder.encode(android.util.Base64.encodeToString(rawHmac, android.util.Base64.NO_WRAP), "UTF-8");
            String finalUrl = withBase + "&_sig=" + sig + "&_ts=" + ts;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", DOUBAN_APP_UA);
            String body = OkHttp.string(finalUrl, headers);
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            // Leodanmu.log("[豆瓣签名] 请求失败: " + e.getMessage());
            return null;
        }
    }

    private static JSONObject requestDoubanSearch(String url) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36");
            headers.put("Referer", "https://movie.douban.com/");
            String body = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== TMDB API =====================

    private static JSONObject requestTmdb(String endpoint, Map<String, String> params) {
        if (tmdbBreaker.isOpen()) {
            Leodanmu.log("[TMDB] 熔断已开启，跳过请求: " + endpoint);
            return null;
        }
        try {
            StringBuilder url = new StringBuilder("https://api.themoviedb.org/3");
            url.append(endpoint);
            url.append(endpoint.contains("?") ? "&" : "?");
            url.append("api_key=").append(TMDB_API_KEY);
            url.append("&language=zh-CN");
            if (params != null) {
                for (Map.Entry<String, String> e : params.entrySet()) {
                    if (!TextUtils.isEmpty(e.getValue())) {
                        url.append("&").append(URLEncoder.encode(e.getKey(), "UTF-8"))
                                .append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
                    }
                }
            }
            String body = OkHttp.string(url.toString(), 5000);
            if (TextUtils.isEmpty(body)) {
                tmdbBreaker.onFailure();
                return null;
            }
            JSONObject data = new JSONObject(body);
            if (data.has("success") && !data.optBoolean("success", true)) {
                tmdbBreaker.onFailure();
                return null;
            }
            tmdbBreaker.onSuccess();
            return data;
        } catch (Exception e) {
            tmdbBreaker.onFailure();
            Leodanmu.log("[TMDB] 请求异常: " + e.getMessage());
            return null;
        }
    }

    private static void fetchTmdbAnimeRanking(int pg, Map<String, String> filters, JSONArray items) throws Exception {
        String sortKey = getFilter(filters, "榜单", "hot_following");
        String yearFilter = getFilter(filters, "年份", "all");
        String regionFilter = getFilter(filters, "地区", "all");

        Map<String, String> params = new HashMap<>();
        params.put("with_genres", "16");
        params.put("include_adult", "false");
        params.put("page", String.valueOf(pg));

        switch (sortKey) {
            case "latest_premiere":
                params.put("sort_by", "first_air_date.desc");
                break;
            case "popular_reputation":
                params.put("sort_by", "vote_average.desc");
                params.put("vote_count.gte", "cn".equals(regionFilter) ? "50" : "200");
                break;
            case "global_trending":
                params.put("sort_by", "popularity.desc");
                params.put("first_air_date.gte", (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 2) + "-01-01");
                params.put("vote_count.gte", "100");
                break;
            default: // hot_following
                params.put("sort_by", "popularity.desc");
                break;
        }

        if (!"all".equals(yearFilter)) {
            params.put("first_air_date.gte", yearFilter + "-01-01");
            params.put("first_air_date.lte", yearFilter + "-12-31");
        }
        if ("cn".equals(regionFilter)) params.put("with_origin_country", "CN");
        else if ("jp".equals(regionFilter)) params.put("with_origin_country", "JP");
        else if ("us".equals(regionFilter)) params.put("with_origin_country", "US");

        JSONObject data = requestTmdb("/discover/tv", params);
        if (data == null) return;

        JSONArray results = data.optJSONArray("results");
        if (results == null) return;

        // 过滤"其他"地区
        if ("other".equals(regionFilter)) {
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item != null) {
                    JSONArray countries = item.optJSONArray("origin_country");
                    if (countries == null || countries.length() == 0) continue;
                    String first = countries.optString(0, "");
                    if (!"CN".equals(first) && !"JP".equals(first) && !"US".equals(first)) {
                        filtered.put(item);
                    }
                }
            }
            results = filtered;
        }

        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item != null) items.put(toVodFromTmdbItem(item));
        }
    }

    private static JSONObject toVodFromTmdbItem(JSONObject item) {
        JSONObject vod = new JSONObject();
        try {
            String id = String.valueOf(item.optInt("id", 0));
            String name = item.optString("name", item.optString("original_name", "未知"));
            String posterPath = item.optString("poster_path", "");
            String pic = TextUtils.isEmpty(posterPath) ? "" : TMDB_IMAGE_BASE + posterPath;
            String firstAirDate = item.optString("first_air_date", "");
            String year = firstAirDate.length() >= 4 ? firstAirDate.substring(0, 4) : "未知";
            double voteAvg = item.optDouble("vote_average", 0);
            int totalEpisodes = item.optInt("number_of_episodes", 0);
            JSONObject lastEp = item.optJSONObject("last_episode_to_air");
            JSONObject nextEp = item.optJSONObject("next_episode_to_air");
            String status = item.optString("status", "");

            String remarks;
            if (voteAvg > 0) {
                remarks = String.format(Locale.US, "%.1f分", voteAvg);
            } else {
                remarks = "暂无评分";
            }

            if ("Ended".equals(status) || "Canceled".equals(status)) {
                if (totalEpisodes > 0) remarks += "(全" + totalEpisodes + "集)";
                else remarks += "(已完结)";
            } else if ("Returning Series".equals(status)) {
                if (nextEp != null) {
                    int nextEpNum = nextEp.optInt("episode_number", 0);
                    if (nextEpNum > 1) remarks += "(更至" + (nextEpNum - 1) + "集)";
                    else remarks += "(连载中)";
                } else if (lastEp != null) {
                    remarks += "(更至" + lastEp.optInt("episode_number", 0) + "集)";
                } else if (totalEpisodes > 0) {
                    remarks += "(共" + totalEpisodes + "集)";
                } else {
                    remarks += "(连载中)";
                }
            } else {
                if (totalEpisodes > 0) remarks += "(共" + totalEpisodes + "集)";
            }

            vod.put("vod_id", "tmdb_" + id);
            vod.put("vod_name", name);
            vod.put("vod_pic", pic);
            vod.put("vod_year", year);
            vod.put("vod_remarks", remarks);
            vod.put("vod_content", item.optString("overview", ""));
            vod.put("goSearch", true);
        } catch (Exception ignored) {}
        return vod;
    }

    private static String[][] buildYearFilterValues() {
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String[][] values = new String[12][2];
        values[0] = new String[]{"全部", "all"};
        for (int i = 0; i < 10; i++) {
            String y = String.valueOf(currentYear - i);
            values[i + 1] = new String[]{y, y};
        }
        values[11] = new String[]{"更早", "older"};
        return values;
    }

    // ===================== TMDB 集数追加 =====================

    private static boolean isTvCategory(String id) {
        return !"movie".equals(id) && !"hot_movie".equals(id) && !"movie_filter".equals(id) && !"top_250".equals(id);
    }

    private static Integer searchTmdbTV(String name, String year) {
        if (TextUtils.isEmpty(name)) return null;
        String cleanName = name.replaceAll("\\s*[\\-—]\\s*第[一二三四五六七八九十百千万\\d]+季\\s*$", "")
                .replaceAll("[（(][^）)]*[）)]", "").trim();
        if (TextUtils.isEmpty(cleanName)) cleanName = name;

        Map<String, String> params = new HashMap<>();
        params.put("query", cleanName);
        if (!TextUtils.isEmpty(year)) params.put("first_air_date_year", year);

        JSONObject data = requestTmdb("/search/tv", params);
        if (data != null) {
            JSONArray results = data.optJSONArray("results");
            if (results != null && results.length() > 0) {
                return results.optJSONObject(0).optInt("id", 0);
            }
        }
        // 不带年份再试一次
        if (!TextUtils.isEmpty(year)) {
            params.remove("first_air_date_year");
            data = requestTmdb("/search/tv", params);
            if (data != null) {
                JSONArray results = data.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    return results.optJSONObject(0).optInt("id", 0);
                }
            }
        }
        return null;
    }

    private static String getEpisodeTextFromTmdbDetail(JSONObject detail) {
        String status = detail.optString("status", "");
        int totalEpisodes = detail.optInt("number_of_episodes", 0);
        JSONObject lastEp = detail.optJSONObject("last_episode_to_air");
        JSONObject nextEp = detail.optJSONObject("next_episode_to_air");

        if ("Ended".equals(status) || "Canceled".equals(status)) {
            if (totalEpisodes > 0) return "(全" + totalEpisodes + "集)";
            return "(已完结)";
        }
        if ("Returning Series".equals(status)) {
            if (nextEp != null) {
                int nextEpNum = nextEp.optInt("episode_number", 0);
                if (nextEpNum > 1) return "(更至" + (nextEpNum - 1) + "集)";
                return "(连载中)";
            }
            if (lastEp != null) return "(更至" + lastEp.optInt("episode_number", 0) + "集)";
            if (totalEpisodes > 0) return "(共" + totalEpisodes + "集)";
            return "(连载中)";
        }
        if (totalEpisodes > 0) return "(共" + totalEpisodes + "集)";
        return "";
    }

    private static void batchAppendTmdbEpisodeInfo(JSONArray items) {
        if (items == null || items.length() == 0) return;
        Leodanmu.log("[TMDB集数] 开始批量查询，共" + items.length() + "条");
        long batchStart = System.currentTimeMillis();

        List<JSONObject> toProcess = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String remarks = item.optString("vod_remarks", "");
            if (remarks.contains("集") || remarks.contains("更至") || remarks.contains("全")) continue;
            toProcess.add(item);
        }

        int batchSize = 5;
        int matched = 0;
        int consecutiveFails = 0;
        for (int start = 0; start < toProcess.size(); start += batchSize) {
            int end = Math.min(start + batchSize, toProcess.size());
            for (int i = start; i < end; i++) {
                JSONObject item = toProcess.get(i);
                try {
                    String name = item.optString("vod_name", "");
                    String year = item.optString("vod_year", "");
                    if (TextUtils.isEmpty(name)) continue;
                    Integer tmdbId = searchTmdbTV(name, year);
                    if (tmdbId == null || tmdbId == 0) { consecutiveFails++; continue; }
                    JSONObject detail = requestTmdb("/tv/" + tmdbId, null);
                    if (detail == null) { consecutiveFails++; continue; }
                    consecutiveFails = 0;
                    String epText = getEpisodeTextFromTmdbDetail(detail);
                    if (!TextUtils.isEmpty(epText)) {
                        String currentRemarks = item.optString("vod_remarks", "");
                        item.put("vod_remarks", currentRemarks + epText);
                        matched++;
                    }
                } catch (Exception ignored) { consecutiveFails++; }
            }
            // 连续10次失败 → TMDB不可达，提前退出
            if (consecutiveFails >= 10) {
                Leodanmu.log("[TMDB集数] 连续10次失败，提前退出");
                break;
            }
            if (end < toProcess.size()) {
                try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            }
        }
        Leodanmu.log("[TMDB集数] 完成，匹配" + matched + "/" + toProcess.size() + "条，耗时" + (System.currentTimeMillis() - batchStart) + "ms");
    }

    private static final Set<String> TV_CATEGORIES = new HashSet<>(Arrays.asList(
            "latest", "douban_hot", "tv", "show", "anime", "douban_anime",
            "hot_tv", "hot_show", "tv_filter", "douban_playlist",
            "anime_tmdb"
    ));
}
