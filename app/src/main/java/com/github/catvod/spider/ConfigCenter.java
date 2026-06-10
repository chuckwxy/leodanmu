package com.github.catvod.spider;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

public class ConfigCenter extends Spider {

    private static final String SECTION_DANMAKU = "danmaku";
    private static final String SECTION_THREAD = "thread";
    private static final String SECTION_DOUBAN = "douban";
    private static final String SECTION_NETWORK = "network";
    private static final String SECTION_DRIVE = "drive";

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            classes.put(createClass(SECTION_DANMAKU, "弹幕配置"));
            classes.put(createClass(SECTION_THREAD, "线程管理"));
            classes.put(createClass(SECTION_DOUBAN, "豆瓣配置"));
            classes.put(createClass(SECTION_NETWORK, "网络配置"));
            classes.put(createClass(SECTION_DRIVE, "网盘驱动"));

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
                case SECTION_NETWORK:
                    buildNetworkSection(list);
                    break;
                case SECTION_DRIVE:
                    buildDriveSection(list);
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

        list.put(createActionVod("auto_tune", "自动线程调优", "",
                config.isEnableAutoTune() ? "已开启" : "已关闭"));

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

    private void buildNetworkSection(JSONArray list) throws Exception {
        Activity activity = Utils.getTopActivity();
        DanmakuConfig config = activity != null ? DanmakuConfigManager.getConfig(activity) : new DanmakuConfig();
        String proxy = config.getHttpProxyUrl();
        String ylhj = config.getYlhjHost();
        String token = config.getYlhjToken();
        list.put(createActionVod("http_proxy", "HTTP代理", "",
                proxy.isEmpty() ? "未设置" : proxy));
        list.put(createActionVod("ylhj_host", "不夜地址", "",
                ylhj.isEmpty() ? "未设置" : ylhj));
        list.put(createActionVod("ylhj_token", "不夜Token", "",
                token.isEmpty() ? "未设置" : token.substring(0, Math.min(token.length(), 8)) + "..."));
    }

    private void buildDriveSection(JSONArray list) throws Exception {
        Activity activity = Utils.getTopActivity();
        DanmakuConfig config = activity != null ? DanmakuConfigManager.getConfig(activity) : new DanmakuConfig();

        Object[][] drives = {
            {"quarkCookie", "夸克云盘", config.getQuarkCookie(), "quark"},
            {"ucCookie", "UC云盘", config.getUcCookie(), "uc"},
            {"baiduCookie", "百度云盘", config.getBaiduCookie(), "baidu"},
            {"aliRefreshToken", "阿里云盘", config.getAliRefreshToken(), "ali"},
            {"pan115Cookie", "115云盘", config.getPan115Cookie(), "115"},
            {"pan123Username", "123云盘", config.getPan123Username() + " / " + (config.getPan123Password().isEmpty() ? "" : "****"), ""},
            {"xunleiUsername", "迅雷云盘", config.getXunleiUsername() + " / " + (config.getXunleiPassword().isEmpty() ? "" : "****"), ""},
            {"pikpakUsername", "Pikpak", config.getPikpakUsername() + " / " + (config.getPikpakPassword().isEmpty() ? "" : "****"), ""},
            {"tianyiAccount", "天翼云盘", config.getTianyiAccount(), ""},
            {"pansouApiUrl", "盘搜API地址", config.getPansouApiUrl(), ""},
            {"pancheckApiUrl", "盘检API地址", config.getPancheckApiUrl(), ""},
        };

        for (Object[] d : drives) {
            String field = (String) d[0];
            String name = (String) d[1];
            String val = (String) d[2];
            String scanKey = (String) d[3];
            String remark = val.isEmpty() ? "未设置，点击配置" : val.length() > 40 ? val.substring(0, 40) + "..." : val;
            list.put(createActionVod("drive_" + field, name, "", remark));
            if (!TextUtils.isEmpty(scanKey)) {
                list.put(createActionVod("drive_" + field + "_scan", "📷 " + name + " 扫码登录", "", "扫码登录"));
            }
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
                        case "auto_tune":
                            config.setEnableAutoTune(!config.isEnableAutoTune());
                            DanmakuConfigManager.saveConfig(ctx, config);
                            Leodanmu.log("ConfigCenter: 自动线程调优状态切换: " + config.isEnableAutoTune());
                            Utils.safeShowToast(ctx,
                                    config.isEnableAutoTune() ? "自动线程调优已开启" : "自动线程调优已关闭");
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
                        case "http_proxy":
                            showHttpProxyDialog(ctx, config);
                            break;
                        case "ylhj_host":
                            showYlhjHostDialog(ctx, config);
                            break;
                        case "ylhj_token":
                            showYlhjTokenDialog(ctx, config);
                            break;
                        case "drive_quarkCookie":
                            showDriveCookieDialog(ctx, config, "quarkCookie", "夸克云盘Cookie");
                            break;
                        case "drive_quarkCookie_scan":
                            showDriveScanDialog(ctx, config, "quark", "夸克云盘");
                            break;
                        case "drive_ucCookie":
                            showDriveCookieDialog(ctx, config, "ucCookie", "UC云盘Cookie");
                            break;
                        case "drive_ucCookie_scan":
                            showDriveScanDialog(ctx, config, "uc", "UC云盘");
                            break;
                        case "drive_baiduCookie":
                            showDriveCookieDialog(ctx, config, "baiduCookie", "百度云盘Cookie(BDUSS+STOKEN)");
                            break;
                        case "drive_baiduCookie_scan":
                            showDriveScanDialog(ctx, config, "baidu", "百度云盘");
                            break;
                        case "drive_aliRefreshToken":
                            showDriveCookieDialog(ctx, config, "aliRefreshToken", "阿里云盘RefreshToken");
                            break;
                        case "drive_aliRefreshToken_scan":
                            showDriveScanDialog(ctx, config, "ali", "阿里云盘");
                            break;
                        case "drive_pan115Cookie":
                            showDriveCookieDialog(ctx, config, "pan115Cookie", "115云盘Cookie");
                            break;
                        case "drive_pan115Cookie_scan":
                            showDriveScanDialog(ctx, config, "115", "115云盘");
                            break;
                        case "drive_pan123Username":
                            showDriveAccountDialog(ctx, config, "pan123Username", "pan123Password", "123云盘", "请输入手机号");
                            break;
                        case "drive_xunleiUsername":
                            showDriveAccountDialog(ctx, config, "xunleiUsername", "xunleiPassword", "迅雷云盘", "请输入手机号");
                            break;
                        case "drive_pikpakUsername":
                            showDriveAccountDialog(ctx, config, "pikpakUsername", "pikpakPassword", "Pikpak", "请输入邮箱/手机号");
                            break;
                        case "drive_tianyiAccount":
                            showDriveCookieDialog(ctx, config, "tianyiAccount", "天翼云盘Cookie");
                            break;
                        case "drive_pansouApiUrl":
                            showDriveCookieDialog(ctx, config, "pansouApiUrl", "盘搜API地址（含端口）");
                            break;
                        case "drive_pancheckApiUrl":
                            showDriveCookieDialog(ctx, config, "pancheckApiUrl", "盘检API地址（含端口）");
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
            String[] ids = {"config", "auto_push", "lp_config", "log", "auto_tune", "ali_thread", "quark_thread", "uc_thread", "http_proxy", "ylhj_host", "ylhj_token", "drive_quarkCookie", "drive_ucCookie", "drive_baiduCookie", "drive_aliRefreshToken", "drive_pan115Cookie", "drive_pan123Username", "drive_xunleiUsername", "drive_pikpakUsername", "drive_tianyiAccount", "drive_pansouApiUrl", "drive_pancheckApiUrl"};
            String[] names = {"弹幕配置", "自动推送弹幕", "布局配置", "查看日志", "自动线程调优", "阿里云盘", "夸克云盘", "UC云盘", "HTTP代理", "不夜地址", "不夜Token", "夸克云盘", "UC云盘", "百度云盘", "阿里云盘", "115云盘", "123云盘", "迅雷云盘", "Pikpak", "天翼云盘", "盘搜API地址", "盘检API地址"};
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
            case "auto_tune": return "自动线程调优";
            case "ali_thread": return "阿里云盘";
            case "quark_thread": return "夸克云盘";
            case "uc_thread": return "UC云盘";
            case "douban_cache": return "豆瓣缓存";
            case "douban_prewarm": return "豆瓣预热";
            case "http_proxy": return "HTTP代理";
            case "ylhj_host": return "不夜地址";
            case "ylhj_token": return "不夜Token";
            case "drive_quarkCookie": return "夸克云盘";
            case "drive_ucCookie": return "UC云盘";
            case "drive_baiduCookie": return "百度云盘";
            case "drive_aliRefreshToken": return "阿里云盘";
            case "drive_pan115Cookie": return "115云盘";
            case "drive_pan123Username": return "123云盘";
            case "drive_xunleiUsername": return "迅雷云盘";
            case "drive_pikpakUsername": return "Pikpak";
            case "drive_tianyiAccount": return "天翼云盘";
            case "drive_pansouApiUrl": return "盘搜API地址";
            case "drive_pancheckApiUrl": return "盘检API地址";
            default: return "配置中心";
        }
    }

    private String getItemRemark(String id, DanmakuConfig config) {
        switch (id) {
            case "config": return "配置弹幕API地址、推送参数";
            case "auto_push": return config.isAutoPushEnabled() ? "已开启" : "已关闭";
            case "lp_config": return "调整弹窗大小和透明度";
            case "log": return "查看运行日志与Hook诊断";
            case "auto_tune": return config.isEnableAutoTune() ? "已开启" : "已关闭";
            case "ali_thread": case "quark_thread": case "uc_thread": {
                DanmakuConfig.SourceProxyConfig spc = config.getProxySourceConfig().get(id.replace("_thread", ""));
                int t = spc != null ? spc.thread : 8;
                int c = spc != null ? spc.chunkSize : 256;
                return "线程 " + t + " / 分块 " + c + "KB";
            }
            case "douban_cache": return "已缓存 " + DoubanFetcher.getCacheSize() + " 个分类，点击清除";
            case "douban_prewarm": return "强制后台刷新所有分类缓存";
            case "http_proxy": return config.getHttpProxyUrl().isEmpty() ? "未设置" : config.getHttpProxyUrl();
            case "ylhj_host": return config.getYlhjHost().isEmpty() ? "未设置" : config.getYlhjHost();
            case "ylhj_token": {
                String t = config.getYlhjToken();
                return t.isEmpty() ? "未设置" : t.substring(0, Math.min(t.length(), 8)) + "...";
            }
            case "drive_quarkCookie": return summarize(config.getQuarkCookie());
            case "drive_ucCookie": return summarize(config.getUcCookie());
            case "drive_baiduCookie": return summarize(config.getBaiduCookie());
            case "drive_aliRefreshToken": return summarize(config.getAliRefreshToken());
            case "drive_pan115Cookie": return summarize(config.getPan115Cookie());
            case "drive_pan123Username": {
                String u = config.getPan123Username();
                String p = config.getPan123Password();
                return u.isEmpty() ? "未设置" : u + " / " + (p.isEmpty() ? "" : "****");
            }
            case "drive_xunleiUsername": {
                String u = config.getXunleiUsername();
                String p = config.getXunleiPassword();
                return u.isEmpty() ? "未设置" : u + " / " + (p.isEmpty() ? "" : "****");
            }
            case "drive_pikpakUsername": {
                String u = config.getPikpakUsername();
                String p = config.getPikpakPassword();
                return u.isEmpty() ? "未设置" : u + " / " + (p.isEmpty() ? "" : "****");
            }
            case "drive_tianyiAccount": return summarize(config.getTianyiAccount());
            case "drive_pansouApiUrl": return config.getPansouApiUrl().isEmpty() ? "未设置" : config.getPansouApiUrl();
            case "drive_pancheckApiUrl": return config.getPancheckApiUrl().isEmpty() ? "未设置" : config.getPancheckApiUrl();
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

    private void showRemoteInputDialog(Activity ctx, DanmakuConfig config, String fieldId, String title, String hint, String currentValue) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);

        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable outerBg = new android.graphics.drawable.GradientDrawable();
        outerBg.setColor(android.graphics.Color.WHITE);
        outerBg.setCornerRadius(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 16, ctx.getResources().getDisplayMetrics()));
        outer.setBackground(outerBg);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(0xFF333333);
        titleView.setPadding(48, 32, 48, 0);
        outer.addView(titleView);

        android.view.View separator = new android.view.View(ctx);
        android.widget.LinearLayout.LayoutParams sepParams = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        sepParams.setMargins(48, 24, 48, 24);
        separator.setLayoutParams(sepParams);
        separator.setBackgroundColor(0xFFE0E0E0);
        outer.addView(separator);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 0, 48, 32);

        EditText input = new EditText(ctx);
        input.setText(currentValue);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setPadding(0, 0, 0, 16);
        layout.addView(input);

        Button qrBtn = new Button(ctx);
        qrBtn.setText("📱 扫码输入");
        qrBtn.setTextSize(14);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = 8;
        qrBtn.setLayoutParams(btnParams);
        layout.addView(qrBtn);

        outer.addView(layout);

        // 保存/取消按钮
        android.view.View btnSep = new android.view.View(ctx);
        android.widget.LinearLayout.LayoutParams btnSepParams = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        btnSepParams.setMargins(48, 0, 48, 16);
        btnSep.setLayoutParams(btnSepParams);
        btnSep.setBackgroundColor(0xFFE0E0E0);
        outer.addView(btnSep);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(48, 0, 48, 28);

        Button cancelBtn = new Button(ctx);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(16);
        cancelBtn.setTextColor(0xFF666666);
        cancelBtn.setAllCaps(false);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, 0, 1);
        cancelParams.rightMargin = 8;
        cancelParams.height = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 44, ctx.getResources().getDisplayMetrics());
        cancelBtn.setLayoutParams(cancelParams);
        android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
        cancelBg.setCornerRadius(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics()));
        cancelBg.setStroke(1, 0xFFCCCCCC);
        cancelBg.setColor(0xFFF5F5F5);
        cancelBtn.setBackground(cancelBg);
        cancelBtn.setOnFocusChangeListener((v, hasFocus) -> {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics()));
            if (hasFocus) {
                bg.setStroke(2, 0xFF007AFF);
                bg.setColor(0xFFE8F0FE);
            } else {
                bg.setStroke(1, 0xFFCCCCCC);
                bg.setColor(0xFFF5F5F5);
            }
            v.setBackground(bg);
        });

        Button saveBtn = new Button(ctx);
        saveBtn.setText("保存");
        saveBtn.setTextSize(16);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setAllCaps(false);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, 0, 1);
        saveParams.leftMargin = 8;
        saveParams.height = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 44, ctx.getResources().getDisplayMetrics());
        saveBtn.setLayoutParams(saveParams);
        android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable();
        saveBg.setCornerRadius(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics()));
        saveBg.setColor(0xFF007AFF);
        saveBtn.setBackground(saveBg);
        saveBtn.setOnFocusChangeListener((v, hasFocus) -> {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics()));
            if (hasFocus) {
                bg.setColor(0xFF0055CC);
                bg.setStroke(2, 0xFFFFFFFF);
            } else {
                bg.setColor(0xFF007AFF);
            }
            v.setBackground(bg);
        });

        btnRow.addView(cancelBtn);
        btnRow.addView(saveBtn);
        outer.addView(btnRow);

        builder.setView(outer);

        final String capturedFieldId = fieldId;
        RemoteInputBus.ConfigCallback configCb = (f, v) -> ctx.runOnUiThread(() -> {
            if (f.equals(capturedFieldId)) {
                input.setText(v);
                String label = f.equals("ylhj_host") ? "buye host" : f.equals("ylhj_token") ? "buye token" : f;
                Utils.safeShowToast(ctx, label + "=" + v);
            }
        });

        android.app.AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> RemoteInputBus.removeConfigInput());
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            switch (fieldId) {
                case "http_proxy":
                    config.setHttpProxyUrl(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "已清除代理" : "代理已设置");
                    break;
                case "ylhj_host":
                    if (val.endsWith("/")) val = val.substring(0, val.length() - 1);
                    config.setYlhjHost(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "已恢复默认地址" : "buye host 已设置");
                    break;
                case "ylhj_token":
                    config.setYlhjToken(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "已恢复默认Token" : "buye token 已设置");
                    break;
                case "drive_quarkCookie":
                    config.setQuarkCookie(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "夸克Cookie已清除" : "夸克Cookie已设置");
                    break;
                case "drive_ucCookie":
                    config.setUcCookie(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "UC Cookie已清除" : "UC Cookie已设置");
                    break;
                case "drive_baiduCookie":
                    config.setBaiduCookie(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "百度Cookie已清除" : "百度Cookie已设置");
                    break;
                case "drive_aliRefreshToken":
                    config.setAliRefreshToken(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "阿里Token已清除" : "阿里Token已设置");
                    break;
                case "drive_pan115Cookie":
                    config.setPan115Cookie(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "115 Cookie已清除" : "115 Cookie已设置");
                    break;
                case "drive_tianyiAccount":
                    config.setTianyiAccount(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "天翼Cookie已清除" : "天翼Cookie已设置");
                    break;
                case "drive_pansouApiUrl":
                    config.setPansouApiUrl(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "盘搜API已恢复默认" : "盘搜API已设置");
                    break;
                case "drive_pancheckApiUrl":
                    config.setPancheckApiUrl(val);
                    DanmakuConfigManager.saveConfig(ctx, config);
                    Utils.safeShowToast(ctx, val.isEmpty() ? "盘检API已恢复默认" : "盘检API已设置");
                    break;
            }
            dialog.dismiss();
        });

        qrBtn.setOnClickListener(v -> {
            RemoteInputBus.onConfigInput(configCb);
            String localIp = NetworkUtils.getLocalIpAddress();
            String qrFieldId = fieldId.startsWith("drive_") ? fieldId.substring(6) : fieldId;
            String url = "http://" + localIp + ":9888/config_input?field=" + qrFieldId;
            DanmakuUIHelper.showFloatingQRCodeDialog(ctx, url, title);
        });

        dialog.show();
    }

    private void showHttpProxyDialog(Activity ctx, DanmakuConfig config) {
        showRemoteInputDialog(ctx, config, "http_proxy", "HTTP代理",
                "例如: http://192.168.31.77:7890", config.getHttpProxyUrl());
    }

    private void showYlhjHostDialog(Activity ctx, DanmakuConfig config) {
        showRemoteInputDialog(ctx, config, "ylhj_host", "不夜地址",
                "例如: http://192.168.10.10:3000", config.getYlhjHost());
    }

    private void showYlhjTokenDialog(Activity ctx, DanmakuConfig config) {
        showRemoteInputDialog(ctx, config, "ylhj_token", "不夜Token",
                "例如: admin123", config.getYlhjToken());
    }

    private String summarize(String val) {
        if (val == null || val.isEmpty()) return "未设置，点击配置";
        return val.length() > 40 ? val.substring(0, 40) + "..." : val;
    }

    private void showDriveCookieDialog(Activity ctx, DanmakuConfig config, String fieldId, String title) {
        String current = "";
        switch (fieldId) {
            case "quarkCookie": current = config.getQuarkCookie(); break;
            case "ucCookie": current = config.getUcCookie(); break;
            case "baiduCookie": current = config.getBaiduCookie(); break;
            case "aliRefreshToken": current = config.getAliRefreshToken(); break;
            case "pan115Cookie": current = config.getPan115Cookie(); break;
            case "tianyiAccount": current = config.getTianyiAccount(); break;
            case "pansouApiUrl": current = config.getPansouApiUrl(); break;
            case "pancheckApiUrl": current = config.getPancheckApiUrl(); break;
        }
        showRemoteInputDialog(ctx, config, "drive_" + fieldId, title,
                "粘贴" + title.replace("Cookie", "").replace("地址", "").trim() + "配置", current);
    }

    private void showDriveAccountDialog(Activity ctx, DanmakuConfig config, String usernameField, String passwordField, String displayName, String usernameHint) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);

        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable outerBg = new android.graphics.drawable.GradientDrawable();
        outerBg.setColor(android.graphics.Color.WHITE);
        outerBg.setCornerRadius(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 16, ctx.getResources().getDisplayMetrics()));
        outer.setBackground(outerBg);

        TextView titleView = new TextView(ctx);
        titleView.setText(displayName + " 账号配置");
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(0xFF333333);
        titleView.setPadding(48, 32, 48, 0);
        outer.addView(titleView);

        android.view.View separator = new android.view.View(ctx);
        LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        sepParams.setMargins(48, 24, 48, 24);
        separator.setLayoutParams(sepParams);
        separator.setBackgroundColor(0xFFE0E0E0);
        outer.addView(separator);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 0, 48, 32);

        EditText userInput = new EditText(ctx);
        userInput.setText(getAccountField(config, usernameField));
        userInput.setHint(usernameHint);
        userInput.setInputType(InputType.TYPE_CLASS_TEXT);
        userInput.setPadding(0, 0, 0, 16);
        layout.addView(userInput);

        EditText passInput = new EditText(ctx);
        passInput.setText(getAccountField(config, passwordField));
        passInput.setHint("密码");
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setPadding(0, 0, 0, 16);
        layout.addView(passInput);

        outer.addView(layout);

        android.view.View btnSep = new android.view.View(ctx);
        LinearLayout.LayoutParams btnSepParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        btnSepParams.setMargins(48, 0, 48, 16);
        btnSep.setLayoutParams(btnSepParams);
        btnSep.setBackgroundColor(0xFFE0E0E0);
        outer.addView(btnSep);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(48, 0, 48, 28);

        builder.setView(outer);

        final android.app.AlertDialog[] dialogRef = new android.app.AlertDialog[1];

        Button cancelBtn = new Button(ctx);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(16);
        cancelBtn.setTextColor(0xFF666666);
        cancelBtn.setAllCaps(false);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cancelParams.rightMargin = 8;
        cancelBtn.setLayoutParams(cancelParams);
        cancelBtn.setOnClickListener(v -> { if (dialogRef[0] != null) dialogRef[0].dismiss(); });

        Button saveBtn = new Button(ctx);
        saveBtn.setText("保存");
        saveBtn.setTextSize(16);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setAllCaps(false);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        saveParams.leftMargin = 8;
        saveBtn.setLayoutParams(saveParams);
        android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable();
        saveBg.setCornerRadius(8);
        saveBg.setColor(0xFF007AFF);
        saveBtn.setBackground(saveBg);

        btnRow.addView(cancelBtn);
        btnRow.addView(saveBtn);
        outer.addView(btnRow);

        android.app.AlertDialog dialog = builder.create();
        dialogRef[0] = dialog;
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        saveBtn.setOnClickListener(v -> {
            String user = userInput.getText().toString().trim();
            String pass = passInput.getText().toString().trim();
            setAccountField(config, usernameField, user);
            setAccountField(config, passwordField, pass);
            DanmakuConfigManager.saveConfig(ctx, config);
            Utils.safeShowToast(ctx, displayName + " 账号已保存");
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showDriveScanDialog(Activity ctx, DanmakuConfig config, String driveKey, String displayName) {
        JSONObject qrResult = DriveScanHelper.generateQRCode(driveKey);
        if (qrResult == null) {
            Utils.safeShowToast(ctx, displayName + " 获取二维码失败，检查不夜地址");
            return;
        }
        String queryToken = qrResult.optString("query_token", "");
        String qrImageUrl = qrResult.optString("qr_image_url", "");
        String qrText = qrResult.optString("qr_text", "");
        if (TextUtils.isEmpty(queryToken) || (TextUtils.isEmpty(qrImageUrl) && TextUtils.isEmpty(qrText))) {
            Utils.safeShowToast(ctx, displayName + " 二维码参数缺失");
            return;
        }

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(48, 32, 48, 32);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(android.graphics.Color.WHITE);
        bg.setCornerRadius(16);
        outer.setBackground(bg);

        TextView titleView = new TextView(ctx);
        titleView.setText(displayName + " 扫码登录");
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(0xFF333333);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 24);
        outer.addView(titleView);

        ImageView qrView = new ImageView(ctx);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(300, 300));
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (!TextUtils.isEmpty(qrImageUrl)) {
            android.os.AsyncTask.SERIAL_EXECUTOR.execute(() -> {
                try {
                    java.net.URL url = new java.net.URL(qrImageUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    java.io.InputStream is = conn.getInputStream();
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                    is.close();
                    ctx.runOnUiThread(() -> qrView.setImageBitmap(bmp));
                } catch (Exception ignored) {}
            });
        }
        outer.addView(qrView);

        TextView statusView = new TextView(ctx);
        statusView.setText("⏳ 等待扫码...");
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF666666);
        statusView.setGravity(android.view.Gravity.CENTER);
        statusView.setPadding(0, 16, 0, 0);
        outer.addView(statusView);

        Button cancelBtn = new Button(ctx);
        cancelBtn.setText("关闭");
        cancelBtn.setTextSize(16);
        cancelBtn.setTextColor(0xFF666666);
        cancelBtn.setAllCaps(false);
        cancelBtn.setOnClickListener(v -> {});
        outer.addView(cancelBtn);

        builder.setView(outer);
        android.app.AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        cancelBtn.setOnClickListener(v -> {
            Handler h = statusView.getHandler();
            if (h != null) h.removeCallbacksAndMessages(null);
            dialog.dismiss();
        });
        dialog.show();

        // Poll for scan status
        Handler handler = new Handler(Looper.getMainLooper());
        Handler.Callback callback = new Handler.Callback() {
            @Override
            public boolean handleMessage(android.os.Message msg) {
                if (!dialog.isShowing()) return false;
                if (msg.what == 0) {
                    android.os.AsyncTask.SERIAL_EXECUTOR.execute(() -> {
                        try {
                            JSONObject result = DriveScanHelper.checkStatus(driveKey, queryToken);
                            if (result != null) {
                                String status = result.optString("status", "");
                                ctx.runOnUiThread(() -> {
                                    if ("CONFIRMED".equals(status)) {
                                        String account = DriveScanHelper.extractAccount(result);
                                        if (!TextUtils.isEmpty(account)) {
                                            String field = getDriveFieldKey(driveKey);
                                            setDriveField(config, field, account);
                                            DanmakuConfigManager.saveConfig(ctx, config);
                                            Leodanmu.log("ConfigCenter: " + displayName + " 扫码登录成功, field=" + field);
                                            statusView.setText("✅ 登录成功");
                                            statusView.setTextColor(0xFF28a745);
                                        } else {
                                            statusView.setText("✅ 已确认，但未获取到账号信息");
                                        }
                                        dialog.dismiss();
                                        Utils.safeShowToast(ctx, displayName + " 扫码登录成功");
                                    } else if ("SCANNED".equals(status)) {
                                        statusView.setText("📱 已扫码，请在手机上确认");
                                        statusView.setTextColor(0xFFff9800);
                                        handler.sendEmptyMessageDelayed(0, 2000);
                                    } else if ("EXPIRED".equals(status) || "CANCELED".equals(status)) {
                                        statusView.setText("❌ 已过期或已取消");
                                        statusView.setTextColor(0xFFdc3545);
                                    } else {
                                        handler.sendEmptyMessageDelayed(0, 2000);
                                    }
                                });
                            } else {
                                handler.sendEmptyMessageDelayed(0, 2000);
                            }
                        } catch (Exception e) {
                            handler.sendEmptyMessageDelayed(0, 2000);
                        }
                    });
                }
                return false;
            }
        };
        handler.sendEmptyMessageDelayed(0, 2000);
    }

    private String getDriveFieldKey(String driveKey) {
        switch (driveKey) {
            case "quark": return "quarkCookie";
            case "uc": return "ucCookie";
            case "baidu": return "baiduCookie";
            case "ali": return "aliRefreshToken";
            case "115": return "pan115Cookie";
            default: return driveKey + "Cookie";
        }
    }

    private void setDriveField(DanmakuConfig config, String field, String val) {
        switch (field) {
            case "quarkCookie": config.setQuarkCookie(val); break;
            case "ucCookie": config.setUcCookie(val); break;
            case "baiduCookie": config.setBaiduCookie(val); break;
            case "aliRefreshToken": config.setAliRefreshToken(val); break;
            case "pan115Cookie": config.setPan115Cookie(val); break;
        }
    }

    private String getAccountField(DanmakuConfig config, String field) {
        switch (field) {
            case "pan123Username": return config.getPan123Username();
            case "pan123Password": return config.getPan123Password();
            case "xunleiUsername": return config.getXunleiUsername();
            case "xunleiPassword": return config.getXunleiPassword();
            case "pikpakUsername": return config.getPikpakUsername();
            case "pikpakPassword": return config.getPikpakPassword();
            default: return "";
        }
    }

    private void setAccountField(DanmakuConfig config, String field, String val) {
        switch (field) {
            case "pan123Username": config.setPan123Username(val); break;
            case "pan123Password": config.setPan123Password(val); break;
            case "xunleiUsername": config.setXunleiUsername(val); break;
            case "xunleiPassword": config.setXunleiPassword(val); break;
            case "pikpakUsername": config.setPikpakUsername(val); break;
            case "pikpakPassword": config.setPikpakPassword(val); break;
        }
    }
}
