package com.github.catvod.spider;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

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
                JSONObject vod = drive.getVod(url);
                if (vod != null) {
                    SpiderDebug.log("DriveManager getVod: " + drive.getKey() + " success, title="
                            + vod.optString("vod_name", "") + " playUrl(len)=" + vod.optString("vod_play_url", "").length());
                } else {
                    SpiderDebug.log("DriveManager getVod: " + drive.getKey() + " returned null");
                }
                return vod;
            }
            SpiderDebug.log("DriveManager getVod: no drive matched for " + url);
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
                JSONObject result = drive.play(id, flag);
                if (result != null) {
                    if (!(result.opt("url") instanceof JSONArray)) {
                        String directUrl = result.optString("url", "");
                        if (!TextUtils.isEmpty(directUrl)) {
                            wrapWithDualTrack(result, drive.getKey(), directUrl);
                        }
                    }
                } else {
                    SpiderDebug.log("DriveManager play: " + drive.getKey() + " returned null");
                }
                return result;
            }
            SpiderDebug.log("DriveManager play: no drive for flag=" + flag + " id=" + id);
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

        JSONObject h = result.optJSONObject("header");
        String cookie = h != null ? h.optString("Cookie", "") : "";

        String proxyUrl = buildProxyUrl(directUrl, thread, chunkSize, cookie);

        JSONArray urls = new JSONArray();

        boolean proxyDefault = !"baidu".equals(driveKey) && !"a123".equals(driveKey);
        if (proxyDefault && TextUtils.isEmpty(cookie)) {
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

    public static String buildProxyUrl(String rawUrl, int thread, int chunkSize, String cookie) {
        try {
            String url = "http://127.0.0.1:5575/proxy?thread=" + thread + "&chunkSize=" + chunkSize
                    + "&url=" + URLEncoder.encode(rawUrl, "UTF-8");
            if (!TextUtils.isEmpty(cookie)) {
                url += "&cookie=" + URLEncoder.encode(cookie, "UTF-8");
            }
            return url;
        } catch (Exception e) {
            return rawUrl;
        }
    }

    public static final CleanupRegistry cleanupRegistry = new CleanupRegistry();

    public static class CleanupRegistry {
        private static final long DELETE_DELAY_MS = 5 * 60 * 1000;
        private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
        private final List<CleanupEntry> pending = new ArrayList<>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean running;

        private static class CleanupEntry {
            String driveKey;
            String fileId;
            String pdirFid;
            String cookie;
            long scheduledAt;
            CleanupEntry(String driveKey, String fileId, String pdirFid, String cookie) {
                this.driveKey = driveKey;
                this.fileId = fileId;
                this.pdirFid = pdirFid;
                this.cookie = cookie;
                this.scheduledAt = System.currentTimeMillis();
            }
        }

        public synchronized void scheduleDelete(String driveKey, String fileId, String cookie) {
            scheduleDelete(driveKey, fileId, "0", cookie);
        }

        public synchronized void scheduleDelete(String driveKey, String fileId, String pdirFid, String cookie) {
            pending.add(new CleanupEntry(driveKey, fileId, pdirFid, cookie));
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
                        attemptDelete(entry.driveKey, entry.fileId, entry.pdirFid, entry.cookie);
                    } catch (Exception ignored) {}
                }
            }
        };

        private void attemptDelete(String driveKey, String fileId, String pdirFid, String cookie) {
            SpiderDebug.log("Cleanup: deleting " + driveKey + "/" + fileId + " (pdir=" + pdirFid + ")");
            if (TextUtils.isEmpty(cookie)) {
                SpiderDebug.log("Cleanup: skip, no cookie for " + driveKey);
                return;
            }
            new Thread(() -> {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", UA);
                headers.put("Referer", "https://pan.quark.cn/");
                headers.put("Cookie", cookie);
                headers.put("Content-Type", "application/json");
                try {
                    JSONObject body = new JSONObject();
                    body.put("action_type", 2);
                    JSONArray filelist = new JSONArray();
                    filelist.put(fileId);
                    body.put("filelist", filelist);
                    body.put("exclude_fids", new JSONArray());
                    body.put("pdir_fid", TextUtils.isEmpty(pdirFid) ? "0" : pdirFid);
                    OkResult result = OkHttp.post("https://pan.quark.cn/1/clouddrive/file/delete?pr=ucpro&fr=pc&__dt=" + System.currentTimeMillis(),
                            body.toString(), headers);
                    String resp = result != null ? result.getBody() : "";
                    SpiderDebug.log("Cleanup: " + driveKey + " delete resp=" + resp);
                    JSONObject json = new JSONObject(resp);
                    if (json.optInt("status", -1) == 200) {
                        SpiderDebug.log("Cleanup: " + driveKey + "/" + fileId + " deleted OK");
                    } else {
                        SpiderDebug.log("Cleanup: " + driveKey + " delete failed: " + json.optString("message", resp));
                    }
                } catch (Exception e) {
                    SpiderDebug.log("Cleanup: " + driveKey + " delete error [" + e.getClass().getSimpleName() + "] " + e.getMessage());
                }
            }).start();
        }
    }
}
