package com.github.catvod.spider;

import com.github.catvod.spider.entity.DanmakuItem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DanmakuManager {

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

        DanmakuItem nextDanmakuItem = lastDanmakuItemMap.get(nextId);
        if (nextDanmakuItem != null) {
            Leodanmu.log("✅ 获取到下一个弹幕弹幕信息: " + nextDanmakuItem.toString());
            // 即使从已缓存 Map 找到，也检查是否有预缓存 XML
            String cachedXml = sCachedXmlMap.get(nextId);
            if (cachedXml != null) {
                sPreCachedXmlForPush = cachedXml;
                sUsingPreCache = true;
                Leodanmu.log("⚡ 预缓存有效(来自持久Map): epId=" + nextId);
            } else {
                Leodanmu.log("📋 预缓存不可用(持久Map无数据): epId=" + nextId);
            }
            return nextDanmakuItem;
        }

        // 检查预缓存是否有命中
        if (nextId == sPreCachedEpId && sPreCachedDanmakuItem != null) {
            Leodanmu.log("⚡ 预缓存命中: epId=" + nextId);
            // 把预缓存的 XML 置入推送使用位
            sPreCachedXmlForPush = sPreCachedXml;
            sUsingPreCache = true;
            lastDanmakuItemMap.put(nextId, sPreCachedDanmakuItem);
            sPreCachedDanmakuItem = null;
            sPreCachedEpId = -1;
            sPreCachedXml = null;
            return lastDanmakuItemMap.get(nextId);
        }

        return null;
    }

    public static void cachePreCachedItem(int epId, DanmakuItem item, String xmlData) {
        sPreCachedEpId = epId;
        sPreCachedDanmakuItem = item;
        sPreCachedXml = xmlData;
        if (xmlData != null && !xmlData.isEmpty()) {
            sCachedXmlMap.put(epId, xmlData);
        }
        Leodanmu.log("💾 预缓存已保存: epId=" + epId + ", xmlLen=" + (xmlData == null ? 0 : xmlData.length()));
    }

    public static int getPreCachedEpId() {
        return sPreCachedEpId;
    }

    /** WebServer /danmaku-cache 查询接口 */
    public static String getCachedXml(int epId) {
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
