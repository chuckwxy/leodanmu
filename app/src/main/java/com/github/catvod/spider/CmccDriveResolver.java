package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CmccDriveResolver implements CloudDrive {

    private static final String API = "https://cloud.139.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SHARE_PATTERN = Pattern.compile("cloud\\.139\\.com/(?:s/)?([a-zA-Z0-9]+)");

    @Override
    public String getKey() { return "a139"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("cloud.139.com");
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            Matcher m = SHARE_PATTERN.matcher(url);
            if (!m.find()) return null;
            String shareCode = m.group(1);

            Map<String, String> headers = buildHeaders();
            JSONObject shareBody = new JSONObject();
            shareBody.put("shareCode", shareCode);
            JSONObject shareResp = OkHttp.postJson(API + "/api/share/v1/share/info", shareBody.toString(), headers);

            String title = shareResp != null ? shareResp.optString("shareName", shareResp.optString("name", "")) : "";
            String firstFileId = shareResp != null ? shareResp.optString("fileId", shareResp.optString("id", "")) : "";
            String fileName = shareResp != null ? shareResp.optString("fileName", shareResp.optString("name", "")) : "";

            String downloadUrl = "";
            if (!TextUtils.isEmpty(firstFileId)) {
                JSONObject dlBody = new JSONObject();
                dlBody.put("shareCode", shareCode);
                dlBody.put("fileId", firstFileId);
                JSONObject dlResp = OkHttp.postJson(API + "/api/share/v1/share/getDownloadUrl", dlBody.toString(), headers);
                if (dlResp != null) {
                    downloadUrl = dlResp.optString("downloadUrl", dlResp.optString("url", ""));
                }
            }

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "\u4E2D\u79FB\u4E91\u76D8\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "a139");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("CMCC getVod error: " + e.getMessage());
            return null;
        }
    }

    @Override
    public JSONObject play(String input, String flag) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", input);
            JSONObject respHeaders = new JSONObject();
            respHeaders.put("Referer", API + "/");
            respHeaders.put("User-Agent", UA);
            result.put("header", respHeaders);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", API + "/");
        headers.put("Content-Type", "application/json");
        return headers;
    }
}
