package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.spider.entity.DanmakuItem;
import com.github.catvod.net.OkHttp;
import com.github.catvod.spider.protect.PayloadBridge;
import com.github.catvod.spider.protect.ProtectedLoader;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    // 运行时配置快照：保存时刷新，平时直接读，避免高频轮询 prefs
    private static volatile boolean runtimeAutoPushEnabled = false;
    private static volatile boolean runtimePushToastEnabled = true;
    private static volatile float runtimeLpWidth = 1.0f;
    private static volatile float runtimeLpHeight = 1.0f;
    private static volatile float runtimeLpAlpha = 1.0f;

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
    private static final int MAX_SHARED_LOG_LINES = 1200;
    private static final String SHARED_LOG_FILE_NAME = "leo_danmaku_runtime.log";
    private static final Object SHARED_LOG_LOCK = new Object();

    /**
     * 添加一个时间戳变量来防止 Leo弹幕 按钮快速连续点击：
     */
    public static long lastButtonClickTime = 0;// 在 DanmakuSpider 类中添加自动推送状态变量

    // 注意：autoPushEnabled 已移除，统一使用 DanmakuConfig

    // 添加保存和加载自动推送状态的方法（已移除，由 DanmakuConfig 管理）

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        getPayloadBridge(context).init(context, extend);
    }

    @Override
    public void destroy() {
        // 插件退出时清理所有缓存
        try {
            DanmakuScanner.stopHookMonitor();
            DanmakuManager.clearPreCache();
            if (sCacheDir != null && sCacheDir.exists()) {
                File[] files = sCacheDir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
            }
            log("🧹 插件退出，所有缓存已清理");
        } catch (Exception ignored) {
        }
    }

    public static void initShellFallback(Context context, String extend) throws Exception {
        // log("init enter: ctx=" + (context == null ? "null" : context.getClass().getName())
        //         + ", extendEmpty=" + TextUtils.isEmpty(extend)
        //         + ", extendHash=" + getExtHash(extend));
        updateHookStatus("init", "enter", "", "", extend, "");
        if (TextUtils.isEmpty(extend) && TextUtils.isEmpty(cachedExt)) {
            log("init: 未提供 ext，已禁用自动补配置，等待前台手动保存");
            updateHookStatus("init", "manual-only", "", "", "", "自动补ext已禁用");
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
                    if (!hasLocalApiUrlsForShell(context) && !TextUtils.isEmpty(cachedExt)) {
                        tryApplyEntryExtIfLocalEmpty(context, cachedExt, "ensureConfig-entry-ext");
                    }

                    DanmakuConfig config = DanmakuConfigManager.getConfig(context);
                    if (config == null) config = new DanmakuConfig();

                    boolean hasLocalApiUrls = config.getApiUrls() != null && !config.getApiUrls().isEmpty();
                    if (!hasLocalApiUrls) {
                        log("ensureConfig: 本地 apiUrls 为空，当前无可用入口 ext，请前台手动保存");
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

        getPayloadBridge(context).clearScannerLastDetectedTitle();
        Leodanmu.resetAutoSearch();

        log("缓存已清空，服务已停止");

    }


    public static String getCachedExtForShell() {
        return cachedExt;
    }

    public static void cacheExtForShell(String extend) {
        if (!TextUtils.isEmpty(extend)) {
            cachedExt = extend;
            cachedExtHash = extend.hashCode();
            configLoaded = false;
        }
    }

    public static boolean hasLocalApiUrlsForShell(Context context) {
        if (context == null) return false;
        try {
            DanmakuConfig config = DanmakuConfigManager.getConfig(context);
            return config != null && config.getApiUrls() != null && !config.getApiUrls().isEmpty();
        } catch (Exception e) {
            log("hasLocalApiUrlsForShell 异常: " + e.getMessage());
            return false;
        }
    }

    public static void tryApplyEntryExtIfLocalEmpty(Context context, String extend, String stage) {
        if (context == null || TextUtils.isEmpty(extend)) return;
        if (hasLocalApiUrlsForShell(context)) {
            log(stage + ": 检测到本地已有配置，保持前台配置优先");
            return;
        }
        log(stage + ": 本地配置为空，首次使用入口 ext 落盘");
        saveFetchedExtToConfig(context, extend, stage);
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
        refreshRuntimeConfig(context, config);

        if (initialized) return;

        // 启动WebServer
        getPayloadBridge(context).ensureWebServer(context);

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
        appendSharedLog(newLogEntry);
    }

    public static String getLogContent() {
        StringBuilder sb = new StringBuilder();
        sb.append(getHookDebugContent()).append("\n");
        String sharedLog = readSharedLogContent();
        if (!TextUtils.isEmpty(sharedLog)) {
            sb.append(sharedLog);
            if (!sharedLog.endsWith("\n")) sb.append("\n");
        } else {
            for (String s : logBuffer) sb.append(s).append("\n");
        }
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
        Activity top = Utils.getTopActivity();
        if (top != null) {
            sb.append(getPayloadBridge(top).getExtTraceLog());
        } else {
            sb.append(ExtFetcher.getTraceLog());
        }
        return sb.toString();
    }

    public static void clearLogs() {
        logBuffer.clear();
        clearSharedLog();
    }

    public static void refreshRuntimeConfig(Context context) {
        refreshRuntimeConfig(context, DanmakuConfigManager.loadConfig(context));
    }

    public static void refreshRuntimeConfig(Context context, DanmakuConfig config) {
        if (config == null && context != null) {
            config = DanmakuConfigManager.loadConfig(context);
        }
        if (config == null) return;
        runtimeAutoPushEnabled = config.isAutoPushEnabled();
        runtimePushToastEnabled = config.isPushToastEnabled();
        runtimeLpWidth = config.getLpWidth();
        runtimeLpHeight = config.getLpHeight();
        runtimeLpAlpha = config.getLpAlpha();
        log("运行时配置已刷新: autoPush=" + runtimeAutoPushEnabled
                + ", pushToast=" + runtimePushToastEnabled
                + ", lpWidth=" + runtimeLpWidth
                + ", lpHeight=" + runtimeLpHeight
                + ", lpAlpha=" + runtimeLpAlpha);
    }

    public static boolean isRuntimeAutoPushEnabled() {
        return runtimeAutoPushEnabled;
    }

    public static boolean isRuntimePushToastEnabled() {
        return runtimePushToastEnabled;
    }

    public static float getRuntimeLpWidth() {
        return runtimeLpWidth;
    }

    public static float getRuntimeLpHeight() {
        return runtimeLpHeight;
    }

    public static float getRuntimeLpAlpha() {
        return runtimeLpAlpha;
    }

    private static File getSharedLogFile() {
        Context context = Utils.getAppContext();
        if (context == null) return null;
        File dir = context.getCacheDir();
        if (dir == null) return null;
        return new File(dir, SHARED_LOG_FILE_NAME);
    }

    private static void appendSharedLog(String newLogEntry) {
        synchronized (SHARED_LOG_LOCK) {
            try {
                File file = getSharedLogFile();
                if (file == null) return;
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                String existing = file.exists()
                        ? new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                        : "";
                StringBuilder combined = new StringBuilder();
                if (!TextUtils.isEmpty(existing)) combined.append(existing);
                if (combined.length() > 0 && combined.charAt(combined.length() - 1) != '\n') combined.append('\n');
                combined.append(newLogEntry).append('\n');

                String[] lines = combined.toString().split("\\n");
                int start = Math.max(0, lines.length - MAX_SHARED_LOG_LINES);
                StringBuilder trimmed = new StringBuilder();
                for (int i = start; i < lines.length; i++) {
                    String line = lines[i];
                    if (TextUtils.isEmpty(line)) continue;
                    trimmed.append(line).append('\n');
                }

                try (FileOutputStream fos = new FileOutputStream(file, false)) {
                    fos.write(trimmed.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // 共享日志失败不影响主流程
            }
        }
    }

    private static String readSharedLogContent() {
        synchronized (SHARED_LOG_LOCK) {
            try {
                File file = getSharedLogFile();
                if (file == null || !file.exists()) return "";
                return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    private static void clearSharedLog() {
        synchronized (SHARED_LOG_LOCK) {
            try {
                File file = getSharedLogFile();
                if (file != null && file.exists()) {
                    try (FileOutputStream fos = new FileOutputStream(file, false)) {
                        fos.write(new byte[0]);
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static String getExtHash(String extend) {
        if (TextUtils.isEmpty(extend)) return "null";
        return String.valueOf(extend.hashCode());
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
        return getPayloadBridge(Utils.getTopActivity()).homeContent(filter);
    }

    public static String homeContentShellFallback(boolean filter) {
        ensureConfig(Utils.getTopActivity());
        try {
            JSONObject result = new JSONObject();
            result.put("class", DoubanFetcher.getCategories());
            result.put("list", DoubanFetcher.fetchHomeList());
            result.put("filters", DoubanFetcher.getFilterConfig());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String ylhj = proxyYlhjCategory(tid, pg);
        if (ylhj != null) return ylhj;
        return getPayloadBridge(Utils.getTopActivity()).categoryContent(tid, pg, filter, extend);
    }

    public static String categoryContentShellFallback(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String ylhj = proxyYlhjCategory(tid, pg);
        if (ylhj != null) return ylhj;
        if (!DoubanFetcher.isDouban(tid)) return "";
        try {
            int page = 1;
            try { page = Integer.parseInt(pg); } catch (Exception ignored) {}
            JSONObject result = DoubanFetcher.fetchCategory(tid, page, extend);
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log("categoryContent douban error: " + e.getMessage());
            return "";
        }
    }


    @Override
    public String detailContent(List<String> ids) {
        String result = proxyYlhjDetail(ids);
        if (result != null) return result;
        String bridgeResult = getPayloadBridge(Utils.getTopActivity()).detailContent(ids);
        // 对非 YLHJ 协议的普通条目，从返回结果提取标题后追加云盘搜索源
        if (ids != null && ids.size() == 1 && !TextUtils.isEmpty(bridgeResult)) {
            String id = ids.get(0);
            if (!isYlhjId(id)) {
                try {
                    JSONObject bridgeData = new JSONObject(bridgeResult);
                    JSONArray list = bridgeData.optJSONArray("list");
                    if (list != null && list.length() > 0) {
                        String title = list.optJSONObject(0).optString("vod_name", "");
                        // 如果 bridge 返回的是占位名（未识别该 ID），尝试从 DoubanFetcher 缓存取真实片名
                        if ("Leo弹幕设置".equals(title) || TextUtils.isEmpty(title)) {
                            String cachedTitle = DoubanFetcher.getTitleById(id);
                            if (!TextUtils.isEmpty(cachedTitle)) {
                                //log("cross-site: using cached title '" + cachedTitle + "' instead of placeholder '" + title + "'");
                                title = cachedTitle;
                            }
                        }
                        if (!TextUtils.isEmpty(title)) {
                            //log("cross-site: searching for " + title);
                            String enriched = enrichWithCloudSources(bridgeResult, title);
                            if (enriched != null) {
                                //log("cross-site: enriched with cloud sources");
                                return enriched;
                            } else {
                                //log("cross-site: enrichment returned null");
                            }
                        } else {
                            //log("cross-site: no title in bridge result");
                        }
                    } else {
                        //log("cross-site: no list in bridge result");
                    }
                } catch (Exception e) {
                    log("detailContent cross-site error: " + e.getMessage());
                }
            } else {
                //log("cross-site: id is YLHJ type, skipping");
            }
        } else {
            //log("cross-site: condition not met: ids=" + (ids == null ? "null" : String.valueOf(ids.size())) + " bridgeResult=" + (TextUtils.isEmpty(bridgeResult) ? "empty" : "ok"));
        }
        return bridgeResult;
    }

    public static String detailContentShellFallback(List<String> ids) {
        ensureConfig(Utils.getTopActivity());
        if (ids == null || ids.isEmpty()) return "";
        String result = proxyYlhjDetail(ids);
        if (result != null) return result;
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

                                    Leodanmu.log("自动推送状态: " + (config.isAutoPushEnabled() ? "开启" : "关闭"));
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
            JSONObject res = new JSONObject();
            JSONArray list = new JSONArray();
            list.put(vod);
            res.put("list", list);
            return res.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String result = proxyYlhjPlay(id, flag);
        if (result != null) return result;
        return getPayloadBridge(Utils.getTopActivity()).playerContent(flag, id, vipFlags);
    }

    public static String playerContentShellFallback(String flag, String id, List<String> vipFlags) {
        ensureConfig(Utils.getTopActivity());
        String result = proxyYlhjPlay(id, flag);
        if (result != null) return result;
        return "";
    }

    @Override
    public String liveContent(String url) throws Exception {
        return getPayloadBridge(Utils.getTopActivity()).liveContent(url);
    }

    public static String liveContentShellFallback(String url) throws Exception {
        // 直播接口：JarLoader 缓存 Spider 实例，init() 只在第一次创建时调用一次。
        // 若第一次创建时 ext 为空（顶层 spider 字段加载），apiUrls 不会被初始化。
        // 必须每次从 SharedPreferences 强制重读，不能依赖 cachedExt 或内存缓存。
        Activity context = Utils.getTopActivity();
        if (context != null) {
            DanmakuConfig freshConfig = DanmakuConfigManager.loadConfig(context);
            log("liveContent: 配置已刷新，当前 apiUrls=" + freshConfig.getApiUrls());
        }
        return new Leodanmu().superLiveContent(url);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContentShellFallback(key);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContentShellFallback(key);
    }

    public static String searchContentShellFallback(String key) {
        ensureConfig(Utils.getTopActivity());
        if (TextUtils.isEmpty(key)) return "";
        try {
            String[] settingIds = {"config", "auto_push", "log", "hook_diag", "lp_config"};
            String[] settingNames = {"弹幕配置", "自动推送弹幕", "查看日志", "Hook诊断", "布局配置"};
            for (int i = 0; i < settingIds.length; i++) {
                if (!key.equals(settingNames[i])) continue;
                JSONObject vod = new JSONObject();
                vod.put("vod_id", settingIds[i]);
                vod.put("vod_name", settingNames[i]);
                vod.put("vod_pic", "");
                String remark = "";
                Activity activity = Utils.getTopActivity();
                if ("auto_push".equals(settingIds[i]) && activity != null) {
                    DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                    remark = config.isAutoPushEnabled() ? "已开启" : "已关闭";
                } else if ("hook_diag".equals(settingIds[i])) {
                    remark = getHookStatusSummary();
                } else if ("config".equals(settingIds[i])) {
                    remark = "配置弹幕API";
                } else if ("log".equals(settingIds[i])) {
                    remark = "查看运行日志";
                } else {
                    remark = "调整弹窗大小和透明度";
                }
                vod.put("vod_remarks", remark);
                JSONArray list = new JSONArray();
                list.put(vod);
                JSONObject result = new JSONObject();
                result.put("list", list);
                log("searchContent: 设置项 '" + key + "' → vod_id=" + settingIds[i]);
                return result.toString();
            }
        } catch (Exception e) {
            log("searchContent error: " + e.getMessage());
        }
        log("searchContent: '" + key + "' → 转发到外部搜索");
        return "";
    }

    private static String getHookStatusSummary() {
        String source = TextUtils.isEmpty(hookLastSource) ? "none" : hookLastSource;
        String stage = TextUtils.isEmpty(hookLastStage) ? "idle" : hookLastStage;
        return "阶段:" + stage + " 来源:" + source;
    }

    public static String getHookStatusSummaryForShell() {
        return getHookStatusSummary();
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

    public static String getHookStatusDetailForShell() {
        return getHookStatusDetail();
    }

    public static void updateHookStatus(String stage, String source, String className, String methodName, String ext, String error) {
        hookLastStage = stage;
        hookLastSource = source;
        hookLastClass = className;
        hookLastMethod = methodName;
        hookLastExtPreview = TextUtils.isEmpty(ext) ? "" : ext.substring(0, Math.min(ext.length(), 260));
        hookLastError = error == null ? "" : error;
        boolean noisyIdle = "idle".equals(hookLastStage) && "local-config".equals(hookLastSource) && TextUtils.isEmpty(hookLastError);
        if (!noisyIdle) {
            log("hook状态: stage=" + hookLastStage + ", source=" + hookLastSource + ", class=" + hookLastClass + ", method=" + hookLastMethod + (TextUtils.isEmpty(hookLastError) ? "" : ", error=" + hookLastError));
        }
    }

    public static PayloadBridge getPayloadBridge(Context context) {
        return ProtectedLoader.getBridge(context);
    }

    public String superLiveContent(String url) throws Exception {
        return super.liveContent(url);
    }

    // ─── 追更助手代理：识别 track:// 协议，转发到 77 的 API ──────────────
    private static final String YLHJ_TOKEN = "sfahefjkahskjfha";
    private static final String YLHJ_DEFAULT_HOST = "http://192.168.31.77:8160";

    public static String getYlhjToken() {
        Activity activity = Utils.getTopActivity();
        if (activity != null) {
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            String token = config.getYlhjToken();
            if (!TextUtils.isEmpty(token)) return token;
        }
        return YLHJ_TOKEN;
    }

    public static String getYlhjHost() {
        Activity activity = Utils.getTopActivity();
        if (activity != null) {
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            String host = config.getYlhjHost();
            if (!TextUtils.isEmpty(host)) return host;
        }
        return YLHJ_DEFAULT_HOST;
    }

    private static boolean isYlhjCategoryId(String id) {
        return id != null && (id.startsWith("track://") || id.startsWith("trackdrive://") || id.startsWith("tracksmart://"));
    }

    // ─── 跨站搜索：为豆瓣条目追加云盘播放源 ──────────────────────────────
    private static String enrichWithCloudSources(String bridgeResult, String title) {
        try {
            String searchUrl = getYlhjHost() + "/api/search?q=" + java.net.URLEncoder.encode(title, "UTF-8") + "&page=1";
            //log("cross-site: search URL: " + searchUrl);
            Map<String, String> headers = new HashMap<>();
            headers.put("token", YLHJ_TOKEN);
            String searchBody = OkHttp.string(searchUrl, headers);
            if (TextUtils.isEmpty(searchBody)) {
                //log("cross-site: search returned empty body");
                return null;
            }
            //log("cross-site: search body length: " + searchBody.length());

            JSONArray searchItems = new JSONArray(searchBody);
            if (searchItems.length() == 0) {
                //log("cross-site: search returned 0 items");
                return null;
            }
            //log("cross-site: search returned " + searchItems.length() + " items");

            // 遍历搜索到的云盘链接，每种网盘只取第一个 URL，构造 link:// 拉取剧集
            Map<String, JSONObject> resolved = new HashMap<>(); // key: cloudType
            for (int i = 0; i < searchItems.length() && resolved.size() < 5; i++) {
                JSONObject item = searchItems.optJSONObject(i);
                if (item == null) continue;
                String cloudUrl = item.optString("url", "");
                String cloudType = item.optString("cloudType", "");
                if (TextUtils.isEmpty(cloudUrl) || TextUtils.isEmpty(cloudType)) continue;
                if (resolved.containsKey(cloudType)) continue; // 每种网盘只取一个
                String encodedUrl = java.net.URLEncoder.encode(cloudUrl, "UTF-8");
                String linkId = "link://" + cloudType + "/" + encodedUrl + "?title=" + java.net.URLEncoder.encode(title, "UTF-8");
                String detailUrl = getYlhjHost() + "/video/ylhj_tracking?id=" + java.net.URLEncoder.encode(linkId, "UTF-8");
                //log("cross-site: fetch detail for " + cloudType);
                String detailBody = OkHttp.string(detailUrl, headers);
                if (TextUtils.isEmpty(detailBody)) {
                    //log("cross-site: detail fetch returned empty");
                    continue;
                }
                JSONObject detailData = new JSONObject(detailBody);
                JSONArray list = detailData.optJSONArray("list");
                int epCount = (list != null) ? list.length() : 0;
                //log("cross-site: detail has " + epCount + " episodes");
                if (list != null && list.length() > 0) {
                    JSONObject first = list.optJSONObject(0);
                    if (first != null) {
                        String subPlayUrl = first.optString("vod_play_url", "");
                        String subPlayFrom = first.optString("vod_play_from", "");
                        if (!TextUtils.isEmpty(subPlayUrl)) {
                            JSONObject entry = new JSONObject();
                            entry.put("cloudType", cloudType);
                            entry.put("vod_play_url", subPlayUrl);
                            entry.put("vod_play_from", TextUtils.isEmpty(subPlayFrom) ? cloudType : subPlayFrom);
                            resolved.put(cloudType, entry);
                        }
                    }
                }
            }
            if (resolved.isEmpty()) {
                //log("cross-site: no extra sources found");
                return null;
            }
            //log("cross-site: got " + resolved.size() + " cloud sources");

            // 合并到原始详情
            JSONObject bridgeData = new JSONObject(bridgeResult);
            JSONArray bridgeList = bridgeData.optJSONArray("list");
            if (bridgeList == null || bridgeList.length() == 0) return null;
            JSONObject vod = bridgeList.optJSONObject(0);
            if (vod == null) return null;

            // 更新标题为真实片名
            vod.put("vod_name", title);
            vod.put("vod_remarks", "");

            // 按 baidu → quark → 其他 顺序输出
            StringBuilder playFrom = new StringBuilder();
            StringBuilder playUrl = new StringBuilder();
            String[] priority = {"baidu", "quark"};
            for (String pt : priority) {
                JSONObject entry = resolved.remove(pt);
                if (entry == null) continue;
                if (playFrom.length() > 0) { playFrom.append("$$$"); playUrl.append("$$$"); }
                playFrom.append(entry.optString("vod_play_from", pt));
                playUrl.append(entry.optString("vod_play_url", ""));
            }
            // 剩余的（阿里云盘等）
            for (Map.Entry<String, JSONObject> e : resolved.entrySet()) {
                JSONObject entry = e.getValue();
                if (playFrom.length() > 0) { playFrom.append("$$$"); playUrl.append("$$$"); }
                playFrom.append(entry.optString("vod_play_from", e.getKey()));
                playUrl.append(entry.optString("vod_play_url", ""));
            }
            vod.put("vod_play_from", playFrom.toString());
            vod.put("vod_play_url", playUrl.toString());
            return bridgeData.toString();
        } catch (Exception e) {
            log("enrichWithCloudSources error: " + e.getMessage());
            return null;
        }
    }

    private static String proxyYlhjCategory(String tid, String pg) {
        if (!isYlhjCategoryId(tid)) return null;
        try {
            String url = getYlhjHost() + "/video/ylhj_tracking?id=" + java.net.URLEncoder.encode(tid, "UTF-8") + "&pg=" + pg;
            Map<String, String> headers = new HashMap<>();
            headers.put("token", YLHJ_TOKEN);
            String body = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(body)) return "";
            // ── 对非 folder 的可播放项写入 vod_play_url，让 TVBox 直接播放 ──
            JSONObject data = new JSONObject(body);
            JSONArray list = data.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    String tag = item.optString("vod_tag", "");
                    if ("folder".equals(tag)) continue;
                    String vid = item.optString("vod_id", "");
                    if (!vid.startsWith("link://")) continue;
                    String name = item.optString("vod_name", "");
                    if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(vid)) {
                        item.put("vod_play_url", name + "$" + vid);
                    }
                }
            }
            return data.toString();
        } catch (Exception e) {
            log("proxyYlhjCategory error: " + e.getMessage());
            return "";
        }
    }

    private static boolean isYlhjId(String id) {
        return id != null && (id.startsWith("track://") || id.startsWith("trackdrive://") || id.startsWith("tracksmart://") || id.startsWith("trackplay://") || id.startsWith("link://"));
    }

    private static String proxyYlhjDetail(List<String> ids) {
        if (ids == null || ids.isEmpty()) return null;
        String id = ids.get(0);
        if (!isYlhjId(id)) return null;
        try {
            String url = getYlhjHost() + "/video/ylhj_tracking?id=" + java.net.URLEncoder.encode(id, "UTF-8");
            Map<String, String> headers = new HashMap<>();
            headers.put("token", YLHJ_TOKEN);
            String body = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(body)) return null;
            JSONObject data = new JSONObject(body);
            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) return null;
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            log("proxyYlhjDetail error: " + e.getMessage());
            return null;
        }
    }

    private static String proxyYlhjPlay(String id, String flag) {
        // 允许任意 ID 走 YLHJ 播放代理（包括 link:// 和云盘解析后的编码剧集 ID），
        // 但避免拦截 HTTP URL（它们应走普通播放流程）
        if (TextUtils.isEmpty(id) || id.startsWith("http")) return null;
        try {
            String url = getYlhjHost() + "/video/ylhj_tracking?play=" + java.net.URLEncoder.encode(id, "UTF-8")
                    + "&flag=" + (flag != null ? java.net.URLEncoder.encode(flag, "UTF-8") : "");
            Map<String, String> headers = new HashMap<>();
            headers.put("token", YLHJ_TOKEN);
            String body = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(body)) return null;
            return body;
        } catch (Exception e) {
            log("proxyYlhjPlay error: " + e.getMessage());
            return null;
        }
    }

}
