package com.github.catvod.spider;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;

import java.util.Set;

public class DanmakuConfigManager {
    private static final String PREFS_NAME = "danmaku_config";
    private static final String KEY_CONFIG_JSON = "config_json";

    private static final String OLD_KEY_API_URLS = "api_urls";
    private static final String OLD_KEY_LP_WIDTH = "lp_width";
    private static final String OLD_KEY_LP_HEIGHT = "lp_height";
    private static final String OLD_KEY_LP_ALPHA = "lp_alpha";
    private static final String OLD_PREFS_AUTO_PUSH = "danmaku_prefs";
    private static final String OLD_KEY_AUTO_PUSH = "auto_push_enabled";

    private static DanmakuConfig sDanmakuConfig;
    private static final Gson gson = new Gson();

    public static DanmakuConfig getConfig(Context context) {
        // 优先返回内存缓存
        if (sDanmakuConfig != null) {
            return sDanmakuConfig;
        }
        // 如果传入的 context 为 null，尝试使用全局 Application Context
        if (context == null) {
            context = Utils.getAppContext();
            if (context == null) {
                Leodanmu.log("DanmakuConfigManager: context is null and no app context, return default config");
                return new DanmakuConfig(); // 返回默认配置，避免 NPE
            }
        }
        sDanmakuConfig = loadConfig(context);
        return sDanmakuConfig;
    }

    public static DanmakuConfig loadConfig(Context context) {
        if (context == null) {
            context = Utils.getAppContext();
            if (context == null) {
                Leodanmu.log("DanmakuConfigManager.loadConfig: context为空");
                return new DanmakuConfig();
            }
        }
        Leodanmu.log("DanmakuConfigManager.loadConfig: prefsName=" + PREFS_NAME
                + ", ctx=" + context.getClass().getName()
                + ", pkg=" + context.getPackageName());
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CONFIG_JSON, null);
        Leodanmu.log("DanmakuConfigManager.loadConfig: 读取 prefs json=" + json);
        if (json != null) {
            DanmakuConfig config = gson.fromJson(json, DanmakuConfig.class);
            Leodanmu.log("DanmakuConfigManager.loadConfig: 解析后 apiUrls=" + (config == null ? "null" : config.getApiUrls()));
            return config;
        } else {
            Leodanmu.log("DanmakuConfigManager.loadConfig: prefs 无 json，走 migrateOldConfig");
            // 迁移旧配置
            return migrateOldConfig(context);
        }
    }

    public static void saveConfig(Context context, DanmakuConfig config) {
        if (context == null) {
            context = Utils.getAppContext();
            if (context == null) {
                Leodanmu.log("DanmakuConfigManager.saveConfig: context为空，无法保存");
                return;
            }
        }
        sDanmakuConfig = config;
        Leodanmu.log("DanmakuConfigManager.saveConfig: prefsName=" + PREFS_NAME
                + ", ctx=" + context.getClass().getName()
                + ", pkg=" + context.getPackageName());
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = gson.toJson(config);
        Leodanmu.log("DanmakuConfigManager.saveConfig: 即将保存 json=" + json);
        Leodanmu.log("DanmakuConfigManager.saveConfig: 保存摘要 apiUrls=" + config.getApiUrls()
                + ", autoPush=" + config.isAutoPushEnabled()
                + ", pushToast=" + config.isPushToastEnabled()
                + ", theme=" + config.getTheme()
                + ", lpWidth=" + config.getLpWidth()
                + ", lpHeight=" + config.getLpHeight()
                + ", lpAlpha=" + config.getLpAlpha());
        boolean ok = prefs.edit().putString(KEY_CONFIG_JSON, json).commit();
        Leodanmu.log("DanmakuConfigManager.saveConfig: commit=" + ok);
        String savedJson = prefs.getString(KEY_CONFIG_JSON, null);
        Leodanmu.log("DanmakuConfigManager.saveConfig: 保存后 prefs json=" + savedJson);
    }

    private static DanmakuConfig migrateOldConfig(Context context) {
        DanmakuConfig config = new DanmakuConfig();
        SharedPreferences oldPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        Set<String> apiUrls = oldPrefs.getStringSet(OLD_KEY_API_URLS, null);
        if (apiUrls != null) {
            config.apiUrls.addAll(apiUrls);
        }

        config.lpWidth = oldPrefs.getFloat(OLD_KEY_LP_WIDTH, 1.0f);
        config.lpHeight = oldPrefs.getFloat(OLD_KEY_LP_HEIGHT, 1.0f);
        config.lpAlpha = oldPrefs.getFloat(OLD_KEY_LP_ALPHA, 1.0f);

        SharedPreferences autoPushPrefs = context.getSharedPreferences(OLD_PREFS_AUTO_PUSH, Context.MODE_PRIVATE);
        config.autoPushEnabled = autoPushPrefs.getBoolean(OLD_KEY_AUTO_PUSH, false);

        saveConfig(context, config);
        oldPrefs.edit()
                .remove(OLD_KEY_API_URLS)
                .remove(OLD_KEY_LP_WIDTH)
                .remove(OLD_KEY_LP_HEIGHT)
                .remove(OLD_KEY_LP_ALPHA)
                .apply();
        autoPushPrefs.edit().remove(OLD_KEY_AUTO_PUSH).apply();

        Leodanmu.log("旧配置已成功迁移到新格式。");
        return config;
    }
}
