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

public class AliDriveResolver implements CloudDrive {

    private static final String AUTH_API = "https://auth.aliyundrive.com";
    private static final String API = "https://api.aliyundrive.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SHARE_ID_PATTERN = Pattern.compile("aliyundrive\\.com/s/([a-zA-Z0-9_]+)");

    private String refreshToken;
    private String accessToken;

    public AliDriveResolver(String refreshToken) {
        this.refreshToken = refreshToken != null ? refreshToken : "";
    }

    @Override
    public String getKey() { return "ali"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("aliyundrive.com") || url.contains("alipan.com");
    }

    private String getAccessToken() throws Exception {
        if (!TextUtils.isEmpty(accessToken)) return accessToken;
        if (TextUtils.isEmpty(refreshToken)) return "";
        JSONObject body = new JSONObject();
        body.put("refresh_token", refreshToken);
        body.put("grant_type", "refresh_token");
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Content-Type", "application/json");
        JSONObject resp = OkHttp.postJson(AUTH_API + "/v2/account/token", body.toString(), headers);
        if (resp == null) return "";
        accessToken = resp.optString("access_token", "");
        String newRefresh = resp.optString("refresh_token", "");
        if (!TextUtils.isEmpty(newRefresh)) refreshToken = newRefresh;
        return accessToken;
    }

    @Override
    public JSONObject generateQRCode() throws Exception {
        return DriveQRCodeUtil.generateAliQR();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        return DriveQRCodeUtil.checkAliStatus(queryToken);
    }

    private void transferToDrive(String shareId, String fileId, String shareToken) throws Exception {
        if (TextUtils.isEmpty(accessToken)) return;

        JSONObject body = new JSONObject();
        body.put("share_id", shareId);
        body.put("file_id", fileId);
        body.put("auto_rename", true);
        body.put("to_parent_file_id", "root");

        Map<String, String> headers = buildHeaders();
        if (!TextUtils.isEmpty(shareToken)) {
            headers.put("x-share-token", shareToken);
        }
        JSONObject resp = OkHttp.postJson(API + "/v2/share_link/share_to_drive", body.toString(), headers);
        if (resp == null) throw new Exception("transfer response null");

        DriveManager.cleanupRegistry.scheduleDelete("ali", fileId);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", "https://www.aliyundrive.com/");
        headers.put("Content-Type", "application/json");
        if (!TextUtils.isEmpty(accessToken)) {
            headers.put("Authorization", "Bearer " + accessToken);
        }
        return headers;
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            Leodanmu.log("Ali getVod: url=" + url + " refreshToken(len)=" + (refreshToken != null ? refreshToken.length() : 0));
            Matcher m = SHARE_ID_PATTERN.matcher(url);
            if (!m.find()) return null;
            String shareId = m.group(1);

            String token = getAccessToken();
            Leodanmu.log("Ali getVod: accessToken empty=" + TextUtils.isEmpty(token));
            if (TextUtils.isEmpty(token)) return null;

            Map<String, String> headers = buildHeaders();

            JSONObject shareBody = new JSONObject();
            shareBody.put("share_id", shareId);
            JSONObject shareResp = OkHttp.postJson(API + "/v2/share_link/get_share_by_anonymous", shareBody.toString(), headers);
            String title = shareResp != null ? shareResp.optString("title", shareResp.optString("share_name", "")) : "";
            String shareToken = shareResp != null ? shareResp.optString("share_token", "") : "";

            if (!TextUtils.isEmpty(shareToken)) {
                headers.put("x-share-token", shareToken);
            }

            JSONObject listBody = new JSONObject();
            listBody.put("share_id", shareId);
            listBody.put("limit", 100);
            listBody.put("parent_file_id", "root");
            JSONObject listResp = OkHttp.postJson(API + "/v2/share_link/get_share_file_list", listBody.toString(), headers);
            if (listResp == null) return null;

            JSONArray items = listResp.optJSONArray("items");
            if (items == null || items.length() == 0) return null;

            JSONObject first = items.optJSONObject(0);
            String fileId = first != null ? first.optString("file_id", "") : "";
            String fileName = first != null ? first.optString("name", "") : "";

            try {
                transferToDrive(shareId, fileId, shareToken);
            } catch (Exception e) {
                SpiderDebug.log("Ali transfer (non-fatal): " + e.getMessage());
            }

            JSONObject dlBody = new JSONObject();
            dlBody.put("share_id", shareId);
            dlBody.put("file_id", fileId);
            JSONObject dlResp = OkHttp.postJson(API + "/v2/file/get_share_link_download_url", dlBody.toString(), headers);
            String downloadUrl = dlResp != null ? dlResp.optString("download_url", "") : "";

            if (TextUtils.isEmpty(downloadUrl)) {
                JSONObject dlUrlBody = new JSONObject();
                dlUrlBody.put("file_id", fileId);
                dlUrlBody.put("expire_sec", 14400);
                JSONObject dlUrlResp = OkHttp.postJson(API + "/v2/file/get_download_url", dlUrlBody.toString(), buildHeaders());
                downloadUrl = dlUrlResp != null ? dlUrlResp.optString("url", "") : "";
            }

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "\u963F\u91CC\u4E91\u76D8\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "ali");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("Ali getVod error: " + e.getMessage());
            return null;
        }
    }

    @Override
    public JSONObject play(String input, String flag) {
        try {
            getAccessToken();
            Leodanmu.log("Ali play: input=" + input.substring(0, Math.min(input.length(), 80)) + " accessToken empty=" + TextUtils.isEmpty(accessToken));
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", input);
            JSONObject respHeaders = new JSONObject();
            respHeaders.put("Referer", "https://www.aliyundrive.com/");
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
