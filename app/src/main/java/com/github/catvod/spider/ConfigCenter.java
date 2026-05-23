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
    private static final String SECTION_SITES = "sites";
    private static final String SECTION_CLOUD = "cloud";

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            classes.put(createClass(SECTION_DANMAKU, "弹幕配置"));
            classes.put(createClass(SECTION_SITES, "站点配置"));
            classes.put(createClass(SECTION_CLOUD, "网盘登录"));

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
                case SECTION_SITES:
                    buildSitesSection(list);
                    break;
                case SECTION_CLOUD:
                    buildCloudSection(list);
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

        list.put(createVod("config", "弹幕配置", "", "配置弹幕API地址和推送参数"));
        list.put(createVod("auto_push", "自动推送弹幕", "",
                config.isAutoPushEnabled() ? "已开启" : "已关闭"));
        list.put(createVod("lp_config", "布局配置", "", "调整弹窗大小和透明度"));
        list.put(createVod("log", "查看日志", "", "运行日志与Hook诊断"));
    }

    private void buildSitesSection(JSONArray list) throws Exception {
        list.put(createVod("sites_placeholder", "站点配置", "",
                "后续版本支持：Cookie管理、请求头配置等"));
    }

    private void buildCloudSection(JSONArray list) throws Exception {
        list.put(createVod("cloud_placeholder", "网盘登录", "",
                "后续版本支持：阿里云盘、WebDAV 等登录配置"));
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        final String id = ids.get(0);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            final Activity ctx = Utils.getTopActivity();
            if (ctx == null || ctx.isFinishing()) return;
            ctx.runOnUiThread(() -> {
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
                        default:
                            Utils.safeShowToast(ctx, "功能开发中");
                            break;
                    }
                } catch (Exception e) {
                    Leodanmu.log("ConfigCenter 详情异常: " + e.getMessage());
                }
            });
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

    private String getItemName(String id) {
        switch (id) {
            case "config": return "弹幕配置";
            case "auto_push": return "自动推送弹幕";
            case "lp_config": return "布局配置";
            case "log": return "查看日志";
            default: return "配置中心";
        }
    }

    private String getItemRemark(String id, DanmakuConfig config) {
        switch (id) {
            case "config": return "配置弹幕API地址、推送参数";
            case "auto_push": return config.isAutoPushEnabled() ? "已开启" : "已关闭";
            case "lp_config": return "调整弹窗大小和透明度";
            case "log": return "查看运行日志与Hook诊断";
            default: return "";
        }
    }

    private static JSONObject createClass(String id, String name) throws Exception {
        JSONObject cls = new JSONObject();
        cls.put("type_id", id);
        cls.put("type_name", name);
        return cls;
    }

    private static JSONObject createVod(String id, String name, String pic, String remark) throws Exception {
        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", pic);
        vod.put("vod_remarks", remark);
        return vod;
    }
}
