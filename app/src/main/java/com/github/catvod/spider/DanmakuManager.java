package com.github.catvod.spider;

import com.github.catvod.spider.entity.DanmakuItem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DanmakuManager {

    // 缓存过期时间：24小时（兜底安全网，主要靠换系列/退出时清理）
    private static final long CACHE_EXPIRE_TIME = 24 * 60 * 60 * 1000L;

    public static String lastAutoDanmakuUrl = "";  // 上次自动推送的弹幕URL
    public static String lastManualDanmakuUrl = ""; // 上次手动选择的弹幕URL
    public static String lastDanmakuUrl = ""; // 上次弹幕URL
    public static ConcurrentMap<Integer, DanmakuItem> lastDanmakuItemMap = new ConcurrentHashMap<>();
    public static int lastDanmakuId = -1;          // 上次的弹幕ID
    public static boolean hasAutoSearched = false; // 是否已自动搜索过
    public static String lastProcessedTitle = "";  // 上次处理的标题
    public static String currentVideoSignature = "";  // 当前视频的唯一标识（基于标题提取）
    public static long lastVideoDetectedTime = 0;     // 上次检测到视频的时间

    // 预缓存：推送成功后提前抓取下集弹幕 XML，换集时直接使用本地缓存
    private static DanmakuItem sPreCachedDanmakuItem = null;
    private static int sPreCachedEpId = -1;
    private static String sPreCachedXml = null;
    // 持久 XML 缓存，WebServer /danmaku-cache 端点可查，clearPreCache 时清理
    private static final ConcurrentMap<Integer, String> sCachedXmlMap = new ConcurrentHashMap<>();
    // 缓存时间戳，用于判断缓存是否过期
    private static final ConcurrentMap<Integer, Long> sCachedXmlTimestamps = new ConcurrentHashMap<>();
    // 正在使用预缓存推送的标志
    private static volatile boolean sUsingPreCache = false;
    private static volatile String sPreCachedXmlForPush = null;

    public static void recordDanmakuUrl(DanmakuItem danmakuItem, boolean isAuto) {
        if (isAuto) {
            lastAutoDanmakuUrl = danmakuItem.getDanmakuUrl();
            Leodanmu.log("记录自动弹幕URL: " + danmakuItem.getDanmakuUrl());
        } else {
            lastManualDanmakuUrl = danmakuItem.getDanmakuUrl();
            Leodanmu.log("记录手动弹幕URL: " + danmakuItem.getDanmakuUrl());
        }
        lastDanmakuUrl = danmakuItem.getDanmakuUrl();
        lastDanmakuId = danmakuItem.getEpId();
        lastDanmakuItemMap.put(lastDanmakuId, danmakuItem);

        // 记录视频检测时间
        lastVideoDetectedTime = System.currentTimeMillis();
//        DanmakuSpider.log("✅ 更新视频检测时间: " + lastVideoDetectedTime);

        // 设置已搜索过，这样换集时就会尝试递增
        if (lastDanmakuId > 0) {
            hasAutoSearched = true;
//            DanmakuSpider.log("✅ 设置 hasAutoSearched = true (ID: " + lastDanmakuId + ")");
        }
    }

    public static DanmakuItem getNextDanmakuItem(int currentEpisodeNum, int newEpisodeNum) {
        int nextId = lastDanmakuId + (newEpisodeNum - currentEpisodeNum);
        Leodanmu.log("📝 获取下一个弹幕URL: " + lastDanmakuId + " -> " + nextId);

        if (nextId <= 0) {
            return null;
        }

        // 1. 优先从已有缓存 Map 中查找
        DanmakuItem nextDanmakuItem = lastDanmakuItemMap.get(nextId);
        if (nextDanmakuItem != null) {
            Leodanmu.log("✅ 获取到下一个弹幕弹幕信息: " + nextDanmakuItem.toString());
            // 检查是否有预缓存 XML（含过期检查）
            String cachedXml = getCachedXml(nextId);
            if (cachedXml != null) {
                sPreCachedXmlForPush = cachedXml;
                sUsingPreCache = true;
                Leodanmu.log("⚡ 预缓存有效(来自持久Map): epId=" + nextId);
            } else {
                sPreCachedXmlForPush = null;
                sUsingPreCache = false;
                Leodanmu.log("📋 预缓存不可用(持久Map无数据): epId=" + nextId);
            }
            return nextDanmakuItem;
        }

        // 2. 检查预缓存是否有命中
        if (nextId == sPreCachedEpId && sPreCachedDanmakuItem != null) {
            Leodanmu.log("⚡ 预缓存命中: epId=" + nextId);
            sPreCachedXmlForPush = sPreCachedXml;
            sUsingPreCache = true;
            // 从 current 条目继承 epTitle，避免 "预缓存#XXX" 污染显示
            DanmakuItem current = lastDanmakuItemMap.get(lastDanmakuId);
            if (current != null && current.getEpTitle() != null) {
                sPreCachedDanmakuItem.setEpTitle(current.getEpTitle());
            }
            lastDanmakuItemMap.put(nextId, sPreCachedDanmakuItem);
            sPreCachedDanmakuItem = null;
            sPreCachedEpId = -1;
            sPreCachedXml = null;
            return lastDanmakuItemMap.get(nextId);
        }

        // 3. 兜底构造（仅当 nextId 合理时）
        // nextId < 100000 通常说明 lastDanmakuId 已被重置或异常
        if (nextId < 100000) {
            Leodanmu.log("⚠️ ID递增结果异常，跳过构造: nextId=" + nextId + " (lastDanmakuId=" + lastDanmakuId + ")");
            return null;
        }
        DanmakuItem currentItem = lastDanmakuItemMap.get(lastDanmakuId);
        if (currentItem != null && currentItem.getApiBase() != null) {
            DanmakuItem constructed = new DanmakuItem();
            constructed.setEpId(nextId);
            constructed.setApiBase(currentItem.getApiBase());
            constructed.setFrom(currentItem.getFrom());
            constructed.setTitle(currentItem.getTitle());
            constructed.setEpTitle("切换至#" + nextId);
            constructed.setAnimeTitle(currentItem.getAnimeTitle());
            if (currentItem.getShortTitle() != null) constructed.setShortTitle(currentItem.getShortTitle());
            sPreCachedXmlForPush = null;
            sUsingPreCache = false;
            Leodanmu.log("🛠️ 构造 ID 递增条目: epId=" + nextId + " apiBase=" + currentItem.getApiBase());
            lastDanmakuItemMap.put(nextId, constructed);
            return constructed;
        }

        return null;
    }

    public static void cachePreCachedItem(int epId, DanmakuItem item, String xmlData) {
        sPreCachedEpId = epId;
        sPreCachedDanmakuItem = item;
        sPreCachedXml = xmlData;
        if (xmlData != null && !xmlData.isEmpty()) {
            sCachedXmlMap.put(epId, xmlData);
            sCachedXmlTimestamps.put(epId, System.currentTimeMillis());
        }
        Leodanmu.log("💾 预缓存已保存: epId=" + epId + ", xmlLen=" + (xmlData == null ? 0 : xmlData.length()));
    }

    public static int getPreCachedEpId() {
        return sPreCachedEpId;
    }

    /** WebServer /danmaku-cache 查询接口（含过期检查） */
    public static String getCachedXml(int epId) {
        Long cachedAt = sCachedXmlTimestamps.get(epId);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt > CACHE_EXPIRE_TIME) {
            sCachedXmlMap.remove(epId);
            sCachedXmlTimestamps.remove(epId);
            Leodanmu.log("⏰ 缓存已过期: epId=" + epId + " (cacheAge=" + (System.currentTimeMillis() - cachedAt) / 1000 + "s)");
            return null;
        }
        return sCachedXmlMap.get(epId);
    }

    public static boolean isUsingPreCache() {
        return sUsingPreCache;
    }

    public static String consumePreCachedXmlForPush() {
        String xml = sPreCachedXmlForPush;
        sPreCachedXmlForPush = null;
        sUsingPreCache = false;
        return xml;
    }

    public static void clearPreCache() {
        sPreCachedDanmakuItem = null;
        sPreCachedEpId = -1;
        sPreCachedXml = null;
        sPreCachedXmlForPush = null;
        sUsingPreCache = false;
        sCachedXmlMap.clear();
        sCachedXmlTimestamps.clear();
    }

    public static void resetAutoSearch() {
        hasAutoSearched = false;
        lastProcessedTitle = "";
        currentVideoSignature = "";
        lastVideoDetectedTime = 0;
        lastDanmakuId = -1;
        lastAutoDanmakuUrl = "";
        lastManualDanmakuUrl = "";
        lastDanmakuUrl = "";
        lastDanmakuItemMap.clear();
        // 不清理预缓存: 预缓存由 consume 或 stopHookMonitor 清理
    }
}
