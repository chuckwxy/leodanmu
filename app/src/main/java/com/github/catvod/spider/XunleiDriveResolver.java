package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XunleiDriveResolver implements CloudDrive {

    @Override
    public JSONObject generateQRCode() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        throw new UnsupportedOperationException();
    }

    private static final String API = "https://pan.xunlei.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SHARE_PATTERN = Pattern.compile("pan\\.xunlei\\.com/s/([a-zA-Z0-9_-]+)");

    private final String username;
    private final String password;
    private String accessToken;

    public XunleiDriveResolver(String username, String password) {
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
    }

    @Override
    public String getKey() { return "xunlei"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("pan.xunlei.com");
    }

    private String login() throws Exception {
        if (!TextUtils.isEmpty(accessToken)) return accessToken;
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) return "";

        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        body.put("captchaToken", "");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Content-Type", "application/json");
        headers.put("Referer", API + "/");

        OkResult result = OkHttp.post(API + "/api/v1/user/signin", body.toString(), headers);
        if (result == null) return "";
        JSONObject resp = new JSONObject(result.getBody());
        JSONObject data = resp.optJSONObject("data");
        if (data != null) {
            accessToken = data.optString("accessToken", data.optString("token", ""));
        }
        return accessToken;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", API + "/");
        headers.put("Content-Type", "application/json");
        if (!TextUtils.isEmpty(accessToken)) {
            headers.put("Authorization", "Bearer " + accessToken);
        }
        return headers;
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            login();
            Leodanmu.log("Xunlei getVod: url=" + url + " accessToken empty=" + TextUtils.isEmpty(accessToken));
            Matcher m = SHARE_PATTERN.matcher(url);
            if (!m.find()) return null;
            String shareCode = m.group(1);
            Map<String, String> headers = buildHeaders();

            JSONObject shareInfo = OkHttp.postJson(API + "/api/v1/share/info?shareCode=" + shareCode, "", headers);
            String title = shareInfo != null ? shareInfo.optString("title", shareInfo.optString("shareName", "")) : "";
            JSONObject fileList = shareInfo != null ? shareInfo.optJSONObject("fileList") : null;
            String firstFileId = "";
            String fileName = "";
            if (fileList != null) {
                JSONObject first = fileList.optJSONObject("0");
                if (first != null) {
                    firstFileId = first.optString("id", first.optString("fileId", ""));
                    fileName = first.optString("name", first.optString("fileName", ""));
                }
            }
            if (TextUtils.isEmpty(firstFileId)) {
                firstFileId = shareInfo != null ? shareInfo.optString("fileId", shareInfo.optString("id", "")) : "";
                fileName = shareInfo != null ? shareInfo.optString("fileName", shareInfo.optString("name", "")) : "";
            }

            try {
                JSONObject xferBody = new JSONObject();
                xferBody.put("shareCode", shareCode);
                xferBody.put("fileId", firstFileId);
                xferBody.put("dirId", "0");
                JSONObject xferResp = OkHttp.postJson(API + "/api/v1/share/save", xferBody.toString(), headers);
                if (xferResp != null) {
                    DriveManager.cleanupRegistry.scheduleDelete("xunlei", firstFileId, "");
                }
            } catch (Exception e) {
                SpiderDebug.log("Xunlei transfer (non-fatal): " + e.getMessage());
            }

            String downloadUrl = "";
            try {
                JSONObject dlBody = new JSONObject();
                dlBody.put("shareCode", shareCode);
                dlBody.put("fileId", firstFileId);
                JSONObject dlResp = OkHttp.postJson(API + "/api/v1/share/download", dlBody.toString(), headers);
                if (dlResp != null) {
                    downloadUrl = dlResp.optString("downloadUrl", dlResp.optString("url", ""));
                }
            } catch (Exception ignored) {}

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "\u8FC5\u96F7\u7F51\u76D8\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "xunlei");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("Xunlei getVod error: " + e.getMessage());
            return null;
        }
    }

    @Override
    public JSONObject play(String input, String flag) {
        try {
            login();
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", input);
            JSONObject respHeaders = new JSONObject();
            respHeaders.put("Referer", API + "/");
            respHeaders.put("User-Agent", UA);
            if (!TextUtils.isEmpty(accessToken)) {
                respHeaders.put("Authorization", "Bearer " + accessToken);
            }
            result.put("header", respHeaders);
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
