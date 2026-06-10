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

public class BaiduDriveResolver implements CloudDrive {

    private static final String API_BASE = "https://pan.baidu.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern SURL_PATTERN = Pattern.compile("/s/([a-zA-Z0-9_-]+)");
    private static final Pattern SIGN_PATTERN = Pattern.compile("\"sign\":\"([^\"]+)\"");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\"timestamp\":(\\d+)");
    private static final Pattern RANDSK_PATTERN = Pattern.compile("\"randsk\":\"([^\"]+)\"");
    private static final Pattern SHAREID_PATTERN = Pattern.compile("\"shareid\":(\\d+)");
    private static final Pattern UK_PATTERN = Pattern.compile("\"uk\":(\\d+)");

    private String bduss;
    private String stoken;

    public BaiduDriveResolver(String cookie) {
        if (!TextUtils.isEmpty(cookie)) {
            Map<String, String> parsed = parseCookie(cookie);
            this.bduss = parsed.getOrDefault("BDUSS", "");
            this.stoken = parsed.getOrDefault("STOKEN", "");
        }
    }

    public BaiduDriveResolver(String bduss, String stoken) {
        this.bduss = bduss != null ? bduss : "";
        this.stoken = stoken != null ? stoken : "";
    }

    @Override
    public String getKey() { return "baidu"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("pan.baidu.com") || url.contains("yun.baidu.com");
    }

    private Map<String, String> parseCookie(String cookie) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(cookie)) return map;
        for (String part : cookie.split(";\\s*")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private String extractSurl(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            Matcher m = SURL_PATTERN.matcher(parsed.getPath());
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    private void transferToDrive(String shareid, String fsId) throws Exception {
        if (bduss.isEmpty()) return;

        Map<String, String> headers = buildHeaders();
        JSONObject body = new JSONObject();
        body.put("shareid", shareid);
        body.put("fs_id", fsId);
        body.put("path", "/");

        JSONObject resp = OkHttp.postJson(API_BASE + "/share/transfer?shareid=" + shareid + "&from=web&v=2", body.toString(), headers);
        if (resp != null) {
            int errno = resp.optInt("errno", -1);
            if (errno != 0) {
                String msg = resp.optString("errmsg", resp.optString("errno", "unknown"));
                SpiderDebug.log("Baidu transfer: " + msg + " (" + errno + ")");
                if (errno != 12) throw new Exception("transfer failed: " + msg);
            }
        }

        DriveManager.cleanupRegistry.scheduleDelete("baidu", fsId, "");
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            Leodanmu.log("Baidu getVod: url=" + url + " bduss(len)=" + (bduss != null ? bduss.length() : 0) + " empty=" + TextUtils.isEmpty(bduss));
            String surl = extractSurl(url);
            if (TextUtils.isEmpty(surl)) return null;

            Map<String, String> headers = buildHeaders();

            String initResp = OkHttp.string(API_BASE + "/share/init?surl=" + surl, headers);
            if (TextUtils.isEmpty(initResp)) return null;

            String sign = extractByPattern(initResp, SIGN_PATTERN);
            String timestamp = extractByPattern(initResp, TIMESTAMP_PATTERN);
            String randsk = extractByPattern(initResp, RANDSK_PATTERN);
            String shareid = extractByPattern(initResp, SHAREID_PATTERN);
            String uk = extractByPattern(initResp, UK_PATTERN);
            String title = extractByPattern(initResp, Pattern.compile("\"share_name\":\"([^\"]+)\""));

            if (TextUtils.isEmpty(shareid)) return null;

            JSONObject body = new JSONObject();
            body.put("sign", sign);
            body.put("timestamp", timestamp);
            body.put("randsk", randsk);
            body.put("shareid", shareid);
            body.put("uk", uk);
            body.put("surl", surl);

            JSONObject listResp = OkHttp.postJson(API_BASE + "/share/list?channel=chunlei&clienttype=0&web=1", body.toString(), headers);
            if (listResp == null) return null;

            JSONObject data = listResp.optJSONObject("data");
            if (data == null) return null;
            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) return null;

            JSONObject first = list.optJSONObject(0);
            String fileName = first != null ? first.optString("server_filename", "") : "";
            String fsId = first != null ? first.optString("fs_id", "") : "";

            try {
                transferToDrive(shareid, fsId);
            } catch (Exception e) {
                SpiderDebug.log("Baidu transfer (non-fatal): " + e.getMessage());
            }

            JSONObject downloadBody = new JSONObject();
            downloadBody.put("shareid", shareid);
            downloadBody.put("uk", uk);
            downloadBody.put("primaryid", shareid);
            downloadBody.put("fid_list", new JSONArray().put(fsId));

            JSONObject dlResp = OkHttp.postJson(API_BASE + "/share/download?channel=chunlei&clienttype=0&web=1", downloadBody.toString(), headers);
            JSONObject dlData = dlResp != null ? dlResp.optJSONObject("data") : null;
            String downloadUrl = dlData != null ? dlData.optString("dlink", dlData.optString("url", "")) : "";

            if (!TextUtils.isEmpty(downloadUrl) && downloadUrl.contains("baidu.com")) {
                downloadUrl += "&app_id=250528";
            }

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "\u767E\u5EA6\u7F51\u76D8\u8D44\u6E90";

            StringBuilder playUrlSb = new StringBuilder();
            String epName = fileName;
            if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
            playUrlSb.append(epName).append("$").append(!TextUtils.isEmpty(downloadUrl) ? downloadUrl : url);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "baidu");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("Baidu getVod error: " + e.getMessage());
            return null;
        }
    }

    private String extractByPattern(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    @Override
    public JSONObject play(String input, String flag) {
        try {
            Leodanmu.log("Baidu play: input=" + input.substring(0, Math.min(input.length(), 80)) + " bduss(len)=" + (bduss != null ? bduss.length() : 0));
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", input);
            JSONObject respHeaders = new JSONObject();
            respHeaders.put("Referer", API_BASE + "/");
            respHeaders.put("User-Agent", UA);
            if (!TextUtils.isEmpty(bduss)) {
                String cookieStr = "BDUSS=" + bduss;
                if (!TextUtils.isEmpty(stoken)) cookieStr += "; STOKEN=" + stoken;
                respHeaders.put("Cookie", cookieStr);
            }
            result.put("header", respHeaders);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public JSONObject generateQRCode() throws Exception {
        return DriveQRCodeUtil.generateBaiduQR();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        return DriveQRCodeUtil.checkBaiduStatus(queryToken);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", API_BASE + "/");
        String cookieStr = "";
        if (!TextUtils.isEmpty(bduss)) cookieStr += "BDUSS=" + bduss + "; ";
        if (!TextUtils.isEmpty(stoken)) cookieStr += "STOKEN=" + stoken + "; ";
        if (!TextUtils.isEmpty(cookieStr)) headers.put("Cookie", cookieStr);
        return headers;
    }
}
