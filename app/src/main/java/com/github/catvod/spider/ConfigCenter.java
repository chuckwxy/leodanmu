package com.github.catvod.spider;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.bean.Result;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

public class ConfigCenter extends Spider {

    private static final String SECTION_DANMAKU = "danmaku";
    private static final String SECTION_DOUBAN = "douban";

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            classes.put(createClass(SECTION_DANMAKU, "弹幕配置"));
            classes.put(createClass(SECTION_DOUBAN, "豆瓣配置"));

            result.put("class", classes);
            result.put("list", new JSONArray());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();

            switch (tid) {
                case SECTION_DANMAKU:
                    buildDanmakuSection(list);
                    break;
                case SECTION_DOUBAN:
                    buildDoubanSection(list);
                    break;
            }

            result.put("list", list);
            result.put("page", 1);
            result.put("pagecount", 1);
            result.put("limit", 20);
            result.put("total", list.length());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void buildDanmakuSection(JSONArray list) throws Exception {
        Activity activity = Utils.getTopActivity();
        DanmakuConfig config = activity != null ? DanmakuConfigManager.getConfig(activity) : new DanmakuConfig();

        list.put(createActionVod("config", "弹幕配置", "", "配置弹幕API地址和推送参数"));
        list.put(createActionVod("auto_push", "自动推送弹幕", "",
                config.isAutoPushEnabled() ? "已开启" : "已关闭"));
        list.put(createActionVod("lp_config", "布局配置", "", "调整弹窗大小和透明度"));
        list.put(createActionVod("log", "查看日志", "", "运行日志与Hook诊断"));
    }

    private void buildDoubanSection(JSONArray list) throws Exception {
        int cacheSize = DoubanFetcher.getCacheSize();
        list.put(createActionVod("douban_cache", "豆瓣缓存", "",
                "已缓存 " + cacheSize + " 个分类，点击清除"));
        list.put(createActionVod("douban_prewarm", "豆瓣预热", "",
                "强制后台刷新所有分类缓存"));
    }

    @Override
    public String action(String action) {
        try {
            Activity ctx = Utils.getTopActivity();
            if (ctx == null || ctx.isFinishing()) return Result.notify("无可用界面");
            DanmakuConfig config = DanmakuConfigManager.getConfig(ctx);
            switch (action) {
                case "config":
                    runOnUiThread(ctx, () -> DanmakuUIHelper.showCombinedConfigDialog(ctx));
                    break;
                case "auto_push":
                    config.setAutoPushEnabled(!config.isAutoPushEnabled());
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Leodanmu.log("ConfigCenter: 自动推送状态切换: " + config.isAutoPushEnabled());
                    return Result.notify(config.isAutoPushEnabled() ? "自动推送已开启" : "自动推送已关闭");
                case "lp_config":
                    runOnUiThread(ctx, () -> DanmakuUIHelper.showLpConfigDialog(ctx));
                    break;
                case "log":
                    runOnUiThread(ctx, () -> DanmakuUIHelper.showLogDialog(ctx));
                    break;
                case "douban_cache":
                    runOnUiThread(ctx, () -> {
                        DoubanFetcher.clearCache();
                        Utils.safeShowToast(ctx, "豆瓣缓存已清除");
                    });
                    break;
                case "douban_prewarm":
                    runOnUiThread(ctx, () -> {
                        DoubanFetcher.prewarm();
                        Utils.safeShowToast(ctx, "豆瓣预热已触发");
                    });
                    break;
                default:
                    runOnUiThread(ctx, () -> Utils.safeShowToast(ctx, "功能开发中"));
                    break;
            }
            return Result.notify("已打开设置");
        } catch (Exception e) {
            Leodanmu.log("ConfigCenter action异常: " + e.getMessage());
            return Result.notify("打开失败");
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return action(ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContentShell(key);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContentShell(key);
    }

    private String searchContentShell(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            String[] ids = {"config", "auto_push", "lp_config", "log"};
            String[] names = {"弹幕配置", "自动推送弹幕", "布局配置", "查看日志"};
            for (int i = 0; i < ids.length; i++) {
                if (!key.equals(names[i])) continue;
                JSONObject vod = new JSONObject();
                vod.put("vod_id", ids[i]);
                vod.put("vod_name", names[i]);
                vod.put("vod_pic", "");
                JSONArray list = new JSONArray();
                list.put(vod);
                JSONObject result = new JSONObject();
                result.put("list", list);
                return result.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void runOnUiThread(Activity activity, Runnable runnable) {
        if (activity == null || activity.isFinishing()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            new Handler(Looper.getMainLooper()).post(runnable);
        }
    }

    private static JSONObject createClass(String id, String name) throws Exception {
        JSONObject cls = new JSONObject();
        cls.put("type_id", id);
        cls.put("type_name", name);
        return cls;
    }

    private static JSONObject createActionVod(String id, String name, String pic, String remark) throws Exception {
        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", pic);
        vod.put("vod_remarks", remark);
        vod.put("action", id);
        return vod;
    }
}
