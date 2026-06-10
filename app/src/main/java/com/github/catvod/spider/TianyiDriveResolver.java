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

public class TianyiDriveResolver implements CloudDrive {

    private static final String API = "https://cloud.189.cn";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SHARE_PATTERN = Pattern.compile("cloud\\.189\\.cn/t/([a-zA-Z0-9]+)");
    private static final Pattern SESSION_PATTERN = Pattern.compile("shareSession\":\"([^\"]+)\"");
    private static final Pattern SESSION_SECRET_PATTERN = Pattern.compile("shareSessionSecret\":\"([^\"]+)\"");

    private final String account;

    public TianyiDriveResolver(String account) {
        this.account = account != null ? account : "";
    }

    @Override
    public String getKey() { return "a189"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("cloud.189.cn");
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            Matcher m = SHARE_PATTERN.matcher(url);
            if (!m.find()) return null;
            String shareCode = m.group(1);

            Map<String, String> headers = buildHeaders();
            String initPage = OkHttp.string(API + "/share/" + shareCode, headers);
            if (TextUtils.isEmpty(initPage)) return null;

            String shareSession = extract(initPage, SESSION_PATTERN);
            String shareSessionSecret = extract(initPage, SESSION_SECRET_PATTERN);
            if (TextUtils.isEmpty(shareSession)) return null;

            String title = extract(initPage, Pattern.compile("shareName\":\"([^\"]+)\""));
            if (TextUtils.isEmpty(title)) title = extract(initPage, Pattern.compile("<title>([^<]+)</title>"));

            JSONObject listBody = new JSONObject();
            listBody.put("shareCode", shareCode);
            listBody.put("pageNum", 1);
            listBody.put("pageSize", 100);
            listBody.put("sessionKey", shareSession);
            JSONObject listResp = OkHttp.postJson(API + "/api/portal/v2/share/getShareFileList.action", listBody.toString(), headers);
            JSONArray list = listResp != null ? listResp.optJSONArray("data") : null;
            String firstFileId = "";
            String fileName = "";
            if (list != null && list.length() > 0) {
                JSONObject first = list.optJSONObject(0);
                firstFileId = first.optString("id", first.optString("fileId", "111"));
                fileName = first.optString("name", first.optString("fileName", ""));
            }
            if (TextUtils.isEmpty(firstFileId)) {
                firstFileId = listResp != null ? listResp.optString("fileId", "") : "";
                if (TextUtils.isEmpty(firstFileId)) firstFileId = "111";
            }

            JSONObject dlBody = new JSONObject();
            dlBody.put("shareCode", shareCode);
            dlBody.put("fileId", firstFileId);
            dlBody.put("sessionKey", shareSession);
            JSONObject dlResp = OkHttp.postJson(API + "/api/portal/v2/share/getShareFileDownloadUrl.action", dlBody.toString(), headers);
            String downloadUrl = dlResp != null ? dlResp.optString("fileDownloadUrl", "") : "";

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "\u5929\u7FFC\u4E91\u76D8\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "a189");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("Tianyi getVod error: " + e.getMessage());
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
        headers.put("Accept", "application/json, text/html, */*");
        if (!TextUtils.isEmpty(account)) headers.put("Cookie", account);
        return headers;
    }

    private String extract(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
