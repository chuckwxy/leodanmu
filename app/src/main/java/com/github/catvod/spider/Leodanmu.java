package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.spider.entity.DanmakuItem;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class Leodanmu extends Spider {

    public static String apiUrl = "";
    private static boolean initialized = false;
    private static File sCacheDir = null;
    private static WebServer webServer;

    // ext 懒加载缓存，兼容直播等不调用 init() 的客户端
    private static String cachedExt = null;
    private static int cachedExtHash = 0;
    private static boolean configLoaded = false;
    private static final Object CONFIG_LOCK = new Object();
    private static String lastAppliedExt = null;
    private static int lastAppliedExtHash = 0;

    // Hook 诊断状态
    private static String hookLastStage = "idle";
    private static String hookLastSource = "none";
    private static String hookLastClass = "";
    private static String hookLastMethod = "";
    private static String hookLastExtPreview = "";
    private static String hookLastError = "";

    // 日志
    private static final List<String> logBuffer = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG_SIZE = 1000;

    /**
     * 添加一个时间戳变量来防止 Leo弹幕 按钮快速连续点击：
     */
    public static long lastButtonClickTime = 0;// 在 DanmakuSpider 类中添加自动推送状态变量

    // 注意：autoPushEnabled 已移除，统一使用 DanmakuConfig

    // 添加保存和加载自动推送状态的方法（已移除，由 DanmakuConfig 管理）

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        // log("init enter: ctx=" + (context == null ? "null" : context.getClass().getName())
        //         + ", extendEmpty=" + TextUtils.isEmpty(extend)
        //         + ", extendHash=" + getExtHash(extend));
        updateHookStatus("init", "enter", "", "", extend, "");
        if (TextUtils.isEmpty(extend) && TextUtils.isEmpty(cachedExt)) {
            try {
                String fetchedExt = ExtFetcher.fetchExtFromSubscription(context);
                if (!TextUtils.isEmpty(fetchedExt)) {
                    extend = fetchedExt;
                    cachedExt = fetchedExt;
                    cachedExtHash = fetchedExt.hashCode();
                    configLoaded = false;
                    applyExtIfNeeded(context, fetchedExt, "init");
                    log("init: 从订阅配置补到ext成功");
                    updateHookStatus("init", ExtFetcher.getLastSource(), ExtFetcher.getLastClassName(), ExtFetcher.getLastMethodName(), fetchedExt, "");
                } else {
                    log("init: ext为空，未从订阅配置补到ext，回落到缓存/已保存配置");
                    updateHookStatus("init", ExtFetcher.getLastSource(), ExtFetcher.getLastClassName(), ExtFetcher.getLastMethodName(), "", ExtFetcher.getLastError());
                }
            } catch (Exception e) {
                log("init: ExtFetcher异常: " + e.getMessage());
                updateHookStatus("init", "exception", "", "", "", e.getMessage());
            }
        }

        if (TextUtils.isEmpty(extend)) {
            extend = cachedExt;
        }

        // 缓存 ext，供不调用 init() 的客户端（直播等）后续使用
        if (!TextUtils.isEmpty(extend)) {
            cachedExt = extend;
            cachedExtHash = extend.hashCode();
            configLoaded = false; // 有新 ext，强制重新加载配置
        }
        doInitWork(context, extend);
    }

    /**
     * 确保配置已加载，任何接口入口都应调用此方法。
     * 兼容直播等不调用 init() 的客户端。
     */
    private static void ensureConfig(Context context) {
        if (configLoaded) return;
        synchronized (CONFIG_LOCK) {
            if (configLoaded) return;
            try {
                if (context != null) {
                    DanmakuConfig config = DanmakuConfigManager.getConfig(context);
                    if (config == null) config = new DanmakuConfig();

                    boolean hasLocalApiUrls = config.getApiUrls() != null && !config.getApiUrls().isEmpty();
                    if (!hasLocalApiUrls) {
                        log("ensureConfig: 本地 apiUrls 为空，尝试用 cachedExt/订阅 ext 补配置");

                        String extToApply = cachedExt;
                        if (TextUtils.isEmpty(extToApply)) {
                            extToApply = ExtFetcher.fetchExtFromSubscription(context);
                        }
                        if (TextUtils.isEmpty(extToApply)) {
                            extToApply = ExtFetcher.fetchExtFromOkJson(context);
                        }

                        if (!TextUtils.isEmpty(extToApply)) {
                            applyExtIfNeeded(context, extToApply, "ensureConfig");
                            DanmakuConfig reloaded = DanmakuConfigManager.loadConfig(context);
                            log("ensureConfig: 补配置后重新读取, apiUrls=" + reloaded.getApiUrls());
                        } else {
                            log("ensureConfig: 未拿到可用于补配置的 ext");
                        }
                    } else {
                        log("ensureConfig: 使用已保存配置, apiUrls=" + config.getApiUrls()
                                + ", autoPush=" + config.isAutoPushEnabled()
                                + ", pushToast=" + config.isPushToastEnabled()
                                + ", theme=" + config.getTheme()
                                + ", lpWidth=" + config.getLpWidth()
                                + ", lpHeight=" + config.getLpHeight()
                                + ", lpAlpha=" + config.getLpAlpha());
                    }
                } else {
                    log("ensureConfig: context为空，跳过配置预热");
                }
            } catch (Exception e) {
                log("ensureConfig 异常: " + e.getMessage());
            }
            configLoaded = true;
        }
    }

    public static void tryAutoPrefetchExt(Context context, String stage) {
        // TV 端性能优先：先停用自动预取，避免重复反射、JSON解析、SharedPreferences读写
        // 如后续确认某些客户端确实不走 init()，再恢复为异步预取版本
    }

    public static void clearCache(Context context) {
        // 清空缓存文件
        File cacheDir = new File(context.getCacheDir(), "leo_danmaku_cache");
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }

        DanmakuScanner.lastDetectedTitle = "";
        Leodanmu.resetAutoSearch();

        log("缓存已清空，服务已停止");

    }


    public static synchronized void doInitWork(Context context, String extend) {
        Utils.initAppContext(context);
        // log("doInitWork enter: initialized=" + initialized
        //         + ", ctx=" + (context == null ? "null" : context.getClass().getName())
        //         + ", extendEmpty=" + TextUtils.isEmpty(extend)
        //         + ", extendHash=" + getExtHash(extend));
        // 初始化缓存目录
        sCacheDir = new File(context.getCacheDir(), "leo_danmaku_cache");
        if (!sCacheDir.exists()) sCacheDir.mkdirs();

        // 初始化配置缓存，不再在这里重复应用 ext，避免 TV 端重复解析/落盘
        DanmakuConfig config = DanmakuConfigManager.getConfig(context);

        if (initialized) return;

        // 启动WebServer
        try {
            webServer = new WebServer(9888);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 显示启动提示
        Activity act = Utils.getTopActivity();
        if (act != null) {
            // log("init toast about to show: activity=" + act.getClass().getName());
            Utils.safeRunOnUiThread(act, new Runnable() {
                @Override
                public void run() {
                    Utils.safeShowToast(act, "Leo弹幕加载成功");
                    // log("init toast shown");
                }
            });
        }

        log("Leo弹幕插件，初始化完成");
        initialized = true;
    }

    // 重置自动搜索状态
    public static void resetAutoSearch() {
        DanmakuManager.resetAutoSearch();
    }

    // 记录弹幕URL
    public static void recordDanmakuUrl(DanmakuItem danmakuItem, boolean isAuto) {
        DanmakuManager.recordDanmakuUrl(danmakuItem, isAuto);
    }

    // 获取下一个弹幕ID
    public static DanmakuItem getNextDanmakuItem(int currentEpisodeNum, int newEpisodeNum) {
        return DanmakuManager.getNextDanmakuItem(currentEpisodeNum, newEpisodeNum);
    }

    // 日志记录
    public static void log(String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        String newLogEntry = time + " " + Thread.currentThread().getName() + " " + msg;

        SpiderDebug.log(newLogEntry);

        // 检查最后一条日志是否与当前消息相同，如果相同则不添加
        if (!logBuffer.isEmpty()) {
            String lastLogEntry = logBuffer.get(logBuffer.size() - 1);
            // 提取最后一条日志的消息部分进行比较（去掉时间和线程名）
            // 查找第一个和第二个空格的位置
            int firstSpaceIndex = lastLogEntry.indexOf(' ');
            if (firstSpaceIndex != -1) {
                int secondSpaceIndex = lastLogEntry.indexOf(' ', firstSpaceIndex + 1);
                if (secondSpaceIndex != -1) {
                    String lastMsg = lastLogEntry.substring(secondSpaceIndex + 1);
                    if (lastMsg.equals(msg)) {
                        return; // 如果消息相同，则直接返回，不添加到日志缓冲区
                    }
                }
            }
        }

        logBuffer.add(newLogEntry);
        if (logBuffer.size() > MAX_LOG_SIZE) {
            logBuffer.remove(0);
        }
    }

    private static String getExtHash(String extend) {
        if (TextUtils.isEmpty(extend)) return "null";
        return String.valueOf(extend.hashCode());
    }

    public static String getLogContent() {
        StringBuilder sb = new StringBuilder();
        sb.append(getHookDebugContent()).append("\n");
        for (String s : logBuffer) sb.append(s).append("\n");
        return sb.toString();
    }

    public static String getHookDebugContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Hook诊断 ===\n");
        sb.append("阶段: ").append(TextUtils.isEmpty(hookLastStage) ? "idle" : hookLastStage).append("\n");
        sb.append("来源: ").append(TextUtils.isEmpty(hookLastSource) ? "none" : hookLastSource).append("\n");
        sb.append("类: ").append(TextUtils.isEmpty(hookLastClass) ? "-" : hookLastClass).append("\n");
        sb.append("方法: ").append(TextUtils.isEmpty(hookLastMethod) ? "-" : hookLastMethod).append("\n");
        sb.append("ext预览: ").append(TextUtils.isEmpty(hookLastExtPreview) ? "-" : hookLastExtPreview).append("\n");
        sb.append("错误: ").append(TextUtils.isEmpty(hookLastError) ? "-" : hookLastError).append("\n\n");
        sb.append("=== Hook流程 ===\n");
        sb.append(ExtFetcher.getTraceLog());
        return sb.toString();
    }

    public static void clearLogs() {
        logBuffer.clear();
    }

    public static void saveFetchedExtToConfig(Context context, String fetchedExt, String stage) {
        applyExtIfNeeded(context, fetchedExt, stage);
    }

    private static void applyExtIfNeeded(Context context, String ext, String stage) {
        if (context == null || TextUtils.isEmpty(ext)) return;
        int extHash = ext.hashCode();
        if (!TextUtils.isEmpty(lastAppliedExt) && lastAppliedExtHash == extHash && TextUtils.equals(lastAppliedExt, ext)) {
            log(stage + ": ext already applied, skip");
            return;
        }
        try {
            DanmakuConfig config = DanmakuConfigManager.getConfig(context);
            if (config == null) config = new DanmakuConfig();
            boolean changed = false;
            if (ext.startsWith("{")) {
                JSONObject jsonObject = new JSONObject(ext);
                if (jsonObject.has("apiUrls")) {
                    Object urlsObj = jsonObject.opt("apiUrls");
                    java.util.Set<String> newUrls = new java.util.HashSet<>();
                    if (urlsObj instanceof JSONArray) {
                        JSONArray arr = (JSONArray) urlsObj;
                        for (int i = 0; i < arr.length(); i++) {
                            String url = arr.optString(i, "").trim();
                            if (!TextUtils.isEmpty(url)) newUrls.add(url);
                        }
                    } else {
                        String url = jsonObject.optString("apiUrls", "").trim();
                        if (!TextUtils.isEmpty(url)) newUrls.add(url);
                    }
                    if (!newUrls.isEmpty() && !newUrls.equals(config.getApiUrls())) {
                        config.setApiUrls(newUrls);
                        changed = true;
                        log(stage + ": save apiUrls=" + newUrls);
                    }
                }
                if (jsonObject.has("autoPushEnabled")) {
                    boolean autoPush = jsonObject.optBoolean("autoPushEnabled");
                    if (config.isAutoPushEnabled() != autoPush) {
                        config.setAutoPushEnabled(autoPush);
                        changed = true;
                        log(stage + ": save autoPushEnabled=" + config.isAutoPushEnabled());
                    }
                }
                if (jsonObject.has("pushToastEnabled")) {
                    boolean pushToast = jsonObject.optBoolean("pushToastEnabled");
                    if (config.isPushToastEnabled() != pushToast) {
                        config.setPushToastEnabled(pushToast);
                        changed = true;
                        log(stage + ": save pushToastEnabled=" + config.isPushToastEnabled());
                    }
                }
                if (jsonObject.has("theme")) {
                    int theme = jsonObject.optInt("theme");
                    if (config.getTheme() != theme) {
                        config.setTheme(theme);
                        changed = true;
                        log(stage + ": save theme=" + config.getTheme());
                    }
                }
                if (jsonObject.has("lpWidth")) {
                    float lpWidth = (float) jsonObject.optDouble("lpWidth", config.getLpWidth());
                    if (Float.compare(config.getLpWidth(), lpWidth) != 0) {
                        config.setLpWidth(lpWidth);
                        changed = true;
                        log(stage + ": save lpWidth=" + config.getLpWidth());
                    }
                }
                if (jsonObject.has("lpHeight")) {
                    float lpHeight = (float) jsonObject.optDouble("lpHeight", config.getLpHeight());
                    if (Float.compare(config.getLpHeight(), lpHeight) != 0) {
                        config.setLpHeight(lpHeight);
                        changed = true;
                        log(stage + ": save lpHeight=" + config.getLpHeight());
                    }
                }
                if (jsonObject.has("lpAlpha")) {
                    float lpAlpha = (float) jsonObject.optDouble("lpAlpha", config.getLpAlpha());
                    if (Float.compare(config.getLpAlpha(), lpAlpha) != 0) {
                        config.setLpAlpha(lpAlpha);
                        changed = true;
                        log(stage + ": save lpAlpha=" + config.getLpAlpha());
                    }
                }
            } else if (ext.startsWith("http")) {
                java.util.Set<String> mergedUrls = new java.util.HashSet<>(config.getApiUrls());
                for (String url : ext.split(",")) {
                    String trimmed = url.trim();
                    if (!TextUtils.isEmpty(trimmed)) mergedUrls.add(trimmed);
                }
                if (!mergedUrls.isEmpty() && !mergedUrls.equals(config.getApiUrls())) {
                    config.setApiUrls(mergedUrls);
                    changed = true;
                    log(stage + ": save apiUrls=" + mergedUrls);
                }
            }

            if (changed) {
                log(stage + ": save前 apiUrls=" + config.getApiUrls());
                DanmakuConfigManager.saveConfig(context, config);
                DanmakuConfig reloaded = DanmakuConfigManager.loadConfig(context);
                log(stage + ": save后 reload apiUrls=" + (reloaded == null ? "null" : reloaded.getApiUrls()));
            } else {
                DanmakuConfig current = DanmakuConfigManager.getConfig(context);
                log(stage + ": ext unchanged, current apiUrls=" + (current == null ? "null" : current.getApiUrls()));
            }
            lastAppliedExt = ext;
            lastAppliedExtHash = extHash;
        } catch (Exception e) {
            log(stage + ": ext自动保存失败: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    // TVBox接口
    @Override
    public String homeContent(boolean filter) {
        ensureConfig(Utils.getTopActivity());
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
        ensureConfig(Utils.getTopActivity());
        try {
            Activity activity = Utils.getTopActivity();
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();

            // 创建弹幕配置按钮
            JSONObject configVod = createVod("config", "弹幕配置", "", "配置弹幕API");
            list.put(configVod);

            // 创建自动推送弹幕按钮（保持开启状态）
            JSONObject autoPushVod = createVod("auto_push", "自动推送弹幕", "",
                    config.isAutoPushEnabled() ? "已开启" : "已关闭");
            list.put(autoPushVod);

            // 创建查看日志按钮
            JSONObject logVod = createVod("log", "查看日志", "", "调试信息");
            list.put(logVod);

            // 创建 Hook 诊断按钮
            JSONObject hookDiagVod = createVod("hook_diag", "Hook诊断", "", getHookStatusSummary());
            list.put(hookDiagVod);

            // 创建布局配置按钮
            JSONObject lpConfigVod = createVod("lp_config", "布局配置", "", "调整弹窗大小和透明度");
            list.put(lpConfigVod);

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
        ensureConfig(Utils.getTopActivity());
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
                                    // 切换自动推送状态
                                    config.setAutoPushEnabled(!config.isAutoPushEnabled());
                                    DanmakuConfigManager.saveConfig(ctx, config);

                                    // 更新UI显示
                                    Leodanmu.log("自动推送状态切换: " + config.isAutoPushEnabled());
                                    Utils.safeShowToast(ctx,
                                            config.isAutoPushEnabled() ? "自动推送已开启" : "自动推送已关闭");

                                    // 重新加载页面以更新状态显示
                                    refreshCategoryContent(ctx);
                                } else if (id.equals("lp_config")) {
                                    DanmakuUIHelper.showLpConfigDialog(ctx);
                                } else if (id.equals("log")) {
                                    DanmakuUIHelper.showLogDialog(ctx);
                                } else if (id.equals("hook_diag")) {
                                    Utils.safeShowToast(ctx, getHookStatusDetail());
                                }
                            } catch (Exception e) {
                                Leodanmu.log("显示对话框失败: " + e.getMessage());
                                Utils.safeShowToast(ctx,
                                        "请稍后再试");
                            }
                        }
                    });
                }
            }
        }, 100); // 延迟100ms，确保Activity稳定

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
                    id.equals("hook_diag") ? getHookStatusSummary() : "请稍候...");
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

    // 添加刷新分类内容的方法
    private void refreshCategoryContent(Activity ctx) {
        try {
            String content = categoryContent("", "", false, new HashMap<>());
            if (!TextUtils.isEmpty(content)) {
                JSONObject result = new JSONObject(content);
                JSONArray list = result.getJSONArray("list");
                DanmakuConfig config = DanmakuConfigManager.getConfig(ctx);

                // 找到自动推送按钮并更新其remark
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    if ("auto_push".equals(item.getString("vod_id"))) {
                        item.put("vod_remarks", config.isAutoPushEnabled() ? "已开启" : "已关闭");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Leodanmu.log("刷新分类内容失败: " + e.getMessage());
        }
    }


    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        ensureConfig(Utils.getTopActivity());
        return "";
    }

    @Override
    public String liveContent(String url) throws Exception {
        // 直播接口：JarLoader 缓存 Spider 实例，init() 只在第一次创建时调用一次。
        // 若第一次创建时 ext 为空（顶层 spider 字段加载），apiUrls 不会被初始化。
        // 必须每次从 SharedPreferences 强制重读，不能依赖 cachedExt 或内存缓存。
        Activity context = Utils.getTopActivity();
        if (context != null) {
            DanmakuConfig freshConfig = DanmakuConfigManager.loadConfig(context);
            DanmakuConfigManager.saveConfig(context, freshConfig);
            log("liveContent: 配置已刷新，当前 apiUrls=" + freshConfig.getApiUrls());
        }
        return super.liveContent(url);
    }

    private static String getHookStatusSummary() {
        String source = TextUtils.isEmpty(hookLastSource) ? "none" : hookLastSource;
        String stage = TextUtils.isEmpty(hookLastStage) ? "idle" : hookLastStage;
        return "阶段:" + stage + " 来源:" + source;
    }

    private static String getHookStatusDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append("阶段: ").append(TextUtils.isEmpty(hookLastStage) ? "idle" : hookLastStage);
        sb.append("\n来源: ").append(TextUtils.isEmpty(hookLastSource) ? "none" : hookLastSource);
        sb.append("\n类: ").append(TextUtils.isEmpty(hookLastClass) ? "-" : hookLastClass);
        sb.append("\n方法: ").append(TextUtils.isEmpty(hookLastMethod) ? "-" : hookLastMethod);
        sb.append("\next预览: ").append(TextUtils.isEmpty(hookLastExtPreview) ? "-" : hookLastExtPreview);
        sb.append("\n错误: ").append(TextUtils.isEmpty(hookLastError) ? "-" : hookLastError);
        return sb.toString();
    }

    public static void updateHookStatus(String stage, String source, String className, String methodName, String ext, String error) {
        hookLastStage = stage;
        hookLastSource = source;
        hookLastClass = className;
        hookLastMethod = methodName;
        hookLastExtPreview = TextUtils.isEmpty(ext) ? "" : ext.substring(0, Math.min(ext.length(), 260));
        hookLastError = error == null ? "" : error;
        log("hook状态: stage=" + hookLastStage + ", source=" + hookLastSource + ", class=" + hookLastClass + ", method=" + hookLastMethod + (TextUtils.isEmpty(hookLastError) ? "" : ", error=" + hookLastError));
    }

    private JSONObject createClass(String id, String name) throws Exception {
        JSONObject cls = new JSONObject();
        cls.put("type_id", id);
        cls.put("type_name", name);
        return cls;
    }

    private JSONObject createVod(String id, String name, String pic, String remark) throws Exception {
        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", pic);
        vod.put("vod_remarks", remark);
        return vod;
    }
}
