package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DriveManager {

    private final Map<String, CloudDrive> drives = new LinkedHashMap<>();
    private final List<CloudDrive> driveList = new ArrayList<>();

    private static final String[] DRIVE_ORDER = {
        "quark", "baidu", "uc", "a115", "ali", "a123", "a189", "a139", "xunlei", "pikpak"
    };

    public void init(DanmakuConfig config) {
        drives.clear();
        driveList.clear();

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
                return drive.play(id, flag);
            }
        } catch (Exception e) {
            SpiderDebug.log("DriveManager play error: " + e.getMessage());
        }
        return null;
    }
}
