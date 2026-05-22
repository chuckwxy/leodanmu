package com.github.catvod.spider;

import com.github.catvod.spider.entity.DanmakuItem;

import android.text.TextUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DanmakuManager {

    // 缓存过期时间：24小时（兜底安全网，主要靠换系列/退出时清理）
    private static final long CACHE_EXPIRE_TIME = 24 * 60 * 60 * 1000L;

    public static String lastAutoDanmakuUrl = "";
    public static String lastManualDanmakuUrl = "";
    public static String lastDanmakuUrl = "";
    public static ConcurrentMap<Integer, DanmakuItem> lastDanmakuItemMap = new ConcurrentHashMap<>();
    public static int lastDanmakuId = -1;
    public static boolean hasAutoSearched = false;
    public static String lastProcessedTitle = "";
    public static String currentVideoSignature = "";
    public static long lastVideoDetectedTime = 0;

    private static DanmakuItem sPreCachedDanmakuItem = null;
    private static int sPreCachedEpId = -1;
    private static String sPreCachedXml = null;
    private static final ConcurrentMap<String, String> sCachedXmlMap = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Long> sCachedXmlTimestamps = new ConcurrentHashMap<>();
    private static volatile boolean sUsingPreCache = false;
    private static volatile String sPreCachedXmlForPush = null;

    // 从 DanmakuItem 构建唯一缓存 key: "animeTitle|from|epId"
    // 如果 animeTitle 为空则回退到 title
    public static String buildCacheKey(DanmakuItem item) {
        if (item == null) return "";
        String showId = item.getAnimeTitle();
        if (TextUtils.isEmpty(showId)) showId = item.getTitle();
        if (TextUtils.isEmpty(showId)) return "";
        String from = item.getFrom();
        if (TextUtils.isEmpty(from)) from = "";
        Integer epId = item.getEpId();
        if (epId == null) return "";
        return showId + "|" + from + "|" + epId;
    }

    public static String encodeCacheKey(DanmakuItem item) {
        String key = buildCacheKey(item);
        if (TextUtils.isEmpty(key)) return "";
        try {
            return URLEncoder.encode(key, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return key;
        }
    }

    public static String decodeCacheKey(String encoded) {
        if (TextUtils.isEmpty(encoded)) return "";
        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return encoded;
        }
    }

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

        lastVideoDetectedTime = System.currentTimeMillis();

        if (lastDanmakuId > 0) {
            hasAutoSearched = true;
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
            String cachedXml = getCachedXml(nextDanmakuItem);
            if (cachedXml != null) {
                sPreCachedXmlForPush = cachedXml;
                sUsingPreCache = true;
                Leodanmu.log("⚡ 预缓存有效(来自持久Map): " + buildCacheKey(nextDanmakuItem));
            } else {
                sPreCachedXmlForPush = null;
                sUsingPreCache = false;
                Leodanmu.log("📋 预缓存不可用(持久Map无数据): " + buildCacheKey(nextDanmakuItem));
            }
            lastDanmakuId = nextId;
            return nextDanmakuItem;
        }

        if (nextId == sPreCachedEpId && sPreCachedDanmakuItem != null) {
            Leodanmu.log("⚡ 预缓存命中: epId=" + nextId);
            sPreCachedXmlForPush = sPreCachedXml;
            sUsingPreCache = true;
            lastDanmakuItemMap.put(nextId, sPreCachedDanmakuItem);
            sPreCachedDanmakuItem = null;
            sPreCachedEpId = -1;
            sPreCachedXml = null;
            lastDanmakuId = nextId;
            return lastDanmakuItemMap.get(nextId);
        }

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
            lastDanmakuId = nextId;
            return constructed;
        }

        return null;
    }

    public static void cachePreCachedItem(int epId, DanmakuItem item, String xmlData) {
        sPreCachedEpId = epId;
        sPreCachedDanmakuItem = item;
        sPreCachedXml = xmlData;
        if (xmlData != null && !xmlData.isEmpty()) {
            String key = buildCacheKey(item);
            if (!TextUtils.isEmpty(key)) {
                sCachedXmlMap.put(key, xmlData);
                sCachedXmlTimestamps.put(key, System.currentTimeMillis());
                Leodanmu.log("💾 预缓存已保存: key=" + key + ", xmlLen=" + xmlData.length());
            }
        }
    }

    public static int getPreCachedEpId() {
        return sPreCachedEpId;
    }

    // 按 DanmakuItem 完全匹配（含过期检查），保证同一剧集同一集才能命中
    public static String getCachedXml(DanmakuItem item) {
        if (item == null) return null;
        String key = buildCacheKey(item);
        if (TextUtils.isEmpty(key)) return null;
        Long cachedAt = sCachedXmlTimestamps.get(key);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt > CACHE_EXPIRE_TIME) {
            invalidateCacheEntry(key);
            Leodanmu.log("⏰ 缓存已过期: key=" + key + " (cacheAge=" + (System.currentTimeMillis() - cachedAt) / 1000 + "s)");
            return null;
        }
        return sCachedXmlMap.get(key);
    }

    // 按原始 key 查（WebServer 用）
    public static String getCachedXmlByKey(String key) {
        if (TextUtils.isEmpty(key)) return null;
        Long cachedAt = sCachedXmlTimestamps.get(key);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt > CACHE_EXPIRE_TIME) {
            invalidateCacheEntry(key);
            Leodanmu.log("⏰ 缓存已过期: key=" + key);
            return null;
        }
        return sCachedXmlMap.get(key);
    }

    // 兼容旧版无参数的查询（清除相关联的缓存时用）
    public static String getCachedXml(int epId) {
        return null;
    }

    public static void invalidateCacheEntry(String key) {
        sCachedXmlMap.remove(key);
        sCachedXmlTimestamps.remove(key);
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
    }

    public static void setItemEpisodeTitle(DanmakuItem item, String epNum, String from) {
        if (item == null || TextUtils.isEmpty(epNum)) return;
        String epTitle;
        if (!TextUtils.isEmpty(from)) {
            epTitle = "[" + from + "] 第" + epNum + "集";
        } else {
            epTitle = "第" + epNum + "集";
        }
        item.setEpTitle(epTitle);
        item.setShortTitle("第" + epNum + "集");
    }
}
