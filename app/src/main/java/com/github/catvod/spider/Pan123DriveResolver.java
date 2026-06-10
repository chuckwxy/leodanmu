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

public class Pan123DriveResolver implements CloudDrive {

    @Override
    public JSONObject generateQRCode() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        throw new UnsupportedOperationException();
    }

    private static final String API = "https://www.123pan.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SHARE_PATTERN = Pattern.compile("123pan\\.com/s/([a-zA-Z0-9_-]+)");

    private final String username;
    private final String password;

    public Pan123DriveResolver(String username, String password) {
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
    }

    @Override
    public String getKey() { return "a123"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("123pan.com") || url.contains("123684.com") || url.contains("123pan.cn")
                || url.contains("123865.com") || url.contains("123912.com") || url.contains("123592.com");
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            Matcher m = SHARE_PATTERN.matcher(url);
            if (!m.find()) return null;
            String shareKey = m.group(1);

            Map<String, String> headers = buildHeaders();

            JSONObject body = new JSONObject();
            body.put("shareKey", shareKey);
            body.put("sharePwd", "");
            body.put("limit", 100);
            body.put("next", 0);
            JSONObject listResp = OkHttp.postJson(API + "/api/share/share/v2", body.toString(), headers);

            if (listResp != null && listResp.optInt("code") == 40001) {
                body.put("sharePwd", "123");
                listResp = OkHttp.postJson(API + "/api/share/share/v2", body.toString(), headers);
            }
            if (listResp == null) return null;

            JSONObject data = listResp.optJSONObject("data");
            if (data == null) return null;

            String title = data.optString("shareName", data.optString("name", ""));
            JSONArray fileList = data.optJSONArray("fileList");
            if (fileList == null || fileList.length() == 0) return null;

            JSONObject first = fileList.optJSONObject(0);
            String fileId = first != null ? first.optString("fileId", "") : "";
            String fileName = first != null ? first.optString("filename", first.optString("name", "")) : "";

            JSONObject dlBody = new JSONObject();
            dlBody.put("shareKey", shareKey);
            dlBody.put("fileId", fileId);
            JSONObject dlResp = OkHttp.postJson(API + "/api/share/download/info", dlBody.toString(), headers);
            String downloadUrl = dlResp != null ? dlResp.optString("DownloadUrl", dlResp.optString("url", "")) : "";

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "123\u4E91\u76D8\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "a123");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("123 getVod error: " + e.getMessage());
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
            respHeaders.put("Referer", "https://www.123pan.com/");
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
        headers.put("Referer", "https://www.123pan.com/");
        headers.put("Content-Type", "application/json");
        return headers;
    }
}
