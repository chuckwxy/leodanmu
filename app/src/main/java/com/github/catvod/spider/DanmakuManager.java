package com.github.catvod.spider;

import com.github.catvod.spider.entity.DanmakuItem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DanmakuManager {

    public static String lastAutoDanmakuUrl = "";
    public static String lastManualDanmakuUrl = "";
    public static String lastDanmakuUrl = "";
    public static ConcurrentMap<Integer, DanmakuItem> lastDanmakuItemMap = new ConcurrentHashMap<>();
    public static int lastDanmakuId = -1;
    public static boolean hasAutoSearched = false;
    public static String lastProcessedTitle = "";
    public static String currentVideoSignature = "";
    public static long lastVideoDetectedTime = 0;

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
            lastDanmakuId = nextId;
            return nextDanmakuItem;
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
            constructed.setEpTitle("第" + newEpisodeNum + "集");
            constructed.setShortTitle("第" + newEpisodeNum + "集");
            constructed.setAnimeTitle(currentItem.getAnimeTitle());
            Leodanmu.log("🛠️ 构造 ID 递增条目: epId=" + nextId + " apiBase=" + currentItem.getApiBase());
            lastDanmakuItemMap.put(nextId, constructed);
            lastDanmakuId = nextId;
            return constructed;
        }

        return null;
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

}
