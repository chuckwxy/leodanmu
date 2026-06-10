package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Pan115DriveResolver implements CloudDrive {

    private static final String API = "https://webapi.115.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SHARE_PATTERN = Pattern.compile("115\\.com/(?:s/)?([a-zA-Z0-9]+)");

    private final String cookie;

    public Pan115DriveResolver(String cookie) {
        this.cookie = cookie != null ? cookie : "";
    }

    @Override
    public String getKey() { return "a115"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("115.com");
    }

    private void transferToDrive(String shareCode, String fid) throws Exception {
        if (TextUtils.isEmpty(cookie)) return;

        Map<String, String> headers = buildHeaders();
        JSONObject body = new JSONObject();
        body.put("share_code", shareCode);
        body.put("file_id", fid);

        JSONObject resp = OkHttp.postJson(API + "/share/save", body.toString(), headers);
        if (resp == null) throw new Exception("115 save response null");
        if (resp.optInt("state", 0) != 1) {
            String msg = resp.optString("message", resp.optString("error", "unknown"));
            SpiderDebug.log("115 transferToDrive: " + msg);
            if (resp.optInt("state", 0) != 0) throw new Exception("save failed: " + msg);
        }

        DriveManager.cleanupRegistry.scheduleDelete("a115", fid);
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            Matcher m = SHARE_PATTERN.matcher(url);
            if (!m.find()) return null;
            String shareCode = m.group(1);

            Map<String, String> headers = buildHeaders();
            JSONObject snap = OkHttp.postJson(API + "/share/snap?share_code=" + shareCode, "", headers);
            if (snap == null) return null;
            if (snap.optInt("state", -1) != 1) return null;

            String title = snap.optString("share_title", snap.optString("title", ""));
            JSONArray data = snap.optJSONArray("data");
            if (data == null || data.length() == 0) return null;

            JSONObject first = data.optJSONObject(0);
            String fid = first.optString("fid", first.optString("file_id", ""));
            String fileName = first.optString("n", first.optString("name", ""));

            try {
                transferToDrive(shareCode, fid);
            } catch (Exception e) {
                SpiderDebug.log("115 transfer (non-fatal): " + e.getMessage());
            }

            JSONObject dlBody = new JSONObject();
            dlBody.put("pickcode", fid);
            JSONObject dlResp = OkHttp.postJson(API + "/files/download", dlBody.toString(), headers);
            String downloadUrl = "";
            if (dlResp != null) {
                JSONObject dlData = dlResp.optJSONObject("data");
                if (dlData != null) downloadUrl = dlData.optString("file_url", "");
            }

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "115\u7F51\u76D8\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "a115");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("115 getVod error: " + e.getMessage());
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
            respHeaders.put("Referer", "https://115.com/");
            respHeaders.put("User-Agent", UA);
            if (!TextUtils.isEmpty(cookie)) respHeaders.put("Cookie", cookie);
            result.put("header", respHeaders);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public JSONObject generateQRCode() throws Exception {
        return DriveQRCodeUtil.generate115QR();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        return DriveQRCodeUtil.check115Status(queryToken);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", "https://115.com/");
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        return headers;
    }
}
