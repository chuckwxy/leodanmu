package com.github.catvod.spider;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.spider.entity.DanmakuItem;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

public class LeoDanmakuService {

    // 线程池
    private static final ExecutorService searchExecutor = Executors.newFixedThreadPool(4);
    // 优化防重复推送：针对每个URL记录推送时间
    private static final Map<String, Long> lastPushTimes = new ConcurrentHashMap<>();
    private static final long PUSH_MIN_INTERVAL = 3000; // 3秒内不重复推送

    // 在 LeoDanmakuService 类中添加缓存相关字段
    private static final long CACHE_EXPIRE_TIME = 30 * 60 * 1000; // 30分钟

    // 反射绑定缓存
    private static volatile ReflectionBinding reflectionBindingCache;
    private static volatile long lastReflectionResolveFailureAtMs = 0;
    private static volatile String lastReflectionResolveFailureKey = "";
    private static volatile String lastReflectionResolveFailureReason = "";
    private static final long REFLECTION_RESOLVE_FAILURE_COOLDOWN_MS = 5000;

    private static class ReflectionBinding {
        String cacheKey;
        Method setDanmakuMethod;
        Class<?> danmakuClass;
        WeakReference<Object> playerRef;
        long resolvedAtMs;

        ReflectionBinding(String cacheKey, Object player, Class<?> danmakuClass, Method setDanmakuMethod, long resolvedAtMs) {
            this.cacheKey = cacheKey;
            this.playerRef = new WeakReference<>(player);
            this.danmakuClass = danmakuClass;
            this.setDanmakuMethod = setDanmakuMethod;
            this.resolvedAtMs = resolvedAtMs;
        }
    }

    private static final AtomicInteger searchSeq = new AtomicInteger(1);

    // 新增：搜索结果封装类
    public static class SearchResult {
        public boolean found = false;
        public double similarity = 0.0;
        public DanmakuItem item = null;

        public SearchResult(boolean found, double similarity, DanmakuItem item) {
            this.found = found;
            this.similarity = similarity;
            this.item = item;
        }
    }

    // 执行搜索
    public static List<DanmakuItem> searchDanmaku(String keyword, Activity activity) {
        if (TextUtils.isEmpty(keyword)) return new ArrayList<>();

        final int searchId = searchSeq.getAndIncrement();
        final long searchStart = System.currentTimeMillis();
        final List<DanmakuItem> globalResults = Collections.synchronizedList(new ArrayList<DanmakuItem>());

        try {
            DanmakuConfig config = DanmakuConfigManager.loadConfig(activity);
            List<String> targets = new ArrayList<>(config.getApiUrls());
            // Leodanmu.log("[search#" + searchId + "] searchDanmaku start keyword=" + keyword + ", apiCount=" + targets.size());
            if (targets.isEmpty()) {
                Leodanmu.log("[search#" + searchId + "] 没有配置API地址");
                Utils.safeShowToast(activity, "没有配置API地址");
                return globalResults;
            }

            ExecutorCompletionService<List<DanmakuItem>> completionService =
                    new ExecutorCompletionService<>(searchExecutor);
            int pendingTasks = 0;

            for (final String url : targets) {
                final String apiUrl = url;
                completionService.submit(new Callable<List<DanmakuItem>>() {
                    @Override
                    public List<DanmakuItem> call() throws Exception {
                        return doSearch(apiUrl, keyword, searchId);
                    }
                });
                pendingTasks++;
                // Leodanmu.log("[search#" + searchId + "] 已提交搜索任务 api=" + apiUrl);
            }

            // 超时控制：TV 端真实请求可能超过 30 秒，外层等待窗口放宽到 50 秒，避免先于 HTTP 返回误判为空
            long endTime = System.currentTimeMillis() + 50000;

            while (pendingTasks > 0) {
                long timeLeft = endTime - System.currentTimeMillis();
                if (timeLeft <= 0) {
                    // Leodanmu.log("[search#" + searchId + "] 等待结果超时(50s), globalResults=" + globalResults.size());
                    break;
                }

                try {
                    long wait = globalResults.isEmpty() ? 8000 : 50;
                    if (wait > timeLeft) wait = timeLeft;

                    java.util.concurrent.Future<List<DanmakuItem>> future =
                            completionService.poll(wait, TimeUnit.MILLISECONDS);
                    if (future != null) {
                        List<DanmakuItem> res = future.get();
                        pendingTasks--;
                        int rawCount = res != null ? res.size() : 0;
                        // Leodanmu.log("[search#" + searchId + "] 收到任务结果 rawCount=" + rawCount + ", pending=" + pendingTasks);

                        if (res != null && !res.isEmpty()) {
                            // 过滤结果
                            java.util.Iterator<DanmakuItem> it = res.iterator();
                            int removed = 0;
                            while (it.hasNext()) {
                                DanmakuItem item = it.next();
                                if (!item.title.contains(keyword) && !keyword.contains(item.title)) {
                                    String kClean = keyword.replaceAll("\\s+", "");
                                    String tClean = item.title.replaceAll("\\s+", "");
                                    if (!tClean.contains(kClean) && !kClean.contains(tClean)) {
                                        it.remove();
                                        removed++;
                                    }
                                }
                            }

                            // Leodanmu.log("[search#" + searchId + "] 过滤完成 kept=" + res.size() + ", removed=" + removed);
                            if (!res.isEmpty()) {
                                Leodanmu.log("找到弹幕结果: " + res.size() + " 个");
                                globalResults.addAll(res);
                            }
                        }
                    } else {
                        // Leodanmu.log("[search#" + searchId + "] poll超时 wait=" + wait + "ms, currentResults=" + globalResults.size() + ", pending=" + pendingTasks);
                        if (!globalResults.isEmpty()) break;
                    }
                } catch (Exception e) {
                    pendingTasks--;
                    // Leodanmu.log("[search#" + searchId + "] 汇总任务异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Leodanmu.log("[search#" + searchId + "] 搜索异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 将List转换为ConcurrentMap
        ConcurrentHashMap<Integer, DanmakuItem> resultMap = new ConcurrentHashMap<>();
        for (DanmakuItem item : globalResults) {
            resultMap.put(item.getEpId(), item);
        }
        DanmakuManager.lastDanmakuItemMap = resultMap;
        // Leodanmu.log("[search#" + searchId + "] searchDanmaku end totalResults=" + globalResults.size() + ", cost=" + (System.currentTimeMillis() - searchStart) + "ms");

        return globalResults;
    }

    // 执行搜索
    private static List<DanmakuItem> doSearch(String apiBase, String keyword, int searchId) {
        List<DanmakuItem> list = new ArrayList<>();
        long start = System.currentTimeMillis();
        try {
            // 尝试多种API路径
            String searchUrl = apiBase + "/api/v2/search/episodes?anime=" +
                    URLEncoder.encode(keyword, "UTF-8");
            // Leodanmu.log("[search#" + searchId + "] 搜索URL: " + searchUrl);

            String json = NetworkUtils.robustHttpGet(searchUrl);
            // Leodanmu.log("[search#" + searchId + "] v2返回长度=" + (TextUtils.isEmpty(json) ? 0 : json.length()) + ", cost=" + (System.currentTimeMillis() - start) + "ms");

            // 回退到旧API
            if (TextUtils.isEmpty(json)) {
                searchUrl = apiBase + "/search/episodes?anime=" +
                        URLEncoder.encode(keyword, "UTF-8");
                // Leodanmu.log("[search#" + searchId + "] 回退搜索URL: " + searchUrl);
                long fallbackStart = System.currentTimeMillis();
                json = NetworkUtils.robustHttpGet(searchUrl);
                // Leodanmu.log("[search#" + searchId + "] fallback返回长度=" + (TextUtils.isEmpty(json) ? 0 : json.length()) + ", cost=" + (System.currentTimeMillis() - fallbackStart) + "ms");
            }

            if (TextUtils.isEmpty(json)) {
                Leodanmu.log("[search#" + searchId + "] 搜索响应为空");
                return list;
            }

            String preview = json.replace('\n', ' ').replace('\r', ' ');
            if (preview.length() > 160) preview = preview.substring(0, 160);
            // Leodanmu.log("[search#" + searchId + "] 搜索响应预览=" + preview);

            // 解析JSON
            JSONArray array = null;
            JSONObject rootOpt = null;

            if (json.trim().startsWith("[")) {
                array = new JSONArray(json);
            } else {
                rootOpt = new JSONObject(json);
                if (rootOpt.has("episodes")) array = rootOpt.optJSONArray("episodes");
                else if (rootOpt.has("animes")) array = rootOpt.optJSONArray("animes");
            }

            if (array == null) {
                Leodanmu.log("[search#" + searchId + "] 未找到episodes/animes数组");
                return list;
            }

            // Leodanmu.log("[search#" + searchId + "] JSON数组长度=" + array.length());

            // 判断数据结构
            boolean isAnimeList = false;
            if (array.length() > 0) {
                JSONObject first = array.optJSONObject(0);
                if (first != null && first.has("episodes") && !first.has("episodeId")) {
                    isAnimeList = true;
                }
                if (rootOpt != null && rootOpt.has("animes")) {
                    isAnimeList = true;
                }
            }

            if (isAnimeList) {
                // 嵌套结构
                for (int i = 0; i < array.length(); i++) {
                    JSONObject anime = array.optJSONObject(i);
                    String animeTitle = anime.optString("animeTitle");
                    if (TextUtils.isEmpty(animeTitle)) animeTitle = anime.optString("title");

                    JSONArray eps = anime.optJSONArray("episodes");
                    if (eps != null) {
                        for (int j = 0; j < eps.length(); j++) {
                            JSONObject ep = eps.optJSONObject(j);
                            processEpisode(ep, animeTitle, apiBase, list);
                        }
                    }
                }
            } else {
                // 扁平结构
                for (int i = 0; i < array.length(); i++) {
                    JSONObject ep = array.optJSONObject(i);
                    processEpisode(ep, null, apiBase, list);
                }
            }
        } catch (Exception e) {
            Leodanmu.log("[search#" + searchId + "] 搜索解析错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        // Leodanmu.log("[search#" + searchId + "] doSearch end resultCount=" + list.size() + ", cost=" + (System.currentTimeMillis() - start) + "ms");
        return list;
    }

    // 处理单集数据
    private static void processEpisode(JSONObject ep, String forcedTitle, String apiBase, List<DanmakuItem> list) {
        String animeTitle = forcedTitle;
        if (TextUtils.isEmpty(animeTitle)) animeTitle = ep.optString("animeTitle");
        if (TextUtils.isEmpty(animeTitle)) animeTitle = ep.optString("title");
        if (TextUtils.isEmpty(animeTitle)) animeTitle = ep.optString("name");

        String epTitle = ep.optString("episodeTitle");
        if (TextUtils.isEmpty(epTitle)) epTitle = ep.optString("epTitle");

        int epId = ep.optInt("episodeId", ep.optInt("epId", ep.optInt("id")));

        if (TextUtils.isEmpty(animeTitle)) {
            return;
        }

        DanmakuItem item = new DanmakuItem();
        item.title = animeTitle;
        item.epTitle = epTitle;
        item.epId = epId;
        item.apiBase = apiBase;

        String[] parts = animeTitle.split("(?i)from"); // 使用不区分大小写的正则表达式
        if (parts.length > 1) {
            String fromPart = parts[1].trim();
            if (!fromPart.isEmpty()) { // 额外检查分割后的部分是否为空
                item.from = fromPart;
                item.animeTitle = parts[0].trim();
            }
        } else {
            item.animeTitle = animeTitle;
        }

        // 清理标题
        String temp = epTitle.replace(animeTitle, "");
        temp = temp.replaceAll("【.*?】", "").replaceAll("\\[.*?\\]", "").trim();
        if (temp.startsWith("-") || temp.startsWith("_")) {
            temp = temp.substring(1).trim();
        }

        item.shortTitle = temp;
        if (TextUtils.isEmpty(item.shortTitle)) {
            item.shortTitle = epTitle;
        }

        list.add(item);
    }

    // ========== 中文数字转阿拉伯数字（复制自 DanmakuScanner） ==========
    private static String convertChineseNumberToArabic(String chineseNum) {
        Map<Character, Integer> map = new HashMap<>();
        map.put('零', 0);
        map.put('一', 1);
        map.put('二', 2);
        map.put('三', 3);
        map.put('四', 4);
        map.put('五', 5);
        map.put('六', 6);
        map.put('七', 7);
        map.put('八', 8);
        map.put('九', 9);
        map.put('十', 10);
        map.put('百', 100);
        map.put('千', 1000);
        map.put('万', 10000);

        try {
            if (chineseNum.matches("[零一二三四五六七八九十百千万]+")) {
                int result = 0;
                int temp = 0;
                int lastUnit = 1;
                for (int i = chineseNum.length() - 1; i >= 0; i--) {
                    char c = chineseNum.charAt(i);
                    if (map.containsKey(c)) {
                        int value = map.get(c);
                        if (value >= 10) {
                            if (value > lastUnit) {
                                lastUnit = value;
                                if (temp == 0) temp = 1;
                                result += temp * value;
                                temp = 0;
                            } else {
                                lastUnit = value;
                                temp = temp == 0 ? value : temp * value;
                            }
                        } else {
                            temp += value;
                        }
                    }
                }
                result += temp;
                return String.valueOf(result);
            }
        } catch (Exception e) {
            // 转换失败，尝试直接解析数字
        }
        // 尝试直接解析为数字
        try {
            return String.valueOf(Integer.parseInt(chineseNum));
        } catch (NumberFormatException e) {
            return chineseNum;
        }
    }

    // ========== 从 DanmakuItem 中提取季数 ==========
    private static String extractSeasonFromItem(DanmakuItem item) {
        String text = item.animeTitle != null ? item.animeTitle : item.title;
        if (TextUtils.isEmpty(text)) return "";

        // 匹配多种季数格式：S1, Season1, 第1季, 第一季
        Pattern[] patterns = {
                Pattern.compile("[Ss](?:eason)?\\s*(\\d{1,2})"),               // S1, Season 1
                Pattern.compile("第\\s*([0-9]{1,2})\\s*季"),                    // 第1季
                Pattern.compile("第\\s*([零一二三四五六七八九十百千万]+)\\s*季") // 第一季
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                String season = m.group(1);
                if (season.matches("[零一二三四五六七八九十百千万]+")) {
                    season = convertChineseNumberToArabic(season);
                }
                return season;
            }
        }
        return "";
    }

    private static class GroupPickResult {
        String groupKey;
        List<DanmakuItem> items;
        double groupScore;
        double itemScore;
        DanmakuItem bestItem;

        GroupPickResult(String groupKey, List<DanmakuItem> items, double groupScore, double itemScore, DanmakuItem bestItem) {
            this.groupKey = groupKey;
            this.items = items;
            this.groupScore = groupScore;
            this.itemScore = itemScore;
            this.bestItem = bestItem;
        }
    }

    // ========== 新增：返回 SearchResult 的自动搜索（供外部迭代调用） ==========
    public static SearchResult autoSearchForResult(EpisodeInfo episodeInfo, Activity activity) {
        if (episodeInfo.getEpisodeNames() == null || episodeInfo.getEpisodeNames().isEmpty()) {
            return new SearchResult(false, 0, null);
        }

        List<DanmakuItem> allResults = new ArrayList<>();
        for (String name : episodeInfo.getEpisodeNames()) {
            if (TextUtils.isEmpty(name)) continue;
            allResults.addAll(searchDanmaku(name, activity));
        }

        if (allResults.isEmpty()) return new SearchResult(false, 0, null);

        GroupPickResult groupPick = pickBestGroup(allResults, episodeInfo);
        DanmakuItem selectedItem = groupPick == null ? null : groupPick.bestItem;

        if (selectedItem != null) {
            double finalScore = groupPick.groupScore + groupPick.itemScore;
            Leodanmu.log("🎯 自动搜索选择: " + selectedItem.title + " - " + selectedItem.epTitle + " (组分: " + groupPick.groupScore + ", 组内分: " + groupPick.itemScore + ")");
            return new SearchResult(true, finalScore, selectedItem);
        }
        return new SearchResult(false, 0, null);
    }

    private static GroupPickResult pickBestGroup(List<DanmakuItem> allResults, EpisodeInfo episodeInfo) {
        Map<String, List<DanmakuItem>> grouped = new HashMap<>();
        for (DanmakuItem item : allResults) {
            String groupKey = item.getAnimeTitle() != null ? item.getAnimeTitle() : item.getTitle();
            if (TextUtils.isEmpty(groupKey)) groupKey = item.getTitle();
            if (TextUtils.isEmpty(groupKey)) groupKey = "_unknown_";
            if (!grouped.containsKey(groupKey)) grouped.put(groupKey, new ArrayList<DanmakuItem>());
            grouped.get(groupKey).add(item);
        }

        String searchKeyword = !TextUtils.isEmpty(episodeInfo.getSearchKeyword()) ? episodeInfo.getSearchKeyword() : episodeInfo.getEpisodeNames().get(0);
        String episodeTag = buildEpisodeMatchTag(episodeInfo);
        TitleMatchInfo targetInfo = TitleNormalizer.parse(searchKeyword + " " + episodeTag);

        GroupPickResult best = null;
        for (Map.Entry<String, List<DanmakuItem>> entry : grouped.entrySet()) {
            String groupKey = entry.getKey();
            TitleMatchInfo candidateInfo = TitleNormalizer.parse(groupKey);
            int structuredScore = TitleNormalizer.score(targetInfo, candidateInfo);
            int specialScore = calculateSpecialScore(episodeInfo, groupKey, groupKey);
            double legacySimilarity = calculateSimilarity(groupKey.split("【")[0].trim(), searchKeyword);
            double groupScore = structuredScore + specialScore + legacySimilarity;
            DanmakuItem bestItem = null;
            double bestItemScore = -9999;

            for (DanmakuItem item : entry.getValue()) {
                double itemScore = calculateItemScore(item, episodeInfo, searchKeyword, targetInfo);
                if (itemScore > bestItemScore) {
                    bestItemScore = itemScore;
                    bestItem = item;
                }
            }

            // Leodanmu.log("📦 标题组候选: " + groupKey + " (结构分: " + structuredScore + ", 特殊分: " + specialScore + ", 相似度: " + legacySimilarity + ", 组分: " + groupScore + ", 最佳组内分: " + bestItemScore + ")");

            if (!isGroupItemMatchAcceptable(episodeInfo, bestItemScore, bestItem)) {
                // Leodanmu.log("⚠️ 标题组淘汰：组内无有效分集匹配 - " + groupKey + (bestItem == null ? "" : "，候选=" + bestItem.getEpTitle()));
                continue;
            }

            if (best == null || (groupScore + bestItemScore) > (best.groupScore + best.itemScore)) {
                best = new GroupPickResult(groupKey, entry.getValue(), groupScore, bestItemScore, bestItem);
            }
        }
        if (best != null) Leodanmu.log("🎯 组选中: " + best.groupKey + " (组分: " + best.groupScore + ", 组内分: " + best.itemScore + ")");
        return best;
    }

    private static double calculateItemScore(DanmakuItem item, EpisodeInfo episodeInfo, String searchKeyword, TitleMatchInfo targetInfo) {
        String rawTitle = item.getAnimeTitle() != null ? item.getAnimeTitle() : item.getTitle();
        String titleToCompare = rawTitle.split("【")[0].trim();
        TitleMatchInfo candidateInfo = TitleNormalizer.parse((item.getAnimeTitle() == null ? "" : item.getAnimeTitle()) + " " + (item.getEpTitle() == null ? "" : item.getEpTitle()));
        int structuredScore = TitleNormalizer.score(targetInfo, candidateInfo);
        int episodeScore = calculateEpisodeScore(episodeInfo, item);
        int specialScore = calculateSpecialScore(episodeInfo, item.getTitle(), item.getEpTitle());
        double legacySimilarity = calculateSimilarity(titleToCompare, searchKeyword);
        double finalScore = structuredScore + episodeScore + specialScore + legacySimilarity;
        // Leodanmu.log("🤔 组内比较: " + item.getTitle() + " - " + item.getEpTitle()
        //         + " (结构分: " + structuredScore + ", 集分: " + episodeScore + ", 特殊分: " + specialScore + ", 相似度: " + legacySimilarity + ", 最终分: " + finalScore + ")");
        return finalScore;
    }

    private static boolean isGroupItemMatchAcceptable(EpisodeInfo episodeInfo, double itemScore, DanmakuItem bestItem) {
        if (bestItem == null) return false;
        if (!TextUtils.isEmpty(episodeInfo.getEpisodeNum())) {
            return calculateEpisodeScore(episodeInfo, bestItem) > 0 || itemScore >= 50;
        }
        if (!TextUtils.isEmpty(episodeInfo.getSpecialTag())) {
            return calculateSpecialScore(episodeInfo, bestItem.getTitle(), bestItem.getEpTitle()) > 0 || itemScore >= 40;
        }
        return itemScore >= 20;
    }

    private static String buildEpisodeMatchTag(EpisodeInfo episodeInfo) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(episodeInfo.getEpisodeYear())) parts.add(episodeInfo.getEpisodeYear());
        if (!TextUtils.isEmpty(episodeInfo.getEpisodeSeasonNum())) parts.add("season " + episodeInfo.getEpisodeSeasonNum());
        if (!TextUtils.isEmpty(episodeInfo.getEpisodeNum())) parts.add("ep " + episodeInfo.getEpisodeNum());
        if (!TextUtils.isEmpty(episodeInfo.getSpecialTag())) parts.add(episodeInfo.getSpecialTag());
        if (!TextUtils.isEmpty(episodeInfo.getEpisodeDateCode())) parts.add(episodeInfo.getEpisodeDateCode());
        return TextUtils.join(" ", parts);
    }

    private static int calculateEpisodeScore(EpisodeInfo episodeInfo, DanmakuItem item) {
        String episodeNum = episodeInfo.getEpisodeNum();
        if (TextUtils.isEmpty(episodeNum)) return 0;
        try {
            int epNum = Integer.parseInt(episodeNum);
            String epTitle = item.getEpTitle() == null ? "" : item.getEpTitle();
            String[] formats = {
                    String.format("第%d集", epNum),
                    String.format("_%02d", epNum),
                    String.format("_%d", epNum),
                    String.format("第%d期", epNum),
                    String.format("第%02d集", epNum),
                    String.format("第%02d期", epNum)
            };
            for (String fmt : formats) {
                if (epTitle.contains(fmt)) return 50;
            }
            return -25;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int calculateSpecialScore(EpisodeInfo episodeInfo, String title, String epTitle) {
        String allText = ((title == null ? "" : title) + " " + (epTitle == null ? "" : epTitle)).toLowerCase();
        int score = 0;
        String specialType = episodeInfo.getSpecialType();
        String specialSuffix = episodeInfo.getSpecialSuffix();
        String specialTag = episodeInfo.getSpecialTag();
        String dateCode = episodeInfo.getEpisodeDateCode();

        if (!TextUtils.isEmpty(specialTag)) {
            if (allText.contains(specialTag.toLowerCase())) score += 60;
            if (!TextUtils.isEmpty(specialType) && allText.contains(specialType.toLowerCase())) score += 25;
        }

        if (!TextUtils.isEmpty(specialSuffix)) {
            if (allText.contains(specialSuffix.toLowerCase())) score += 40;
            String opposite = "上".equals(specialSuffix) ? "下" : ("下".equals(specialSuffix) ? "上" : "");
            if (!TextUtils.isEmpty(opposite) && allText.contains(opposite)) score -= 60;
        }

        if (!TextUtils.isEmpty(dateCode)) {
            String dotted = dateCode.substring(0, 4) + "." + dateCode.substring(4, 6) + "." + dateCode.substring(6, 8);
            String dashed = dateCode.substring(0, 4) + "-" + dateCode.substring(4, 6) + "-" + dateCode.substring(6, 8);
            if (allText.contains(dateCode.toLowerCase()) || allText.contains(dotted.toLowerCase()) || allText.contains(dashed.toLowerCase())) {
                score += 20;
            }
        }
        return score;
    }

    // ========== 修改：原有 autoSearch 使用新的逻辑并保持返回 boolean ==========
    public static boolean autoSearch(EpisodeInfo episodeInfo, Activity activity) {
        if (TextUtils.isEmpty(episodeInfo.getEpisodeName())) return false;

        final boolean[] found = {false};
        final Object lock = new Object();

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                String searchKeyword;
                if (episodeInfo.isIgnoreSearchKeywordCache()) {
                    searchKeyword = !TextUtils.isEmpty(episodeInfo.getSeriesName()) ? episodeInfo.getSeriesName() : episodeInfo.getEpisodeName();
                } else {
                    searchKeyword = !TextUtils.isEmpty(episodeInfo.getSearchKeyword()) ? episodeInfo.getSearchKeyword() : episodeInfo.getEpisodeName();
                }
                sb.append("开始搜索弹幕 ").append(searchKeyword);
//                Utils.safeShowToast(activity, sb.toString());
                Leodanmu.log(sb.toString());
            }
        });

        // 60秒超时
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (!found[0]) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Leodanmu.log("自动搜索超时（60秒）");
//                        Toast.makeText(activity, "自动搜索超时（60秒）", Toast.LENGTH_SHORT).show();
                            }
                        });
                        lock.notify();
                    }
                }
            }
        }, 60000);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long autoSearchStart = System.currentTimeMillis();
                    SearchResult result = autoSearchForResult(episodeInfo, activity);
                    // Leodanmu.log("autoSearchForResult完成 found=" + result.found + ", similarity=" + result.similarity + ", cost=" + (System.currentTimeMillis() - autoSearchStart) + "ms");
                    if (result.found) {
                        Leodanmu.log("🎯 自动搜索找到结果: " + result.item);

                        // 立即记录弹幕URL（在推送前）
                        Leodanmu.recordDanmakuUrl(result.item, true);
                        DanmakuScanner.syncResolvedDanmakuState(episodeInfo, result.item);

                        found[0] = true;

                        pushDanmakuDirect(result.item, activity, true);
                    } else {
                        Leodanmu.log("自动搜索未找到任何结果");
                        // 显示提示
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Utils.safeShowToast(activity, "自动搜索未找到弹幕，请手动搜索");
                            }
                        });
                    }

                    synchronized (lock) {
                        lock.notify();
                    }
                } catch (Exception e) {
                    Leodanmu.log("自动搜索异常: " + e.getMessage());
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            }
        }).start();

        // 等待结果
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return found[0];
    }

    // ========== 修改：手动搜索不再使用 DanmakuUtils.extractTitle，直接使用原词 ==========
    public static List<DanmakuItem> manualSearch(String keyword, Activity activity) {
        List<DanmakuItem> results = new ArrayList<>();

        if (TextUtils.isEmpty(keyword)) return results;

        try {
            // 直接使用原词搜索，不再清理
            results = searchDanmaku(keyword, activity);
        } catch (Exception e) {
            Leodanmu.log("手动搜索失败: " + e.getMessage());
        }

        return results;
    }

    // ========== 修改：直接推送弹幕URL，使用动态端口和防重复推送 ==========
    public static void pushDanmakuDirect(DanmakuItem danmakuItem, Activity activity, boolean isAuto) {
        pushDanmakuDirect(danmakuItem, activity, isAuto, false, false);
    }

    public static void pushDanmakuDirect(DanmakuItem danmakuItem, Activity activity, boolean isAuto, boolean fastPushThenVerify) {
        pushDanmakuDirect(danmakuItem, activity, isAuto, fastPushThenVerify, false);
    }

    public static void pushDanmakuDirect(DanmakuItem danmakuItem, Activity activity, boolean isAuto, boolean fastPushThenVerify, boolean forceRefresh) {
        String danmakuUrl = danmakuItem.getDanmakuUrl();
        if (TextUtils.isEmpty(danmakuUrl)) {
            Leodanmu.log("⚠️ 推送弹幕URL为空，跳过");
            return;
        }

        DanmakuConfig config = activity != null ? DanmakuConfigManager.getConfig(activity) : null;
        int offsetMs = config != null ? config.getDanmakuTimeOffsetMs() : 0;
        String pushKey = danmakuUrl + "#offset=" + offsetMs;

        long currentTime = System.currentTimeMillis();
        Long lastPush = lastPushTimes.get(pushKey);
        if (!forceRefresh && lastPush != null && (currentTime - lastPush < PUSH_MIN_INTERVAL)) {
            Leodanmu.log("⚠️ 推送过于频繁 (同一URL和偏移)，跳过: " + pushKey);
            return;
        }

        lastPushTimes.put(pushKey, currentTime);
        cleanupOldPushTimes(currentTime);
        Leodanmu.recordDanmakuUrl(danmakuItem, isAuto);
        if (TextUtils.isEmpty(DanmakuScanner.currentSeriesName)
                || TextUtils.isEmpty(DanmakuScanner.currentEpisodeNum)) {
            DanmakuScanner.syncResolvedDanmakuState(null, danmakuItem);
        }

        boolean isMainThread = Looper.myLooper() == Looper.getMainLooper();
        if (isMainThread) {
            Leodanmu.log("警告：推送弹幕在主线程调用，切换到子线程");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    pushDanmakuInThread(danmakuItem, activity, fastPushThenVerify, isAuto);
                }
            }).start();
        } else {
            Leodanmu.log("已经在子线程，直接执行弹幕推送");
            pushDanmakuInThread(danmakuItem, activity, fastPushThenVerify, isAuto);
        }
    }

    private static void cleanupOldPushTimes(long currentTime) {
        long cleanupThreshold = 5 * 60 * 1000;
        Iterator<Map.Entry<String, Long>> iterator = lastPushTimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (currentTime - entry.getValue() > cleanupThreshold) {
                iterator.remove();
            }
        }
    }

    private static void pushDanmakuInThread(DanmakuItem danmakuItem, Activity activity, boolean fastPushThenVerify) {
        pushDanmakuInThread(danmakuItem, activity, fastPushThenVerify, true);
    }

    private static void pushDanmakuInThread(DanmakuItem danmakuItem, Activity activity, boolean fastPushThenVerify, boolean isAuto) {
        try {
            if (TextUtils.isEmpty(danmakuItem.getDanmakuUrl())) {
                Leodanmu.log("推送弹幕URL为空");
                return;
            }

            if (fastPushThenVerify) {
                Leodanmu.log("⚡ 命中已验证链路，先推送后异步校验: " + danmakuItem.getEpTitle());
                String localIp = NetworkUtils.getLocalIpAddress();
                DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                int offsetMs = config != null ? config.getDanmakuTimeOffsetMs() : 0;
                String refreshPath = buildDanmakuRefreshPath(danmakuItem, localIp, offsetMs);
                Leodanmu.log("📡 推送代理URL: " + refreshPath);
                if (offsetMs != 0) {
                    Leodanmu.log("启用弹幕时间偏移: " + DanmakuUtils.formatOffsetLabel(offsetMs) + "，通过本地代理推送");
                }
                String pushResult = null;
                boolean reflectionPushed = activity != null && tryPushDanmakuByReflection(danmakuItem, activity, refreshPath);
                if (reflectionPushed) {
                    pushResult = "OK";
                    Leodanmu.log("✅ 快速推送已通过反射发送: " + buildDanmakuDisplayName(danmakuItem));
                } else {
                    Leodanmu.log("反射推送不可用，快速推送回退到HTTP");
                    String pushUrl = "http://" + localIp + ":" + Utils.getPort() + "/action?do=refresh&type=danmaku&path=" +
                            URLEncoder.encode(refreshPath, "UTF-8");
                    for (int i = 0; i < 3; i++) {
                        pushResult = NetworkUtils.robustHttpGet(pushUrl);
                        Leodanmu.log("快速推送尝试 " + (i + 1) + "/3: " + (!TextUtils.isEmpty(pushResult) ? "成功" : "失败"));
                        if (!TextUtils.isEmpty(pushResult) && pushResult.toLowerCase().contains("ok")) {
                            Leodanmu.log("✅ 快速推送已发送");
                            break;
                        }
                        if (i < 2) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
                if (!TextUtils.isEmpty(pushResult) && pushResult.toLowerCase().contains("ok")) {
                    verifyDanmakuAfterPushAsync(danmakuItem, activity);
                } else {
                    Leodanmu.log("❌ 快速推送失败，响应: " + pushResult);
                }
                return;
            }

            int danmakuCount = fetchValidDanmakuCount(danmakuItem, 3);
            if (danmakuCount <= 0) {
                Leodanmu.log("❌ 无法获取有效的弹幕数据（或弹幕为空），取消推送");
                return;
            }

            String pushResp;
            String localIp = NetworkUtils.getLocalIpAddress();
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            int offsetMs = config != null ? config.getDanmakuTimeOffsetMs() : 0;
            String refreshPath = buildDanmakuRefreshPath(danmakuItem, localIp, offsetMs);
            Leodanmu.log("📡 推送代理URL: " + refreshPath);

            if (offsetMs != 0) {
                Leodanmu.log("启用弹幕时间偏移: " + DanmakuUtils.formatOffsetLabel(offsetMs) + "，通过本地代理推送");
            }

            boolean reflectionPushed = activity != null && tryPushDanmakuByReflection(danmakuItem, activity, refreshPath);
            if (reflectionPushed) {
                pushResp = "OK";
                Leodanmu.log("✅ 已通过反射方式推送弹幕: " + buildDanmakuDisplayName(danmakuItem));
            } else {
                String pushUrl = "http://" + localIp + ":" + Utils.getPort() + "/action?do=refresh&type=danmaku&path=" +
                        URLEncoder.encode(refreshPath, "UTF-8");
                Leodanmu.log("反射推送不可用，回退到HTTP推送: " + pushUrl);
                pushResp = "";
                for (int i = 0; i < 3; i++) {
                    pushResp = NetworkUtils.robustHttpGet(pushUrl);
                    Leodanmu.log("推送尝试 " + (i + 1) + "/3: " + (!TextUtils.isEmpty(pushResp) ? "成功" : "失败"));
                    if (!TextUtils.isEmpty(pushResp) && pushResp.toLowerCase().contains("ok")) {
                        Leodanmu.log("✅ 推送成功");
                        break;
                    }
                    if (i < 2) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            notifyPushResult(activity, danmakuItem, danmakuCount, pushResp);
        } catch (Exception e) {
            Leodanmu.log("推送异常: " + e.getMessage());
        }
    }

    private static int fetchValidDanmakuCount(DanmakuItem danmakuItem, int maxRetries) {
        Leodanmu.apiUrl = danmakuItem.getApiBase();
        String danmakuData;
        int danmakuCount;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                Leodanmu.log("开始获取弹幕数据 (尝试 " + (attempt + 1) + "/" + maxRetries + ") - URL: " + danmakuItem.getDanmakuUrl());
                danmakuData = NetworkUtils.robustHttpGet(danmakuItem.getDanmakuUrl());
                danmakuCount = countDanmakuItems(danmakuData);
                if (danmakuCount > 0) {
                    Leodanmu.log("✅ 获取到有效弹幕数据，总数: " + danmakuCount + " 条");
                    return danmakuCount;
                } else if (danmakuCount == 0) {
                    Leodanmu.log("⚠️ 弹幕数据为空或无内容，尝试次数: " + (attempt + 1) + "/" + maxRetries);
                } else {
                    Leodanmu.log("⚠️ 弹幕数据格式错误或解析失败，尝试次数: " + (attempt + 1) + "/" + maxRetries);
                }
            } catch (Exception e) {
                Leodanmu.log("获取弹幕数据异常 (尝试 " + (attempt + 1) + "/" + maxRetries + "): " + e.getMessage());
            }

            if (attempt < maxRetries - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return 0;
    }

    private static String pushDanmakuToPlayer(DanmakuItem danmakuItem) throws Exception {
        String localIp = NetworkUtils.getLocalIpAddress();
        String pushUrl = "http://" + localIp + ":" + Utils.getPort() + "/action?do=refresh&type=danmaku&path=" +
                URLEncoder.encode(danmakuItem.getDanmakuUrl(), "UTF-8");
        Leodanmu.log("推送地址: " + pushUrl);

        String pushResp = "";
        for (int i = 0; i < 3; i++) {
            pushResp = NetworkUtils.robustHttpGet(pushUrl);
            Leodanmu.log("推送尝试 " + (i + 1) + "/3: " + (!TextUtils.isEmpty(pushResp) ? "成功" : "失败"));
            if (!TextUtils.isEmpty(pushResp) && pushResp.toLowerCase().contains("ok")) {
                Leodanmu.log("✅ 推送成功");
                break;
            }
            if (i < 2) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return pushResp;
    }

    private static void verifyDanmakuAfterPushAsync(final DanmakuItem danmakuItem, final Activity activity) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int danmakuCount = fetchValidDanmakuCount(danmakuItem, 3);
                if (danmakuCount <= 0) {
                    Leodanmu.log("⚠️ 异步校验失败：弹幕为空或无效 - " + danmakuItem.getDanmakuUrl());
                    return;
                }
                if (!TextUtils.equals(DanmakuManager.lastDanmakuUrl, danmakuItem.getDanmakuUrl())) {
                    Leodanmu.log("ℹ️ 异步校验完成，但当前弹幕已切换，跳过旧提示: " + danmakuItem.getEpTitle());
                    return;
                }
                notifyPushResult(activity, danmakuItem, danmakuCount, "ok-async");
            }
        }).start();
    }

    private static void notifyPushResult(Activity activity, DanmakuItem danmakuItem, int danmakuCount, String pushResp) {
        final int finalDanmakuCount = danmakuCount;
        final String finalPushResp = pushResp;
        if (activity != null && !activity.isFinishing()) {
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            final boolean showToast = config.isPushToastEnabled();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (activity == null || activity.isFinishing()) return;
                    if (showToast) {
                        if (!TextUtils.isEmpty(finalPushResp) && finalPushResp.toLowerCase().contains("ok")) {
                            String message = String.format("弹幕已推送: %s - %s (共%d条)",
                                    danmakuItem.getTitle(),
                                    danmakuItem.getEpTitle(),
                                    finalDanmakuCount);
                            Utils.safeShowToast(activity, message);
                            Leodanmu.log(message);
                        } else {
                            Utils.safeShowToast(activity, "推送失败: 无响应或响应异常");
                            Leodanmu.log("❌ 推送失败，响应: " + finalPushResp);
                        }
                    } else {
                        if (!TextUtils.isEmpty(finalPushResp) && finalPushResp.toLowerCase().contains("ok")) {
                            Leodanmu.log(String.format("弹幕已推送: %s - %s (共%d条)",
                                    danmakuItem.getTitle(),
                                    danmakuItem.getEpTitle(),
                                    finalDanmakuCount));
                        } else {
                            Leodanmu.log("❌ 推送失败，响应: " + finalPushResp);
                        }
                    }
                }
            });
        }
    }

    // ========== 构建弹幕刷新路径（支持时间偏移本地代理）==========
    private static String buildDanmakuRefreshPath(DanmakuItem danmakuItem, String localIp, int offsetMs) throws Exception {
        String rawUrl = danmakuItem.getDanmakuUrl();
        if (offsetMs == 0) return rawUrl;
        return "http://" + localIp + ":" + getWebServerPort() + "/danmaku?url=" +
                URLEncoder.encode(rawUrl, "UTF-8") +
                "&t=" + System.currentTimeMillis();
    }

    private static int getWebServerPort() {
        return 9888;
    }

    // ========== 新增：反射推送弹幕（参考 CatVodSpider）==========
    private static boolean tryPushDanmakuByReflection(final DanmakuItem danmakuItem, final Activity activity, final String danmakuPath) {
        if (activity == null || TextUtils.isEmpty(danmakuPath)) return false;

        final ReflectionBinding binding = resolveReflectionBinding(activity);
        if (binding == null) return false;

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = new boolean[]{false};
        final String[] error = new String[]{null};

        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    Object player = binding.playerRef.get();
                    if (player == null) {
                        clearReflectionBindingCache();
                        error[0] = "宿主播放器缓存已失效";
                        return;
                    }
                    Object danmaku = createFongMiDanmaku(activity, danmakuItem, danmakuPath, binding.danmakuClass);
                    if (danmaku == null) {
                        error[0] = "未能构造宿主弹幕对象";
                        return;
                    }
                    binding.setDanmakuMethod.setAccessible(true);
                    binding.setDanmakuMethod.invoke(player, danmaku);
                    success[0] = true;
                } catch (Throwable e) {
                    clearReflectionBindingCache();
                    error[0] = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    latch.countDown();
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            activity.runOnUiThread(task);
            try {
                latch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error[0] = "等待主线程反射推送被中断";
            }
        }

        if (success[0]) {
            return true;
        }

        if (!TextUtils.isEmpty(error[0])) {
            Leodanmu.log("反射推送失败: " + error[0]);
        }
        return false;
    }

    public static boolean canPushDanmakuByReflection(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        try {
            return resolveReflectionBinding(activity) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ReflectionBinding resolveReflectionBinding(Activity activity) {
        if (activity == null || activity.isFinishing()) return null;
        ReflectionBinding cached = getCachedReflectionBinding(activity);
        if (cached != null) {
            return cached;
        }

        String cacheKey = buildReflectionCacheKey(activity);
        long now = System.currentTimeMillis();
        if (cacheKey.equals(lastReflectionResolveFailureKey)
                && now - lastReflectionResolveFailureAtMs < REFLECTION_RESOLVE_FAILURE_COOLDOWN_MS) {
            return null;
        }

        try {
            Class<?> danmakuClass = resolveHostDanmakuClass(activity);
            if (danmakuClass == null) {
                markReflectionResolveFailure(cacheKey, "未找到宿主Danmaku类");
                return null;
            }
            Object player = resolveFongMiPlayer(activity, danmakuClass);
            if (player == null) {
                markReflectionResolveFailure(cacheKey, "未找到宿主播放器实例");
                return null;
            }
            Method setDanmakuMethod = findDanmakuMethod(player.getClass(), danmakuClass);
            if (setDanmakuMethod == null) {
                markReflectionResolveFailure(cacheKey, "宿主目标缺少setDanmaku/o方法: " + player.getClass().getName());
                return null;
            }
            setDanmakuMethod.setAccessible(true);
            ReflectionBinding binding = new ReflectionBinding(cacheKey, player, danmakuClass, setDanmakuMethod, now);
            reflectionBindingCache = binding;
            lastReflectionResolveFailureAtMs = 0;
            lastReflectionResolveFailureKey = "";
            lastReflectionResolveFailureReason = "";
            return binding;
        } catch (Throwable e) {
            markReflectionResolveFailure(cacheKey, e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static ReflectionBinding getCachedReflectionBinding(Activity activity) {
        ReflectionBinding binding = reflectionBindingCache;
        if (binding == null || activity == null) return null;
        if (!buildReflectionCacheKey(activity).equals(binding.cacheKey)) return null;
        if (binding.danmakuClass == null || binding.setDanmakuMethod == null) return null;
        Object player = binding.playerRef != null ? binding.playerRef.get() : null;
        if (player == null) return null;
        if (!binding.setDanmakuMethod.getDeclaringClass().isAssignableFrom(player.getClass())) return null;
        return binding;
    }

    private static void clearReflectionBindingCache() {
        reflectionBindingCache = null;
    }

    private static void markReflectionResolveFailure(String cacheKey, String reason) {
        clearReflectionBindingCache();
        lastReflectionResolveFailureKey = cacheKey == null ? "" : cacheKey;
        lastReflectionResolveFailureAtMs = System.currentTimeMillis();
        lastReflectionResolveFailureReason = reason == null ? "" : reason;
    }

    private static String buildReflectionCacheKey(Activity activity) {
        if (activity == null) return "";
        String packageName = "";
        Package pkg = activity.getClass().getPackage();
        if (pkg != null && pkg.getName() != null) packageName = pkg.getName();
        return activity.getClass().getName() + "#" + packageName;
    }

    private static Object resolveFongMiPlayer(Activity activity, Class<?> danmakuClass) throws Exception {
        Object player = tryResolveExactFongMiDanmakuTarget(activity, danmakuClass);
        if (player != null) return player;

        Activity topActivity = Utils.getTopActivity();
        if (topActivity != null && topActivity != activity) {
            player = tryResolveExactFongMiDanmakuTarget(topActivity, danmakuClass);
            if (player != null) return player;
        }

        player = tryResolveKnownHostControllerTarget(activity, danmakuClass);
        if (player != null) return player;
        if (topActivity != null && topActivity != activity) {
            player = tryResolveKnownHostControllerTarget(topActivity, danmakuClass);
            if (player != null) return player;
        }

        player = tryResolveDanmakuTargetFromActivityDirect(activity, danmakuClass);
        if (player != null) return player;
        if (topActivity != null && topActivity != activity) {
            player = tryResolveDanmakuTargetFromActivityDirect(topActivity, danmakuClass);
            if (player != null) return player;
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        player = tryResolveDanmakuTargetFromObject(activity, "activity-root", 5, visited, true, danmakuClass);
        if (player != null) return player;
        if (topActivity != null && topActivity != activity) {
            player = tryResolveDanmakuTargetFromObject(topActivity, "top-activity-root", 5, visited, true, danmakuClass);
            if (player != null) return player;
        }

        player = tryResolvePlayerFromActivity(activity, danmakuClass);
        if (player != null) return player;

        if (topActivity != null && topActivity != activity) {
            player = tryResolvePlayerFromActivity(topActivity, danmakuClass);
            if (player != null) return player;
        }

        player = tryResolvePlayerFromServer(activity, danmakuClass);
        if (player != null) return player;

        player = tryResolvePlayerFromActivityFields(activity, danmakuClass);
        if (player != null) return player;

        if (topActivity != null && topActivity != activity) {
            player = tryResolvePlayerFromActivityFields(topActivity, danmakuClass);
            if (player != null) return player;
        }

        for (Activity candidate : getAliveActivities()) {
            if (candidate == null || candidate == activity || candidate == topActivity) continue;
            player = tryResolveExactFongMiDanmakuTarget(candidate, danmakuClass);
            if (player != null) return player;
            player = tryResolveDanmakuTargetFromActivityDirect(candidate, danmakuClass);
            if (player != null) return player;
            player = tryResolveDanmakuTargetFromObject(candidate, "candidate-root", 4, visited, false, danmakuClass);
            if (player != null) return player;
            player = tryResolvePlayerFromActivity(candidate, danmakuClass);
            if (player != null) return player;
            player = tryResolvePlayerFromActivityFields(candidate, danmakuClass);
            if (player != null) return player;
        }

        return null;
    }

    private static Object tryResolveExactFongMiDanmakuTarget(Activity activity, Class<?> danmakuClass) {
        if (activity == null) return null;
        try {
            if (!"com.fongmi.android.tv.ui.activity.VideoActivity".equals(activity.getClass().getName())) {
                return null;
            }
            Field serviceField = findField(activity.getClass(), "F");
            if (serviceField == null) {
                Leodanmu.log("精确链路失败: VideoActivity 缺少字段 F");
                return null;
            }
            serviceField.setAccessible(true);
            Object service = serviceField.get(activity);
            if (service == null) {
                Leodanmu.log("精确链路失败: VideoActivity.F 为空");
                return null;
            }
            if (!"com.fongmi.android.tv.service.PlaybackService".equals(service.getClass().getName())) {
                Leodanmu.log("精确链路失败: VideoActivity.F 类型异常 -> " + service.getClass().getName());
                logObjectShape("VideoActivity.F", service, danmakuClass, 2);
                return null;
            }
            Field danmakuField = findField(service.getClass(), "u");
            if (danmakuField == null) {
                Leodanmu.log("精确链路失败: PlaybackService 缺少字段 u");
                logObjectShape("PlaybackService", service, danmakuClass, 2);
                return null;
            }
            danmakuField.setAccessible(true);
            Object target = danmakuField.get(service);
            if (target == null) {
                Leodanmu.log("精确链路失败: PlaybackService.u 为空");
                logObjectShape("PlaybackService", service, danmakuClass, 2);
                return null;
            }
            if (findDanmakuMethod(target.getClass(), danmakuClass) == null) {
                Leodanmu.log("精确链路失败: PlaybackService.u 不含 Danmaku 方法 -> " + target.getClass().getName());
                logObjectShape("PlaybackService.u", target, danmakuClass, 2);
                return null;
            }
            Leodanmu.log("精确链路命中: VideoActivity.F.u -> " + target.getClass().getName());
            return target;
        } catch (Throwable e) {
            Leodanmu.log("精确链路反射失败: " + e.getMessage());
            return null;
        }
    }

    private static Object tryResolveKnownHostControllerTarget(Activity activity, Class<?> danmakuClass) {
        if (activity == null) return null;

        Object target = tryResolveKnownControllerType(activity, danmakuClass,
                "F3.f",
                "com.fongmi.android.tv.ui.activity.VideoActivity",
                "k0",
                "OK影视Mobile/F3.f.k0");
        if (target != null) return target;

        target = tryResolveKnownControllerType(activity, danmakuClass,
                "C3.e",
                "com.fongmi.android.tv.ui.activity.VideoActivity",
                "i0",
                "OK影视TV/C3.e.i0");
        if (target != null) return target;

        target = tryResolveKnownControllerField(activity, danmakuClass, "Z", "l0", "影视+/Z.l0");
        if (target != null) return target;

        target = tryResolveKnownControllerByMethodSignatures(activity, danmakuClass);
        if (target != null) return target;

        target = tryResolveKnownPlaybackServiceStaticByMethodSignatures(activity, danmakuClass);
        if (target != null) return target;

        return null;
    }

    private static Object tryResolveKnownControllerField(Activity activity, Class<?> danmakuClass, String fieldName, String methodName, String label) {
        try {
            Field field = findField(activity.getClass(), fieldName);
            if (field == null) {
                Leodanmu.log("宿主链路未命中: " + label + " 缺少字段 " + fieldName);
                return null;
            }
            field.setAccessible(true);
            Object controller = field.get(activity);
            if (controller == null) {
                Leodanmu.log("宿主链路未命中: " + label + " 字段为空");
                return null;
            }
            Method method = findCompatibleMethod(controller.getClass(), methodName, danmakuClass);
            if (method == null) {
                Leodanmu.log("宿主链路未命中: " + label + " 缺少方法 " + methodName + "(" + danmakuClass.getSimpleName() + ")");
                return null;
            }
            Leodanmu.log("宿主链路命中: " + label + " -> " + controller.getClass().getName() + "#" + method.getName());
            return controller;
        } catch (Throwable e) {
            Leodanmu.log("宿主链路异常: " + label + " - " + e.getMessage());
            return null;
        }
    }

    private static Object tryResolveKnownControllerType(Activity activity,
                                                        Class<?> danmakuClass,
                                                        String simpleTypeName,
                                                        String ownerClassName,
                                                        String methodName,
                                                        String label) {
        try {
            if (!ownerClassName.equals(activity.getClass().getName())) {
                return null;
            }
            Class<?> current = activity.getClass();
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    Class<?> fieldType = field.getType();
                    if (fieldType == null) continue;
                    if (!simpleTypeName.equals(fieldType.getName()) && !simpleTypeName.equals(fieldType.getSimpleName())) continue;
                    field.setAccessible(true);
                    Object controller = field.get(activity);
                    if (controller == null) {
                        Leodanmu.log("宿主链路未命中: " + label + " 字段为空(" + current.getName() + "#" + field.getName() + ")");
                        return null;
                    }
                    Method method = findCompatibleMethod(controller.getClass(), methodName, danmakuClass);
                    if (method == null) {
                        Leodanmu.log("宿主链路未命中: " + label + " 缺少方法 " + methodName + "(" + danmakuClass.getSimpleName() + ")");
                        return null;
                    }
                    Leodanmu.log("宿主链路命中: " + label + " -> " + current.getName() + "#" + field.getName() + " / " + controller.getClass().getName() + "#" + method.getName());
                    return controller;
                }
                current = current.getSuperclass();
            }
            Leodanmu.log("宿主链路未命中: " + label + " 缺少类型字段 " + simpleTypeName);
            return null;
        } catch (Throwable e) {
            Leodanmu.log("宿主链路异常: " + label + " - " + e.getMessage());
            return null;
        }
    }

    private static Object tryResolveKnownControllerByMethodSignatures(Activity activity, Class<?> danmakuClass) {
        try {
            String[] methodNames = new String[]{"k0", "i0", "l0", "o"};
            Class<?> current = activity.getClass();
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(activity);
                    if (value == null) continue;
                    Class<?> valueClass = value.getClass();
                    if (isSimpleValueType(valueClass) || isViewOnlyDanmakuTarget(valueClass) || isWrapperOrAsyncTarget(valueClass)) continue;
                    for (String methodName : methodNames) {
                        Method method = findCompatibleMethod(valueClass, methodName, danmakuClass);
                        if (method == null) continue;
                        Leodanmu.log("宿主链路命中: 方法签名字段 -> " + current.getName() + "#" + field.getName() + " / " + valueClass.getName() + "#" + method.getName());
                        return value;
                    }
                }
                current = current.getSuperclass();
            }
            Leodanmu.log("宿主链路未命中: 方法签名字段扫描未找到 k0/i0/l0/o(" + danmakuClass.getSimpleName() + ")");
            return null;
        } catch (Throwable e) {
            Leodanmu.log("宿主链路异常: 方法签名字段扫描 - " + e.getMessage());
            return null;
        }
    }

    private static Object tryResolveKnownPlaybackServiceStaticByMethodSignatures(Activity activity, Class<?> danmakuClass) {
        try {
            ClassLoader loader = activity.getClassLoader() != null ? activity.getClassLoader() : LeoDanmakuService.class.getClassLoader();
            Class<?> serviceClass = Class.forName("com.fongmi.android.tv.service.PlaybackService", false, loader);
            String[] methodNames = new String[]{"k0", "i0", "l0", "o"};
            Class<?> current = serviceClass;
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) continue;
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value == null) continue;
                    Class<?> valueClass = value.getClass();
                    if (isSimpleValueType(valueClass) || isViewOnlyDanmakuTarget(valueClass) || isWrapperOrAsyncTarget(valueClass)) continue;
                    for (String methodName : methodNames) {
                        Method method = findCompatibleMethod(valueClass, methodName, danmakuClass);
                        if (method == null) continue;
                        Leodanmu.log("宿主链路命中: PlaybackService静态控制器 -> " + current.getName() + "#" + field.getName() + " / " + valueClass.getName() + "#" + method.getName());
                        return value;
                    }
                }
                current = current.getSuperclass();
            }
            Leodanmu.log("宿主链路未命中: PlaybackService静态控制器扫描未找到 k0/i0/l0/o(" + danmakuClass.getSimpleName() + ")");
            return null;
        } catch (Throwable e) {
            Leodanmu.log("宿主链路异常: PlaybackService静态控制器扫描 - " + e.getMessage());
            return null;
        }
    }

    private static Class<?> resolveHostDanmakuClass(Activity activity) throws Exception {
        ClassLoader loader = activity.getClassLoader() != null ? activity.getClassLoader() : LeoDanmakuService.class.getClassLoader();
        return findHostClass(loader, activity, "bean.Danmaku");
    }

    private static Object createFongMiDanmaku(Activity activity, DanmakuItem danmakuItem, String danmakuPath, Class<?> danmakuClass) throws Exception {
        if (danmakuClass == null) return null;
        Constructor<?> constructor = danmakuClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object danmaku = constructor.newInstance();

        Method setName = findMethod(danmakuClass, "setName", String.class);
        Method setUrl = findMethod(danmakuClass, "setUrl", String.class);
        Method setSelected = findMethod(danmakuClass, "setSelected", boolean.class);
        if (setUrl == null) return null;

        if (setName != null) {
            setName.setAccessible(true);
            setName.invoke(danmaku, buildDanmakuDisplayName(danmakuItem));
        }
        setUrl.setAccessible(true);
        setUrl.invoke(danmaku, danmakuPath);
        if (setSelected != null) {
            setSelected.setAccessible(true);
            setSelected.invoke(danmaku, true);
        }
        return danmaku;
    }

    private static String buildDanmakuDisplayName(DanmakuItem danmakuItem) {
        if (danmakuItem == null) return "弹幕";
        String title = danmakuItem.getTitleWithEp();
        String source = danmakuItem.getFrom();
        if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(source)) return title + " · " + source;
        if (!TextUtils.isEmpty(title)) return title;
        if (!TextUtils.isEmpty(source)) return source;
        if (!TextUtils.isEmpty(danmakuItem.getEpTitle())) return danmakuItem.getEpTitle();
        if (!TextUtils.isEmpty(danmakuItem.getTitle())) return danmakuItem.getTitle();
        return danmakuItem.getDanmakuUrl();
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?> parameterType) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getName().equals(name) &&
                        params.length == 1 &&
                        params[0].isAssignableFrom(parameterType)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object tryResolvePlayerFromActivity(Activity activity, Class<?> danmakuClass) {
        if (activity == null) return null;
        try {
            Method playerMethod = findMethod(activity.getClass(), "player");
            if (playerMethod != null) {
                playerMethod.setAccessible(true);
                Object player = playerMethod.invoke(activity);
                if (player != null) {
                    Object resolved = normalizeResolvedPlayerTarget(player, "activity.player()", danmakuClass, true);
                    if (resolved != null) return resolved;
                }
                Leodanmu.log("activity.player() 返回空: " + activity.getClass().getName());
            }
        } catch (Throwable e) {
            Leodanmu.log("activity.player() 反射失败: " + e.getMessage());
        }

        try {
            Method serviceMethod = findMethod(activity.getClass(), "service");
            if (serviceMethod != null) {
                serviceMethod.setAccessible(true);
                Object service = serviceMethod.invoke(activity);
                if (service == null) {
                    Leodanmu.log("activity.service() 返回空: " + activity.getClass().getName());
                } else {
                    Object player = tryResolvePlayerFromService(service, true, danmakuClass);
                    if (player != null) {
                        Leodanmu.log("反射定位播放器成功: activity.service().player() -> " + activity.getClass().getName());
                        return player;
                    }
                }
            }
        } catch (Throwable e) {
            Leodanmu.log("activity.service() 反射失败: " + e.getMessage());
        }

        try {
            Field serviceField = findField(activity.getClass(), "mService");
            if (serviceField != null) {
                serviceField.setAccessible(true);
                Object service = serviceField.get(activity);
                if (service == null) {
                    Leodanmu.log("activity.mService 返回空: " + activity.getClass().getName());
                } else {
                    Object player = tryResolvePlayerFromService(service, true, danmakuClass);
                    if (player != null) {
                        Leodanmu.log("反射定位播放器成功: activity.mService.player() -> " + activity.getClass().getName());
                        return player;
                    }
                }
            }
        } catch (Throwable e) {
            Leodanmu.log("activity.mService 反射失败: " + e.getMessage());
        }

        return null;
    }

    private static Object tryResolvePlayerFromServer(Activity activity, Class<?> danmakuClass) {
        try {
            ClassLoader loader = activity != null && activity.getClassLoader() != null ? activity.getClassLoader() : LeoDanmakuService.class.getClassLoader();
            Class<?> serverClass = findHostClass(loader, activity, "server.Server");
            if (serverClass == null) return null;
            Method getMethod = findMethod(serverClass, "get");
            Method getServiceMethod = findMethod(serverClass, "getService");
            if (getMethod == null || getServiceMethod == null) return null;
            getMethod.setAccessible(true);
            Object server = getMethod.invoke(null);
            if (server == null) return null;
            getServiceMethod.setAccessible(true);
            Object service = getServiceMethod.invoke(server);
            if (service == null) {
                Leodanmu.log("Server.get().getService() 返回空");
                return null;
            }
            Object player = tryResolvePlayerFromService(service, true, danmakuClass);
            if (player != null) {
                Leodanmu.log("反射定位播放器成功: Server.get().getService().player()");
                return player;
            }
        } catch (Throwable e) {
            Leodanmu.log("Server.get().getService() 反射失败: " + e.getMessage());
        }
        return null;
    }

    private static Object tryResolvePlayerFromService(Object service, boolean verbose, Class<?> danmakuClass) {
        if (service == null) return null;
        try {
            Object directTarget = tryResolveDanmakuTargetFromPlaybackServiceDirect(service, verbose, danmakuClass);
            if (directTarget != null) return directTarget;

            Object directCandidate = tryResolveDanmakuTargetFromObject(service, service.getClass().getName(), 2,
                    Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), false, danmakuClass);
            if (directCandidate != null) {
                if (verbose) Leodanmu.log("反射定位弹幕目标成功: service-root-scan -> " + directCandidate.getClass().getName());
                return directCandidate;
            }

            Method playerMethod = findMethod(service.getClass(), "player");
            if (playerMethod == null) {
                if (verbose) Leodanmu.log("service.player() 方法不存在: " + service.getClass().getName());
            } else {
                playerMethod.setAccessible(true);
                Object player = playerMethod.invoke(service);
                if (player == null) {
                    if (verbose) Leodanmu.log("service.player() 返回空: " + service.getClass().getName());
                } else {
                    Object resolved = normalizeResolvedPlayerTarget(player, "service.player()", danmakuClass, verbose);
                    if (resolved != null) return resolved;
                }
            }

            Field playerField = findField(service.getClass(), "player");
            if (playerField == null) playerField = findField(service.getClass(), "mPlayer");
            if (playerField != null) {
                playerField.setAccessible(true);
                Object player = playerField.get(service);
                if (player == null) {
                    if (verbose) Leodanmu.log("service.player 字段返回空: " + service.getClass().getName());
                } else {
                    Object resolved = normalizeResolvedPlayerTarget(player, "service.player field", danmakuClass, verbose);
                    if (resolved != null) return resolved;
                }
            } else if (verbose) {
                Leodanmu.log("service.player 字段不存在: " + service.getClass().getName());
            }

            Object candidate = tryResolveDanmakuTargetFromFields(service, service.getClass().getName(), 4, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), verbose, danmakuClass);
            if (candidate != null) return candidate;
        } catch (Throwable e) {
            if (verbose) Leodanmu.log("service.player/service字段 反射失败: " + e.getMessage());
        }
        return null;
    }

    private static Object tryResolveDanmakuTargetFromActivityDirect(Activity activity, Class<?> danmakuClass) {
        if (activity == null) return null;
        try {
            Field serviceField = findField(activity.getClass(), "F");
            if (serviceField == null) return null;
            serviceField.setAccessible(true);
            Object service = serviceField.get(activity);
            if (service == null) {
                Leodanmu.log("activity.F 返回空: " + activity.getClass().getName());
                return null;
            }
            Object target = tryResolveDanmakuTargetFromPlaybackServiceDirect(service, true, danmakuClass);
            if (target != null) {
                Leodanmu.log("反射定位弹幕目标成功: activity.F.u -> " + activity.getClass().getName() + " / " + target.getClass().getName());
                return target;
            }
        } catch (Throwable e) {
            Leodanmu.log("activity.F 反射失败: " + e.getMessage());
        }

        Object candidate = tryResolveDanmakuTargetFromLikelyActivityFields(activity, true, danmakuClass);
        if (candidate != null) return candidate;
        return null;
    }

    private static Object tryResolveDanmakuTargetFromPlaybackServiceDirect(Object service, boolean verbose, Class<?> danmakuClass) {
        if (service == null) return null;
        try {
            Field danmakuField = findField(service.getClass(), "u");
            if (danmakuField == null) {
                if (verbose) Leodanmu.log("service.u 字段不存在: " + service.getClass().getName());
                return null;
            }
            danmakuField.setAccessible(true);
            Object target = danmakuField.get(service);
            if (target == null) {
                if (verbose) Leodanmu.log("service.u 返回空: " + service.getClass().getName());
                return null;
            }
            if (findDanmakuMethod(target.getClass(), danmakuClass) != null) {
                Object normalized = normalizeControllerLikeTarget(target, "service.u", danmakuClass, verbose);
                if (normalized != null) return normalized;
            }
            if (verbose) Leodanmu.log("service.u 不含弹幕方法: " + target.getClass().getName());
        } catch (Throwable e) {
            if (verbose) Leodanmu.log("service.u 反射失败: " + e.getMessage());
        }

        try {
            Class<?> current = service.getClass();
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(service);
                    if (value == null) continue;
                    Class<?> valueClass = value.getClass();
                    if (isSimpleValueType(valueClass)) continue;
                    if (!isLikelyDanmakuCarrierField(field, valueClass) && findDanmakuMethod(valueClass, danmakuClass) == null) continue;
                    Object resolved = tryResolveDanmakuTargetFromObject(value,
                            service.getClass().getName() + "#" + field.getName(),
                            2,
                            Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()),
                            false,
                            danmakuClass);
                    if (resolved != null) {
                        if (verbose) Leodanmu.log("反射定位弹幕目标成功: service-field-scan -> " + valueClass.getName());
                        return resolved;
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable e) {
            if (verbose) Leodanmu.log("service 字段扫描失败: " + e.getMessage());
        }
        return null;
    }

    private static Object tryResolvePlayerFromActivityFields(Activity activity, Class<?> danmakuClass) {
        if (activity == null) return null;
        Class<?> current = activity.getClass();
        while (current != null) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(activity);
                    if (value == null) continue;

                    if (isFongMiPlayerClass(value.getClass())) {
                        Object resolved = normalizeResolvedPlayerTarget(value, current.getName() + "#" + field.getName(), danmakuClass, true);
                        if (resolved != null) return resolved;
                    }

                    if (isLikelyPlaybackServiceField(field, value)) {
                        Object nestedPlayer = tryResolvePlayerFromService(value, true, danmakuClass);
                        if (nestedPlayer != null) {
                            Leodanmu.log("反射定位播放器成功: 字段服务 " + current.getName() + "#" + field.getName());
                            return nestedPlayer;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object normalizeResolvedPlayerTarget(Object candidate, String label, Class<?> danmakuClass, boolean verbose) {
        if (candidate == null) return null;
        Class<?> candidateClass = candidate.getClass();
        if (findDanmakuMethod(candidateClass, danmakuClass) != null &&
                !isViewOnlyDanmakuTarget(candidateClass) &&
                !isWrapperOrAsyncTarget(candidateClass)) {
            if (verbose) Leodanmu.log("反射定位弹幕目标成功: " + label + " -> " + candidateClass.getName());
            return candidate;
        }
        if (isViewOnlyDanmakuTarget(candidateClass) || isWrapperOrAsyncTarget(candidateClass) || isLikelyPlayerWrapperClass(candidateClass)) {
            Object nested = tryResolveDanmakuTargetFromObject(candidate,
                    label + "#" + candidateClass.getSimpleName(),
                    3,
                    Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()),
                    false,
                    danmakuClass);
            if (nested != null) {
                if (verbose) Leodanmu.log("反射定位弹幕目标成功: " + label + " -> " + nested.getClass().getName());
                return nested;
            }
            return null;
        }
        if (isFongMiPlayerClass(candidateClass)) {
            if (verbose) Leodanmu.log("反射定位播放器成功: " + label + " -> " + candidateClass.getName());
            return candidate;
        }
        return null;
    }

    private static boolean isFongMiPlayerClass(Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        return name.endsWith(".player.PlayerManager") ||
                name.endsWith(".player.Players") ||
                name.startsWith("com.fongmi.android.tv.player.") ||
                name.endsWith(".Player") ||
                name.endsWith(".ExoMediaPlayer") ||
                name.endsWith(".IjkPlayer") ||
                name.endsWith(".VodPlayer");
    }

    private static boolean hasSingleArgMethodNamed(Class<?> type, String methodName) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterTypes().length == 1) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static Method findDanmakuMethod(Class<?> targetClass, Class<?> danmakuClass) {
        if (targetClass == null || danmakuClass == null) return null;
        Method method = findCompatibleMethod(targetClass, "o", danmakuClass);
        if (method != null) return method;
        method = findCompatibleMethod(targetClass, "setDanmaku", danmakuClass);
        if (method != null) return method;
        return findAnyCompatibleSingleArgMethod(targetClass, danmakuClass);
    }

    private static Object tryResolveDanmakuTargetFromObject(Object holder, String ownerLabel, int depth, Set<Object> visited, boolean verbose, Class<?> danmakuClass) {
        if (holder == null || depth < 0) return null;
        if (visited.contains(holder)) return null;
        visited.add(holder);

        Class<?> holderClass = holder.getClass();
        Method danmakuMethod = findDanmakuMethod(holderClass, danmakuClass);
        if (danmakuMethod != null) {
            Object normalized = normalizeControllerLikeTarget(holder, ownerLabel, danmakuClass, verbose);
            if (normalized != null) return normalized;
        }

        if (isFongMiPlayerClass(holderClass) && !isLikelyPlayerWrapperClass(holderClass)) {
            if (verbose) Leodanmu.log("反射定位弹幕目标成功: " + ownerLabel + " -> " + holderClass.getName());
            return holder;
        }

        return tryResolveDanmakuTargetFromFields(holder, ownerLabel, depth, visited, verbose, danmakuClass);
    }

    private static Object tryResolveDanmakuTargetFromFields(Object holder, String ownerLabel, int depth, Set<Object> visited, boolean verbose, Class<?> danmakuClass) {
        if (holder == null || depth < 0) return null;
        Class<?> current = holder.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value == null) continue;

                    Class<?> valueClass = value.getClass();
                    if (isSimpleValueType(valueClass)) continue;

                    if (isFongMiPlayerClass(valueClass) &&
                            !isLikelyPlayerWrapperClass(valueClass) &&
                            !isViewOnlyDanmakuTarget(valueClass) &&
                            !isWrapperOrAsyncTarget(valueClass)) {
                        if (verbose) Leodanmu.log("反射定位播放器成功: " + ownerLabel + "#" + field.getName() + " -> " + valueClass.getName());
                        return value;
                    }

                    if (findDanmakuMethod(valueClass, danmakuClass) != null) {
                        Object normalized = normalizeControllerLikeTarget(value, ownerLabel + "#" + field.getName(), danmakuClass, verbose);
                        if (normalized != null) return normalized;
                    }

                    if (isLikelyPlaybackServiceField(field, value)) {
                        Object player = tryResolvePlayerFromService(value, false, danmakuClass);
                        if (player != null) {
                            if (verbose) Leodanmu.log("反射定位播放器成功: " + ownerLabel + "#" + field.getName() + " -> service-scan");
                            return player;
                        }
                    }

                    if (depth > 0) {
                        Object nested = tryResolveDanmakuTargetFromObject(value, ownerLabel + "#" + field.getName(), depth - 1, visited, verbose, danmakuClass);
                        if (nested != null) return nested;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean isLikelyDanmakuCarrierField(Field field, Class<?> valueClass) {
        if (field == null || valueClass == null) return false;
        String fieldName = field.getName().toLowerCase();
        String typeName = valueClass.getName().toLowerCase();
        if ("u".equals(fieldName) || "f".equals(fieldName) || "h".equals(fieldName) || "d".equals(fieldName)) return true;
        if (fieldName.contains("player") || fieldName.contains("manager") || fieldName.contains("controller") ||
                fieldName.contains("session") || fieldName.contains("spec") || fieldName.contains("render") ||
                fieldName.contains("engine") || fieldName.contains("media") || fieldName.contains("core")) return true;
        return typeName.contains("player") || typeName.contains("manager") || typeName.contains("controller") ||
                typeName.contains("session") || typeName.contains("spec") || typeName.contains("render") ||
                typeName.contains("engine") || typeName.contains("media") || typeName.contains("exo") ||
                typeName.contains("ijk");
    }

    private static boolean isSimpleValueType(Class<?> type) {
        if (type == null) return true;
        if (type.isPrimitive() || type.isArray() || type.isEnum()) return true;
        String name = type.getName();
        return name.startsWith("java.lang.") ||
                name.startsWith("java.util.") ||
                name.startsWith("android.animation.") ||
                name.startsWith("android.graphics.") ||
                name.startsWith("android.drawable.") ||
                name.startsWith("android.content.res.") ||
                name.startsWith("android.view.") ||
                name.startsWith("android.view.animation.") ||
                name.startsWith("android.widget.") ||
                name.startsWith("androidx.");
    }

    private static boolean isLikelyPlaybackServiceField(Field field, Object value) {
        if (field == null || value == null) return false;
        String fieldName = field.getName().toLowerCase();
        String typeName = value.getClass().getName().toLowerCase();
        if (value instanceof CharSequence) return false;
        if (isSimpleValueType(value.getClass())) return false;
        if ("mservice".equals(fieldName) || "service".equals(fieldName)) return true;
        return typeName.endsWith(".service.playbackservice") ||
                typeName.equals("com.fongmi.android.tv.service.playbackservice") ||
                typeName.contains("playbackservice");
    }

    private static boolean isLikelyPlayerWrapperClass(Class<?> type) {
        if (type == null) return false;
        String name = type.getName().toLowerCase();
        if (name.endsWith(".players")) return true;
        int objectFieldCount = 0;
        int danmakuMethodCount = 0;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 1) danmakuMethodCount++;
            }
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                if (isSimpleValueType(field.getType())) continue;
                objectFieldCount++;
            }
            current = current.getSuperclass();
        }
        return danmakuMethodCount == 0 && objectFieldCount > 0;
    }

    private static boolean isViewOnlyDanmakuTarget(Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        if (name.contains("master.flame.danmaku.ui.widget.DanmakuView")) return true;
        Class<?> current = type;
        while (current != null) {
            if ("android.view.View".equals(current.getName())) return true;
            current = current.getSuperclass();
        }
        return false;
    }

    private static Object normalizeControllerLikeTarget(Object candidate, String label, Class<?> danmakuClass, boolean verbose) {
        if (candidate == null) return null;
        Class<?> candidateClass = candidate.getClass();
        Method method = findDanmakuMethod(candidateClass, danmakuClass);
        if (method == null) return null;
        if (isViewOnlyDanmakuTarget(candidateClass) || isWrapperOrAsyncTarget(candidateClass)) return null;
        if (!looksLikePlaybackController(candidateClass, method) && !looksLikeDanmakuController(candidateClass, method)) return null;
        if (verbose) Leodanmu.log("反射定位弹幕目标成功: " + label + " -> " + candidateClass.getName());
        return candidate;
    }

    private static boolean looksLikePlaybackController(Class<?> type, Method danmakuMethod) {
        if (type == null || danmakuMethod == null) return false;
        String name = type.getName().toLowerCase();
        if (name.startsWith("defpackage.")) {
            int score = 0;
            Class<?> current = type;
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    Class<?> fieldType = field.getType();
                    String fieldName = field.getName().toLowerCase();
                    String fieldTypeName = fieldType.getName().toLowerCase();
                    if (fieldName.equals("h") || fieldName.equals("d") || fieldName.equals("g") || fieldName.equals("c")) score++;
                    if (fieldTypeName.contains("media3") || fieldTypeName.contains("danmaku") || fieldTypeName.contains("playback")) score += 2;
                    if (fieldTypeName.contains("player") || fieldTypeName.contains("view")) score++;
                }
                current = current.getSuperclass();
            }
            if ("o".equals(danmakuMethod.getName())) score += 2;
            return score >= 3;
        }
        return danmakuMethod.getName().equals("o");
    }

    private static boolean looksLikeDanmakuController(Class<?> type, Method danmakuMethod) {
        if (type == null || danmakuMethod == null) return false;
        String name = type.getName().toLowerCase();
        int score = 0;
        if (name.startsWith("defpackage.")) score++;
        if ("o".equals(danmakuMethod.getName())) score += 2;
        if (findMethod(type, "getRealUrl") != null) score++;
        Class<?> current = type;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                String fieldTypeName = field.getType().getName().toLowerCase();
                String fieldName = field.getName().toLowerCase();
                if (fieldTypeName.contains("danmakuview")) score += 2;
                if (fieldTypeName.contains("ijkvideoview") || fieldTypeName.contains("playerview")) score++;
                if (fieldTypeName.contains("okhttp") || fieldTypeName.contains("request")) score++;
                if (fieldName.equals("g") || fieldName.equals("c") || fieldName.equals("i")) score++;
            }
            current = current.getSuperclass();
        }
        return score >= 3;
    }

    private static boolean isWrapperOrAsyncTarget(Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        if (name.startsWith("java.util.concurrent.")) return true;
        if (name.startsWith("kotlinx.coroutines.")) return true;
        if (name.startsWith("android.animation.")) return true;
        if (name.startsWith("android.graphics.drawable.")) return true;
        if (name.startsWith("android.view.animation.")) return true;
        if (Runnable.class.isAssignableFrom(type)) return true;
        if (java.util.concurrent.Callable.class.isAssignableFrom(type)) return true;
        if (java.util.concurrent.Executor.class.isAssignableFrom(type)) return true;
        if (java.util.concurrent.Future.class.isAssignableFrom(type)) return true;
        if (android.animation.Animator.class.isAssignableFrom(type)) return true;
        if (android.graphics.drawable.Drawable.class.isAssignableFrom(type)) return true;
        return false;
    }

    private static Object tryResolveDanmakuTargetFromLikelyActivityFields(Activity activity, boolean verbose, Class<?> danmakuClass) {
        if (activity == null) return null;
        try {
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            Class<?> current = activity.getClass();
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(activity);
                    if (value == null) continue;
                    Class<?> valueClass = value.getClass();
                    if (isSimpleValueType(valueClass)) continue;

                    if (isLikelyPlaybackServiceField(field, value)) {
                        Object player = tryResolvePlayerFromService(value, false, danmakuClass);
                        if (player != null) {
                            if (verbose) Leodanmu.log("反射定位播放器成功: activity-field-service -> " + current.getName() + "#" + field.getName());
                            return player;
                        }
                    }

                    if (isLikelyDanmakuCarrierField(field, valueClass) || findDanmakuMethod(valueClass, danmakuClass) != null || isFongMiPlayerClass(valueClass)) {
                        Object candidate = tryResolveDanmakuTargetFromObject(value,
                                activity.getClass().getName() + "#" + field.getName(),
                                3,
                                visited,
                                false,
                                danmakuClass);
                        if (candidate != null) {
                            if (verbose) Leodanmu.log("反射定位弹幕目标成功: activity-field-scan -> " + current.getName() + "#" + field.getName());
                            return candidate;
                        }
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable e) {
            if (verbose) Leodanmu.log("activity 字段扫描失败: " + e.getMessage());
        }
        return null;
    }

    private static Method findAnyCompatibleSingleArgMethod(Class<?> type, Class<?> parameterType) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 1) continue;
                Class<?> argType = method.getParameterTypes()[0];
                if (!argType.isAssignableFrom(parameterType)) continue;
                if (method.getReturnType() != Void.TYPE && method.getReturnType() != type) continue;
                String name = method.getName();
                if (name.startsWith("set") || name.length() <= 2 || name.contains("dan")) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Activity> getAliveActivities() {
        List<Activity> result = new ArrayList<>();
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<Object, Object> activities;
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT) {
                activities = (java.util.HashMap<Object, Object>) activitiesField.get(activityThread);
            } else {
                activities = (android.util.ArrayMap<Object, Object>) activitiesField.get(activityThread);
            }
            for (Object activityRecord : activities.values()) {
                if (activityRecord == null) continue;
                Class<?> recordClass = activityRecord.getClass();
                Field activityField = recordClass.getDeclaredField("activity");
                activityField.setAccessible(true);
                Activity act = (Activity) activityField.get(activityRecord);
                if (act == null || act.isFinishing()) continue;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && act.isDestroyed()) {
                    continue;
                }
                result.add(act);
            }
        } catch (Throwable e) {
            Leodanmu.log("枚举存活Activity失败: " + e.getMessage());
        }
        return result;
    }

    private static Class<?> findHostClass(ClassLoader loader, Activity activity, String suffix) {
        String[] prefixes = getHostPackagePrefixes(activity);
        for (String prefix : prefixes) {
            if (TextUtils.isEmpty(prefix)) continue;
            try {
                return Class.forName(prefix + "." + suffix, false, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String[] getHostPackagePrefixes(Activity activity) {
        List<String> prefixes = new ArrayList<>();
        Activity topActivity = Utils.getTopActivity();
        addPackagePrefixes(prefixes, activity);
        if (topActivity != null && topActivity != activity) addPackagePrefixes(prefixes, topActivity);
        if (prefixes.isEmpty()) prefixes.add("com.fongmi.android.tv");
        return prefixes.toArray(new String[0]);
    }

    private static void addPackagePrefixes(List<String> prefixes, Activity activity) {
        if (activity == null) return;
        String name = activity.getClass().getName();
        String[] markers = new String[]{
                ".ui.activity.",
                ".ui.",
                ".activity."
        };
        for (String marker : markers) {
            int index = name.indexOf(marker);
            if (index > 0) {
                String prefix = name.substring(0, index);
                if (!prefixes.contains(prefix)) prefixes.add(prefix);
            }
        }
        Package pkg = activity.getClass().getPackage();
        if (pkg != null) {
            String packageName = pkg.getName();
            if (!prefixes.contains(packageName)) prefixes.add(packageName);
        }
    }

    // ========== 替换：DOM 解析统计弹幕条数 ==========
    private static int countDanmakuItems(String xmlData) {
        if (TextUtils.isEmpty(xmlData) || !xmlData.trim().startsWith("<")) return 0;
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.xml.sax.InputSource is = new org.xml.sax.InputSource(new java.io.StringReader(xmlData));
            org.w3c.dom.Document doc = builder.parse(is);
            return doc.getElementsByTagName("d").getLength();
        } catch (Exception e) {
            Leodanmu.log("解析弹幕数据异常: " + e.getMessage());
            return -1; // 返回-1表示解析异常
        }
    }

    // ========== 新增：编辑距离相似度计算 ==========
    private static double calculateSimilarity(String s1, String s2) {
        String longer = s1, shorter = s2;
        if (s1.length() < s2.length()) {
            longer = s2;
            shorter = s1;
        }
        int longerLength = longer.length();
        if (longerLength == 0) return 1.0;
        return (longerLength - editDistance(longer, shorter)) / (double) longerLength;
    }

    private static int editDistance(String s1, String s2) {
        s1 = s1.toLowerCase();
        s2 = s2.toLowerCase();
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else if (j > 0) {
                    int newValue = costs[j - 1];
                    if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                        newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                    }
                    costs[j - 1] = lastValue;
                    lastValue = newValue;
                }
            }
            if (i > 0) costs[s2.length()] = lastValue;
        }
        return costs[s2.length()];
    }

    private static void logObjectShape(String label, Object target, Class<?> danmakuClass, int depth) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        logObjectShapeInternal(label, target, danmakuClass, depth, visited);
    }

    private static void logObjectShapeInternal(String label, Object target, Class<?> danmakuClass, int depth, Set<Object> visited) {
        if (target == null || depth <= 0 || visited.contains(target)) return;
        visited.add(target);
        try {
            Class<?> current = target.getClass();
            StringBuilder sb = new StringBuilder();
            sb.append("对象形状: ").append(label).append(" (").append(current.getName()).append(")");
            Leodanmu.log(sb.toString());
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    if (isSimpleValueType(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value == null) continue;
                    String typeName = value.getClass().getName();
                    String fieldName = field.getName();
                    boolean hasMethod = findDanmakuMethod(value.getClass(), danmakuClass) != null;
                    Leodanmu.log("  字段: " + current.getSimpleName() + "#" + fieldName + " -> " + typeName + (hasMethod ? " [含Danmaku方法]" : ""));
                    if (depth > 1) {
                        logObjectShapeInternal(label + "." + fieldName, value, danmakuClass, depth - 1, visited);
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }
}