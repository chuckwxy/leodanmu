package com.github.catvod.spider;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

public class ConfigCenter extends Spider {

    private static final String SECTION_DANMAKU = "danmaku";
    private static final String SECTION_THREAD = "thread";
    private static final String SECTION_DOUBAN = "douban";

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            classes.put(createClass(SECTION_DANMAKU, "弹幕配置"));
            classes.put(createClass(SECTION_THREAD, "线程管理"));
            classes.put(createClass(SECTION_DOUBAN, "豆瓣配置"));

            result.put("class", classes);
            result.put("list", new JSONArray());
            String json = result.toString();
            Leodanmu.log("ConfigCenter homeContent: " + json);
            return json;
        } catch (Exception e) {
            Leodanmu.log("ConfigCenter homeContent异常: " + e.getMessage());
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
                case SECTION_THREAD:
                    buildThreadSection(list);
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

    private void buildThreadSection(JSONArray list) throws Exception {
        Activity activity = Utils.getTopActivity();
        DanmakuConfig config = activity != null ? DanmakuConfigManager.getConfig(activity) : new DanmakuConfig();

        String[][] sources = {{"ali", "阿里云盘"}, {"quark", "夸克云盘"}, {"uc", "UC云盘"}};
        for (String[] s : sources) {
            DanmakuConfig.SourceProxyConfig spc = config.getProxySourceConfig().get(s[0]);
            int t = spc != null ? spc.thread : 8;
            int c = spc != null ? spc.chunkSize : 256;
            list.put(createActionVod(s[0] + "_thread", s[1], "",
                    "线程 " + t + " / 分块 " + c + "KB"));
        }
    }

    private void buildDoubanSection(JSONArray list) throws Exception {
        int cacheSize = DoubanFetcher.getCacheSize();
        list.put(createActionVod("douban_cache", "豆瓣缓存", "",
                "已缓存 " + cacheSize + " 个分类，点击清除"));
        list.put(createActionVod("douban_prewarm", "豆瓣预热", "",
                "强制后台刷新所有分类缓存"));
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        final String id = ids.get(0);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                final Activity ctx = Utils.getTopActivity();
                if (ctx == null || ctx.isFinishing()) return;
                ctx.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            DanmakuConfig config = DanmakuConfigManager.getConfig(ctx);
                    switch (id) {
                        case "config":
                            DanmakuUIHelper.showCombinedConfigDialog(ctx);
                            break;
                        case "auto_push":
                            config.setAutoPushEnabled(!config.isAutoPushEnabled());
                            DanmakuConfigManager.saveConfig(ctx, config);
                            Leodanmu.log("ConfigCenter: 自动推送状态切换: " + config.isAutoPushEnabled());
                            Utils.safeShowToast(ctx,
                                    config.isAutoPushEnabled() ? "自动推送已开启" : "自动推送已关闭");
                            break;
                        case "lp_config":
                            DanmakuUIHelper.showLpConfigDialog(ctx);
                            break;
                        case "log":
                            DanmakuUIHelper.showLogDialog(ctx);
                            break;
                        case "ali_thread":
                            DanmakuUIHelper.showSourceProxyDialog(ctx, config, "ali", "阿里云盘");
                            break;
                        case "quark_thread":
                            DanmakuUIHelper.showSourceProxyDialog(ctx, config, "quark", "夸克云盘");
                            break;
                        case "uc_thread":
                            DanmakuUIHelper.showSourceProxyDialog(ctx, config, "uc", "UC云盘");
                            break;
                        case "douban_cache":
                            DoubanFetcher.clearCache();
                            Utils.safeShowToast(ctx, "豆瓣缓存已清除");
                            break;
                        case "douban_prewarm":
                            DoubanFetcher.prewarm();
                            Utils.safeShowToast(ctx, "豆瓣预热已触发");
                            break;
                        default:
                            Utils.safeShowToast(ctx, "功能开发中");
                            break;
                    }
                        } catch (Exception e) {
                            Leodanmu.log("ConfigCenter 详情异常: " + e.getMessage());
                            Utils.safeShowToast(ctx, "请稍后再试");
                        }
                    }
                });
            }
        }, 100);

        try {
            Activity activity = Utils.getTopActivity();
            DanmakuConfig config = activity != null ? DanmakuConfigManager.getConfig(activity) : new DanmakuConfig();
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", getItemName(id));
            vod.put("vod_pic", "");
            vod.put("vod_remarks", getItemRemark(id, config));
            vod.put("vod_play_url", "");
            vod.put("vod_play_from", "");
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            list.put(vod);
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
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
            String[] ids = {"config", "auto_push", "lp_config", "log", "ali_thread", "quark_thread", "uc_thread"};
            String[] names = {"弹幕配置", "自动推送弹幕", "布局配置", "查看日志", "阿里云盘", "夸克云盘", "UC云盘"};
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

    @Override
    public String action(String action) {
        List<String> ids = new java.util.ArrayList<>();
        ids.add(action);
        return detailContent(ids);
    }

    private String getItemName(String id) {
        switch (id) {
            case "config": return "弹幕配置";
            case "auto_push": return "自动推送弹幕";
            case "lp_config": return "布局配置";
            case "log": return "查看日志";
            case "ali_thread": return "阿里云盘";
            case "quark_thread": return "夸克云盘";
            case "uc_thread": return "UC云盘";
            case "douban_cache": return "豆瓣缓存";
            case "douban_prewarm": return "豆瓣预热";
            default: return "配置中心";
        }
    }

    private String getItemRemark(String id, DanmakuConfig config) {
        switch (id) {
            case "config": return "配置弹幕API地址、推送参数";
            case "auto_push": return config.isAutoPushEnabled() ? "已开启" : "已关闭";
            case "lp_config": return "调整弹窗大小和透明度";
            case "log": return "查看运行日志与Hook诊断";
            case "ali_thread": case "quark_thread": case "uc_thread": {
                DanmakuConfig.SourceProxyConfig spc = config.getProxySourceConfig().get(id.replace("_thread", ""));
                int t = spc != null ? spc.thread : 8;
                int c = spc != null ? spc.chunkSize : 256;
                return "线程 " + t + " / 分块 " + c + "KB";
            }
            case "douban_cache": return "已缓存 " + DoubanFetcher.getCacheSize() + " 个分类，点击清除";
            case "douban_prewarm": return "强制后台刷新所有分类缓存";
            default: return "";
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
