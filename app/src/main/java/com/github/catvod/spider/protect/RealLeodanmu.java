package com.github.catvod.spider.protect;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.spider.DanmakuConfig;
import com.github.catvod.spider.DanmakuConfigManager;
import com.github.catvod.spider.DanmakuScanner;
import com.github.catvod.spider.DanmakuUIHelper;
import com.github.catvod.spider.ExtFetcher;
import com.github.catvod.spider.Leodanmu;
import com.github.catvod.spider.Utils;
import com.github.catvod.spider.WebServer;

import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

/**
 * 第四刀：开始把可控的列表/详情/UI 承接逻辑收进 payload 主路径。
 * init/live 等更脆弱的入口暂时仍走稳定 fallback，降低风险。
 */
public class RealLeodanmu implements PayloadBridge {

    @Override
    public void init(Context context, String extend) throws Exception {
        Leodanmu.updateHookStatus("init", "enter", "", "", extend, "");
        if (TextUtils.isEmpty(extend) && TextUtils.isEmpty(Leodanmu.getCachedExtForShell())) {
            Leodanmu.log("init: 未提供 ext，已禁用自动补配置，等待前台手动保存");
            Leodanmu.updateHookStatus("init", "manual-only", "", "", "", "自动补ext已禁用");
        }

        if (TextUtils.isEmpty(extend)) {
            extend = Leodanmu.getCachedExtForShell();
        }

        Leodanmu.cacheExtForShell(extend);
        Leodanmu.tryApplyEntryExtIfLocalEmpty(context, extend, "init-entry-ext");
        Leodanmu.doInitWork(context, extend);
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            classes.put(createClass("leo_danmaku_config", "Leo弹幕设置"));
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
            Activity activity = Utils.getTopActivity();
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();

            list.put(createVod("config", "弹幕配置", "", "配置弹幕API"));
            list.put(createVod("auto_push", "自动推送弹幕", "", config.isAutoPushEnabled() ? "已开启" : "已关闭"));
            list.put(createVod("log", "查看日志", "", "调试信息"));
            list.put(createVod("hook_diag", "Hook诊断", "", Leodanmu.getHookStatusSummaryForShell()));
            list.put(createVod("lp_config", "布局配置", "", "调整弹窗大小和透明度"));

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

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        final String id = ids.get(0);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                final Activity ctx = Utils.getTopActivity();
                if (ctx != null && !ctx.isFinishing()) {
                    ctx.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                DanmakuConfig config = DanmakuConfigManager.getConfig(ctx);
                                if (id.equals("config")) {
                                    DanmakuUIHelper.showConfigDialog(ctx);
                                } else if (id.equals("auto_push")) {
                                    config.setAutoPushEnabled(!config.isAutoPushEnabled());
                                    DanmakuConfigManager.saveConfig(ctx, config);
                                    Leodanmu.log("自动推送状态切换: " + config.isAutoPushEnabled());
                                    Utils.safeShowToast(ctx, config.isAutoPushEnabled() ? "自动推送已开启" : "自动推送已关闭");
                                } else if (id.equals("lp_config")) {
                                    DanmakuUIHelper.showLpConfigDialog(ctx);
                                } else if (id.equals("log")) {
                                    DanmakuUIHelper.showLogDialog(ctx);
                                } else if (id.equals("hook_diag")) {
                                    Utils.safeShowToast(ctx, Leodanmu.getHookStatusDetailForShell());
                                }
                            } catch (Exception e) {
                                Leodanmu.log("显示对话框失败: " + e.getMessage());
                                Utils.safeShowToast(ctx, "请稍后再试");
                            }
                        }
                    });
                }
            }
        }, 100);

        try {
            Activity activity = Utils.getTopActivity();
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", id.equals("auto_push") ? "自动推送弹幕" :
                    id.equals("lp_config") ? "布局配置" :
                    id.equals("log") ? "查看日志" :
                    id.equals("hook_diag") ? "Hook诊断" : "Leo弹幕设置");
            vod.put("vod_pic", "");
            vod.put("vod_remarks", id.equals("auto_push") ?
                    (config.isAutoPushEnabled() ? "已开启" : "已关闭") :
                    id.equals("lp_config") ? "调整弹窗大小和透明度" :
                    id.equals("log") ? "查看运行日志" :
                    id.equals("hook_diag") ? Leodanmu.getHookStatusSummaryForShell() : "请稍候...");
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
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }

    @Override
    public String liveContent(String url) throws Exception {
        Activity context = Utils.getTopActivity();
        if (context != null) {
            if (!Leodanmu.hasLocalApiUrlsForShell(context) && !TextUtils.isEmpty(Leodanmu.getCachedExtForShell())) {
                Leodanmu.tryApplyEntryExtIfLocalEmpty(context, Leodanmu.getCachedExtForShell(), "liveContent-entry-ext");
            }
            DanmakuConfig freshConfig = DanmakuConfigManager.loadConfig(context);
            DanmakuConfigManager.saveConfig(context, freshConfig);
            Leodanmu.log("liveContent: 配置已刷新，当前 apiUrls=" + freshConfig.getApiUrls());
        }
        return new Leodanmu().superLiveContent(url);
    }

    @Override
    public void startHookMonitor() {
        DanmakuScanner.startHookMonitor();
    }

    @Override
    public void clearScannerLastDetectedTitle() {
        DanmakuScanner.lastDetectedTitle = "";
    }

    @Override
    public void ensureWebServer(Context context) {
        try {
            new WebServer(9888);
        } catch (IOException e) {
            Leodanmu.log("WebServer 启动失败: " + e.getMessage());
        }
    }

    @Override
    public String getExtTraceLog() {
        return ExtFetcher.getTraceLog();
    }

    @Override
    public String getLogContent() {
        return Leodanmu.getLogContent();
    }

    @Override
    public void clearLogs() {
        Leodanmu.clearLogs();
    }

    @Override
    public String getHookStatusSummary() {
        return Leodanmu.getHookStatusSummaryForShell();
    }

    @Override
    public String getHookStatusDetail() {
        return Leodanmu.getHookStatusDetailForShell();
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
