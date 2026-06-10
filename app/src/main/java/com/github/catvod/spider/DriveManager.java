package com.github.catvod.spider;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DriveManager {

    private final Map<String, CloudDrive> drives = new LinkedHashMap<>();
    private final List<CloudDrive> driveList = new ArrayList<>();
    private DanmakuConfig config;

    private static final String[] DRIVE_ORDER = {
        "quark", "baidu", "uc", "a115", "ali", "a123", "a189", "a139", "xunlei", "pikpak"
    };

    public void init(DanmakuConfig config) {
        drives.clear();
        driveList.clear();
        this.config = config;

        if (config == null) {
            SpiderDebug.log("DriveManager: config is null");
            return;
        }

        for (String key : DRIVE_ORDER) {
            CloudDrive drive = createDrive(key, config);
            if (drive != null) {
                drives.put(key, drive);
                driveList.add(drive);
            }
        }
        SpiderDebug.log("DriveManager init: " + drives.size() + " drives loaded");
    }

    private CloudDrive createDrive(String key, DanmakuConfig config) {
        switch (key) {
            case "quark": {
                String cookie = config.getQuarkCookie();
                if (TextUtils.isEmpty(cookie)) {
                    SpiderDebug.log("DriveManager: quark skipper (no cookie)");
                    return new QuarkDriveResolver("");
                }
                return new QuarkDriveResolver(cookie);
            }
            case "baidu":
                return new BaiduDriveResolver(config.getBaiduCookie());
            case "uc":
                return new UcDriveResolver(config.getUcCookie());
            case "a115":
                return new Pan115DriveResolver(config.getPan115Cookie());
            case "ali":
                return new AliDriveResolver(config.getAliRefreshToken());
            case "a123":
                return new Pan123DriveResolver(config.getPan123Username(), config.getPan123Password());
            case "a189":
                return new TianyiDriveResolver(config.getTianyiAccount());
            case "a139":
                return new CmccDriveResolver();
            case "xunlei":
                return new XunleiDriveResolver(config.getXunleiUsername(), config.getXunleiPassword());
            case "pikpak":
                return new PikpakDriveResolver(config.getPikpakUsername(), config.getPikpakPassword());
            default:
                return null;
        }
    }

    public CloudDrive match(String url) {
        if (TextUtils.isEmpty(url)) return null;
        for (CloudDrive drive : driveList) {
            try {
                if (drive.matchShare(url)) return drive;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public CloudDrive get(String key) {
        if (key == null) key = "";
        String k = key.toLowerCase().replaceAll("[^a-z0-9]", "");
        CloudDrive exact = drives.get(k);
        if (exact != null) return exact;
        for (Map.Entry<String, CloudDrive> entry : drives.entrySet()) {
            if (k.contains(entry.getKey()) || entry.getKey().contains(k)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public List<CloudDrive> getAll() {
        return driveList;
    }

    public List<String> getKeys() {
        return new ArrayList<>(drives.keySet());
    }

    public JSONObject getVod(String url) {
        try {
            CloudDrive drive = match(url);
            if (drive != null) {
                SpiderDebug.log("DriveManager getVod: matched " + drive.getKey());
                return drive.getVod(url);
            }
        } catch (Exception e) {
            SpiderDebug.log("DriveManager getVod error: " + e.getMessage());
        }
        return null;
    }

    public JSONObject play(String flag, String id) {
        try {
            CloudDrive drive = get(flag);
            if (drive == null) {
                drive = match(id);
            }
            if (drive != null) {
                SpiderDebug.log("DriveManager play: using " + drive.getKey() + " flag=" + flag);
                JSONObject result = drive.play(id, flag);
                if (result != null && result.has("url") && !(result.opt("url") instanceof JSONArray)) {
                    String directUrl = result.optString("url", "");
                    if (!TextUtils.isEmpty(directUrl)) {
                        wrapWithDualTrack(result, drive.getKey(), directUrl);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            SpiderDebug.log("DriveManager play error: " + e.getMessage());
        }
        return null;
    }

    private void wrapWithDualTrack(JSONObject result, String driveKey, String directUrl) throws Exception {
        if (config == null) return;
        DanmakuConfig.SourceProxyConfig spc = config.getProxySourceConfig().get(driveKey);
        int thread = spc != null ? spc.thread : 8;
        int chunkSize = spc != null ? spc.chunkSize : 256;

        String proxyUrl = buildProxyUrl(directUrl, thread, chunkSize);
        JSONArray urls = new JSONArray();

        boolean proxyDefault = !"baidu".equals(driveKey) && !"a123".equals(driveKey);
        if (proxyDefault) {
            urls.put("Leo\u4EE3\u7406");
            urls.put(proxyUrl);
            urls.put("\u76F4\u8FDE");
            urls.put(directUrl);
        } else {
            urls.put("\u76F4\u8FDE");
            urls.put(directUrl);
            urls.put("Leo\u4EE3\u7406");
            urls.put(proxyUrl);
        }
        result.put("url", urls);
    }

    public static String buildProxyUrl(String rawUrl, int thread, int chunkSize) {
        try {
            return "http://127.0.0.1:5575/proxy?thread=" + thread + "&chunkSize=" + chunkSize
                    + "&url=" + URLEncoder.encode(rawUrl, "UTF-8");
        } catch (Exception e) {
            return rawUrl;
        }
    }

    public static final CleanupRegistry cleanupRegistry = new CleanupRegistry();

    public static class CleanupRegistry {
        private static final long DELETE_DELAY_MS = 5 * 60 * 1000;
        private final List<CleanupEntry> pending = new ArrayList<>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean running;

        private static class CleanupEntry {
            String driveKey;
            String fileId;
            long scheduledAt;
            CleanupEntry(String driveKey, String fileId) {
                this.driveKey = driveKey;
                this.fileId = fileId;
                this.scheduledAt = System.currentTimeMillis();
            }
        }

        public synchronized void scheduleDelete(String driveKey, String fileId) {
            pending.add(new CleanupEntry(driveKey, fileId));
            if (!running) {
                running = true;
                handler.postDelayed(cleanupRunnable, DELETE_DELAY_MS);
            }
        }

        private final Runnable cleanupRunnable = new Runnable() {
            @Override
            public void run() {
                List<CleanupEntry> toDelete;
                synchronized (CleanupRegistry.this) {
                    long now = System.currentTimeMillis();
                    toDelete = new ArrayList<>();
                    for (CleanupEntry entry : pending) {
                        if (now - entry.scheduledAt >= DELETE_DELAY_MS) {
                            toDelete.add(entry);
                        }
                    }
                    pending.removeAll(toDelete);
                    running = !pending.isEmpty();
                    if (running) {
                        handler.postDelayed(this, DELETE_DELAY_MS);
                    }
                }
                for (CleanupEntry entry : toDelete) {
                    try {
                        attemptDelete(entry.driveKey, entry.fileId);
                    } catch (Exception ignored) {}
                }
            }
        };

        private void attemptDelete(String driveKey, String fileId) {
            SpiderDebug.log("Cleanup: attempting delete " + driveKey + "/" + fileId);
        }
    }
}
