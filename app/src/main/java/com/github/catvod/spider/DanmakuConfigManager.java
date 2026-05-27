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

    private static volatile DanmakuConfig sDanmakuConfig;
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
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CONFIG_JSON, null);
        if (json != null && !json.isEmpty()) {
            try {
                DanmakuConfig config = gson.fromJson(json, DanmakuConfig.class);
                if (config != null) {
                    sDanmakuConfig = config;
                    return config;
                }
            } catch (Exception e) {
                Leodanmu.log("解析配置失败，尝试迁移旧配置: " + e.getMessage());
            }
        } else {
            return migrateOldConfig(context);
        }
        return migrateOldConfig(context);
    }

    public static void saveConfig(Context context, DanmakuConfig config) {
        if (context == null) {
            context = Utils.getAppContext();
            if (context == null) {
                Leodanmu.log("DanmakuConfigManager.saveConfig: context为空，无法保存");
                return;
            }
        }
        DanmakuConfig existing = loadConfig(context);
        if (config == null) config = new DanmakuConfig();
        if ((config.getApiUrls() == null || config.getApiUrls().isEmpty())
                && existing != null && existing.getApiUrls() != null && !existing.getApiUrls().isEmpty()) {
            config.setApiUrls(existing.getApiUrls());
        }
        sDanmakuConfig = config;
        Leodanmu.log("cfg saved: proxyThread=" + config.getProxyThread() + " chunk=" + config.getProxyChunkSize() + " autoTune=" + config.isEnableAutoTune() + " type=" + config.getProxyType());
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = gson.toJson(config);
        prefs.edit().putString(KEY_CONFIG_JSON, json).apply();
        Leodanmu.refreshRuntimeConfig(context, config);
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
        config.autoPushEnabled = autoPushPrefs.getBoolean(OLD_KEY_AUTO_PUSH, true);

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
