package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
            "latest", "movie", "tv", "show", "anime",
            "hot_movie", "hot_tv",
            "movie_filter", "tv_filter"
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
        return CATEGORIES.contains(tid);
    }

    // ─── Categories ─────────────────────────────────────────────────────────
    public static JSONArray getCategories() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(classObj("latest", "\u6700\u8fd1\u66f4\u65b0"));
        arr.put(classObj("movie", "\u70ed\u95e8\u7535\u5f71"));
        arr.put(classObj("tv", "\u70ed\u95e8\u5267\u96c6"));
        arr.put(classObj("show", "\u70ed\u64ad\u7efc\u827a"));
        arr.put(classObj("anime", "\u70ed\u64ad\u52a8\u6f2b"));
        arr.put(classObj("hot_movie", "\u7535\u5f71\u699c\u5355"));
        arr.put(classObj("hot_tv", "\u5267\u96c6\u699c\u5355"));
        arr.put(classObj("movie_filter", "\u7535\u5f71\u7b5b\u9009"));
        arr.put(classObj("tv_filter", "\u7535\u89c6\u7b5b\u9009"));
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

        // ── 热门电影 ─────────────────────────────────────────────────────
        root.put("movie", buildFilters(
                filter("平台", "平台", "", new String[][]{
                        {"爱奇艺", "iqy_hot_movie"}, {"腾讯", "tx_hot_movie"}, {"优酷", "youku_hot_movie"},
                        {"芒果TV", "mgtv_hot_movie"}, {"360影视", "360ys_hot_movie"}, {"搜狗视频", "sogousp_hot_movie"}
                })
        ));

        // ── 热门剧集 ─────────────────────────────────────────────────────
        root.put("tv", buildFilters(
                filter("平台", "平台", "", new String[][]{
                        {"爱奇艺", "iqy_hot_tv"}, {"腾讯", "tx_hot_tv"}, {"优酷", "youku_hot_tv"},
                        {"芒果TV", "mgtv_hot_tv"}, {"360影视", "360ys_hot_tv"}, {"搜狗视频", "sogousp_hot_tv"}
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
                        {"豆瓣电影Top250", "movie_top250"},
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
                        {"爱奇艺战争榜", "ranks1/1/4705204050526533"},
                        {"腾讯剧情榜", "rank_hot_movie_story"},
                        {"腾讯口碑经典榜", "rank_hot_movie_classic"},
                        {"腾讯喜剧片榜", "rank_hot_movie_comedy"},
                        {"腾讯爱情榜", "rank_hot_movie_love"},
                        {"腾讯科幻片榜", "rank_hot_movie_fiction"},
                        {"优酷热度榜", "youku_movie_0"},
                        {"优酷上新榜", "youku_movie_1"},
                        {"优酷高分榜", "youku_movie_2"},
                        {"优酷免费榜", "youku_movie_3"},
                        {"优酷网打榜", "youku_movie_4"},
                        {"优酷港片榜", "youku_movie_5"},
                        {"优酷喜剧榜", "youku_movie_6"}
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
        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();

        // 多平台首页聚合 (参照 JS homeVod / br_requests)
        mergeItems(merged, PlatformFetcher.fetchTencent("movie", 1, "75"), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchTencent("tv", 1, "75"), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchTencent("variety", 1, "75"), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchTencent("anime", 1, "75"), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchIqiyi("movie", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchIqiyi("tv", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchIqiyi("variety", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchIqiyi("anime", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchYouku("movie", 1, "new"), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchYouku("tv", 1, "hot"), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchYouku("variety", 1, "hot"), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchMangoTV("3", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchMangoTV("2", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetch360kan("1", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetch360kan("2", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchSogou("film", 1), seen, 200);
        mergeItems(merged, PlatformFetcher.fetchSogou("tv", 1), seen, 200);

        // 豆瓣已有内容
        List<String> sources = Arrays.asList("latest", "hot_movie", "hot_tv", "show", "anime");
        for (String id : sources) {
            try {
                JSONObject data = fetchCategoryInternal(id, 1, null);
                if (data == null) continue;
                mergeItems(merged, data.optJSONArray("list"), seen, 200);
            } catch (Exception ignored) {}
        }
        return merged;
    }

    // ─── Category fetch (public entry) ──────────────────────────────────────
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

        // ── 热门电影 ──────────────────────────────────────────────────────
        } else if ("movie".equals(id)) {
            String platTag = getFilter(filters, "平台", "");
            fetchHotMovie(platTag, pg, items);
            total = items.length() + COUNT;

        // ── 热门剧集 ──────────────────────────────────────────────────────
        } else if ("tv".equals(id)) {
            String platTag = getFilter(filters, "平台", "");
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

        // ── 电影榜单 ──────────────────────────────────────────────────────
        } else if ("hot_movie".equals(id)) {
            String slug = getFilter(filters, "榜单", "");
            if (TextUtils.isEmpty(slug)) slug = "movie_real_time_hotest";
            fetchMovielist(slug, pg, items);
            total = items.length() + 20;

        // ── 剧集榜单 ──────────────────────────────────────────────────────
        } else if ("hot_tv".equals(id)) {
            String slug = getFilter(filters, "榜单", "");
            if (TextUtils.isEmpty(slug)) slug = "tv_real_time_hotest";
            fetchTvlist(slug, pg, items);
            total = items.length() + 20;

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
        }

        int offset = start + COUNT;
        for (int round = 0; round < 2 && items.length() < COUNT; round++) {
            int before = items.length();
            if ("latest".equals(id)) {
                String platform = getFilter(filters, "数据源", "");
                String contentType = getFilter(filters, "类型", "");
                String platName = prefixToPlatform(platform);
                boolean isDoubanAll = "douban".equals(platName) && TextUtils.isEmpty(contentType);
                if (isDoubanAll) {
                    JSONObject md = requestDouban(HOST + "/movie/recommend?sort=R&start=" + offset + "&count=" + COUNT);
                    if (md != null) mergeItems(items, md.optJSONArray("items"));
                    JSONObject td = requestDouban(HOST + "/tv/recommend?sort=R&start=" + offset + "&count=" + COUNT);
                    if (td != null) mergeItems(items, td.optJSONArray("items"));
                }
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

    // ─── Latest (最近更新) ─────────────────────────────────────────────────
    private static void fetchLatest(String platform, String contentType, int pg, String sort, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platform)) platform = "douban_ru";
        if (TextUtils.isEmpty(contentType)) contentType = "ru_all";

        if (!"douban_ru".equals(platform)) {
            String platName = prefixToPlatform(platform);
            if (isKnownPlatform(platform) && platName != null && !"douban".equals(platName)) {
                String lftxs = "tencent".equals(platName) ? PlatformFetcher.tencentLatestLftxs("movie") : "U";
                if ("ru_all".equals(contentType)) {
                    mergeItems(items, PlatformFetcher.fetchPlatform(platName, "movie", pg, lftxs));
                    lftxs = "tencent".equals(platName) ? PlatformFetcher.tencentLatestLftxs("tv") : "U";
                    mergeItems(items, PlatformFetcher.fetchPlatform(platName, "tv", pg, lftxs));
                } else {
                    String typeName = RU_TYPE_MAP.containsKey(contentType) ? RU_TYPE_MAP.get(contentType) : "movie";
                    lftxs = "tencent".equals(platName) ? PlatformFetcher.tencentLatestLftxs(typeName) : "U";
                    mergeItems(items, PlatformFetcher.fetchPlatform(platName, typeName, pg, lftxs));
                }
            }
            return;
        }
        // douban branch
        if ("ru_all".equals(contentType)) {
            JSONObject md = requestDouban(HOST + "/movie/recommend?sort=R&start=" + (pg - 1) * COUNT + "&count=" + COUNT);
            if (md != null) mergeItems(items, md.optJSONArray("items"));
            JSONObject td = requestDouban(HOST + "/tv/recommend?sort=R&start=" + (pg - 1) * COUNT + "&count=" + COUNT);
            if (td != null) mergeItems(items, td.optJSONArray("items"));
        } else {
            String year = new SimpleDateFormat("yyyy", Locale.getDefault()).format(new Date());
            String ep;
            String typeTag;
            if ("ru_movie".equals(contentType)) {
                ep = "movie/recommend";
                typeTag = "";
            } else {
                ep = "tv/recommend";
                if ("ru_tv".equals(contentType)) typeTag = "电视剧";
                else if ("ru_zy".equals(contentType)) typeTag = "综艺";
                else if ("ru_dm".equals(contentType)) typeTag = "日本动漫,动漫";
                else typeTag = "";
            }
            String tags = typeTag;
            if (!TextUtils.isEmpty(tags)) tags = "," + tags;
            String url = HOST + "/" + ep + "?tags=" + URLEncoder.encode(year + tags, "UTF-8")
                    + "&sort=R&start=" + (pg - 1) * COUNT + "&count=" + COUNT;
            JSONObject data = requestDouban(url);
            if (data != null) mergeItems(items, data.optJSONArray("items"));
        }
    }

    // ─── Hot Movie (热门电影) ──────────────────────────────────────────────
    private static void fetchHotMovie(String platTag, int pg, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platTag)) platTag = "";
        if (isKnownPlatform(platTag)) {
            mergeItems(items, PlatformFetcher.fetchPlatform(prefixToPlatform(platTag), "movie", pg, "U"));
            return;
        }
        // Use frodo API with tag
        String url = HOST + "/movie/recommend?sort=U&start=" + (pg - 1) * COUNT + "&count=" + COUNT;
        if (!TextUtils.isEmpty(platTag)) {
            url += "&tags=" + URLEncoder.encode(platTag, "UTF-8");
        }
        JSONObject data = requestDouban(url);
        if (data != null) mergeItems(items, data.optJSONArray("items"));
    }

    // ─── Hot TV (热门剧集) ─────────────────────────────────────────────────
    private static void fetchHotTv(String platTag, int pg, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platTag)) platTag = "";
        if (isKnownPlatform(platTag)) {
            mergeItems(items, PlatformFetcher.fetchPlatform(prefixToPlatform(platTag), "tv", pg, "U"));
            return;
        }
        String url = HOST + "/tv/recommend?sort=U&start=" + (pg - 1) * COUNT + "&count=" + COUNT;
        if (!TextUtils.isEmpty(platTag)) {
            url += "&tags=" + URLEncoder.encode(platTag, "UTF-8");
        }
        JSONObject data = requestDouban(url);
        if (data != null) mergeItems(items, data.optJSONArray("items"));
    }

    // ─── Hot Variety (热播综艺) ────────────────────────────────────────────
    private static void fetchHotZy(String platTag, int pg, JSONArray items) throws Exception {
        if (TextUtils.isEmpty(platTag)) platTag = "zy_all";

        if (isKnownPlatform(platTag)) {
            mergeItems(items, PlatformFetcher.fetchPlatform(prefixToPlatform(platTag), "variety", pg, "U"));
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
                JSONArray result = PlatformFetcher.fetchPlatform(platName, "anime", pg, "U");
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
        } else if ("movie_top250".equals(slug)) {
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
            Leodanmu.log("\u8c46\u74e3\u8bf7\u6c42\u5931\u8d25: " + e.getMessage());
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
            Leodanmu.log("\u8c46\u74e3\u641c\u7d22\u8bf7\u6c42\u5931\u8d25: " + e.getMessage());
            return null;
        }
    }
}
