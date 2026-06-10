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

public class PikpakDriveResolver implements CloudDrive {

    @Override
    public JSONObject generateQRCode() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        throw new UnsupportedOperationException();
    }

    private static final String API = "https://api-drive.mypikpak.com";
    private static final String AUTH_API = "https://user.mypikpak.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SHARE_PATTERN = Pattern.compile("(?:mypikpak|pikpak)\\.com/s/([a-zA-Z0-9_-]+)");

    private final String username;
    private final String password;
    private String accessToken;

    public PikpakDriveResolver(String username, String password) {
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
    }

    @Override
    public String getKey() { return "pikpak"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("mypikpak.com") || url.contains("pikpak.com");
    }

    private String login() throws Exception {
        if (!TextUtils.isEmpty(accessToken)) return accessToken;
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) return "";

        JSONObject body = new JSONObject();
        body.put("captcha_token", "");
        body.put("client_id", "YUMx5nI8ZU8Ap8pm");
        body.put("client_secret", "DbSlovN1ZGZ7oxWk");
        body.put("username", username);
        body.put("password", password);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Content-Type", "application/json");

        OkResult result = OkHttp.post(AUTH_API + "/v1/auth/signin", body.toString(), headers);
        if (result == null) return "";
        JSONObject resp = new JSONObject(result.getBody());
        JSONObject data = resp.optJSONObject("data");
        if (data != null) {
            accessToken = data.optString("access_token", "");
        }
        return accessToken;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Content-Type", "application/json");
        headers.put("Referer", "https://mypikpak.com/");
        if (!TextUtils.isEmpty(accessToken)) {
            headers.put("Authorization", "Bearer " + accessToken);
        }
        return headers;
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            login();
            Matcher m = SHARE_PATTERN.matcher(url);
            if (!m.find()) return null;
            String shareCode = m.group(1);
            Map<String, String> headers = buildHeaders();

            JSONObject shareBody = new JSONObject();
            shareBody.put("share_code", shareCode);
            shareBody.put("pass_code", "");
            JSONObject shareResp = OkHttp.postJson(API + "/drive/v1/share:get", shareBody.toString(), headers);
            String title = shareResp != null ? shareResp.optString("title", shareResp.optString("share_title", "")) : "";

            JSONObject fileListResp = OkHttp.postJson(API + "/drive/v1/share:list?share_code=" + shareCode, "", headers);
            String firstFileId = "";
            String fileName = "";
            if (fileListResp != null) {
                JSONObject files = fileListResp.optJSONObject("files");
                if (files != null) {
                    JSONObject first = files.optJSONObject("0");
                    if (first != null) {
                        firstFileId = first.optString("id", first.optString("file_id", ""));
                        fileName = first.optString("name", first.optString("file_name", ""));
                    }
                }
            }

            String downloadUrl = "";
            try {
                JSONObject dlBody = new JSONObject();
                dlBody.put("share_code", shareCode);
                dlBody.put("file_id", firstFileId);
                JSONObject dlResp = OkHttp.postJson(API + "/drive/v1/share:download", dlBody.toString(), headers);
                if (dlResp != null) {
                    downloadUrl = dlResp.optString("download_url", dlResp.optString("url", ""));
                }
            } catch (Exception ignored) {}

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "Pikpak\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "pikpak");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("Pikpak getVod error: " + e.getMessage());
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
            respHeaders.put("Referer", "https://mypikpak.com/");
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
