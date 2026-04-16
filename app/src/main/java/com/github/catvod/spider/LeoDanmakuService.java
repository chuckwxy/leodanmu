package com.github.catvod.spider;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.spider.entity.DanmakuItem;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
        pushDanmakuDirect(danmakuItem, activity, isAuto, false);
    }

    public static void pushDanmakuDirect(DanmakuItem danmakuItem, Activity activity, boolean isAuto, boolean fastPushThenVerify) {
        String danmakuUrl = danmakuItem.getDanmakuUrl();
        if (TextUtils.isEmpty(danmakuUrl)) {
            Leodanmu.log("⚠️ 推送弹幕URL为空，跳过");
            return;
        }

        long currentTime = System.currentTimeMillis();
        Long lastPush = lastPushTimes.get(danmakuUrl);
        if (lastPush != null && (currentTime - lastPush < PUSH_MIN_INTERVAL)) {
            Leodanmu.log("⚠️ 推送过于频繁 (同一URL)，跳过: " + danmakuUrl);
            return;
        }

        lastPushTimes.put(danmakuUrl, currentTime);
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
                    pushDanmakuInThread(danmakuItem, activity, fastPushThenVerify);
                }
            }).start();
        } else {
            Leodanmu.log("已经在子线程，直接执行弹幕推送");
            pushDanmakuInThread(danmakuItem, activity, fastPushThenVerify);
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
        try {
            if (TextUtils.isEmpty(danmakuItem.getDanmakuUrl())) {
                Leodanmu.log("推送弹幕URL为空");
                return;
            }

            if (fastPushThenVerify) {
                Leodanmu.log("⚡ 命中已验证链路，先推送后异步校验: " + danmakuItem.getEpTitle());
                String fastPushResp = pushDanmakuToPlayer(danmakuItem);
                if (!TextUtils.isEmpty(fastPushResp) && fastPushResp.toLowerCase().contains("ok")) {
                    Leodanmu.log("✅ 快速推送已发送，等待异步校验: " + danmakuItem.getDanmakuUrl());
                    verifyDanmakuAfterPushAsync(danmakuItem, activity);
                } else {
                    Leodanmu.log("❌ 快速推送失败，响应: " + fastPushResp);
                }
                return;
            }

            int danmakuCount = fetchValidDanmakuCount(danmakuItem, 3);
            if (danmakuCount <= 0) {
                Leodanmu.log("❌ 无法获取有效的弹幕数据（或弹幕为空），取消推送");
                return;
            }

            String pushResp = pushDanmakuToPlayer(danmakuItem);
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

    // private static String buildShortStack() {
    //     StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    //     StringBuilder sb = new StringBuilder();
    //     int count = 0;
    //     for (StackTraceElement e : stack) {
    //         String cls = e.getClassName();
    //         if (cls.contains("Thread") || cls.contains("LeoDanmakuService")) continue;
    //         if (count > 0) sb.append(" <- ");
    //         sb.append(cls).append("#").append(e.getMethodName()).append(":").append(e.getLineNumber());
    //         count++;
    //         if (count >= 6) break;
    //     }
    //     return sb.toString();
    // }

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
}