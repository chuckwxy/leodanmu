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
    private static final String UA = "netdisk;P2SP;2.2.91.136;android-android;";
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
            this.bduss = parsed.containsKey("BDUSS") ? parsed.get("BDUSS") : "";
            this.stoken = parsed.containsKey("STOKEN") ? parsed.get("STOKEN") : "";
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

        Map<String, String> headers = buildApiHeaders();
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

            Map<String, String> headers = buildApiHeaders();

            String initResp = OkHttp.string(API_BASE + "/share/init?surl=" + surl, headers);
            if (TextUtils.isEmpty(initResp)) return null;

            String sign = extractByPattern(initResp, SIGN_PATTERN);
            String timestamp = extractByPattern(initResp, TIMESTAMP_PATTERN);
            String randsk = extractByPattern(initResp, RANDSK_PATTERN);
            String shareid = extractByPattern(initResp, SHAREID_PATTERN);
            String uk = extractByPattern(initResp, UK_PATTERN);
            String title = extractByPattern(initResp, Pattern.compile("\"share_name\":\"([^\"]+)\""));
            String pwd = "";
            if (url.contains("pwd=")) {
                pwd = url.replaceAll(".*pwd=([^&#]+).*", "$1");
            }

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

            StringBuilder playUrlSb = new StringBuilder();
            for (int i = 0; i < list.length(); i++) {
                JSONObject file = list.optJSONObject(i);
                if (file == null) continue;
                String fName = file.optString("server_filename", "");
                String fId = file.optString("fs_id", "");
                if (TextUtils.isEmpty(fId)) continue;

                JSONObject token = new JSONObject();
                token.put("id", fId);
                token.put("share_id", Long.parseLong(shareid));
                token.put("uk", Long.parseLong(uk));
                token.put("shareId", surl);
                token.put("pwd", pwd);
                String tokenHex = bytesToHex(token.toString().getBytes("UTF-8"));

                String epName = fName;
                if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
                long sizeMB = fName.contains(".") ? 0 : 0;
                if (file.has("size")) {
                    sizeMB = file.optLong("size") / (1024 * 1024);
                }
                String displayName = (sizeMB > 0 ? "[" + sizeMB + "MB]" : "") + epName;

                if (i > 0) playUrlSb.append("#");
                playUrlSb.append(displayName).append("$").append(tokenHex);
            }

            if (TextUtils.isEmpty(title)) title = fileName;
            if (TextUtils.isEmpty(title)) title = "\u767E\u5EA6\u7F51\u76D8\u8D44\u6E90";

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

    @Override
    public JSONObject play(String input, String flag) {
        try {
            Leodanmu.log("Baidu play: input=" + input.substring(0, Math.min(input.length(), 80)) + " bduss(len)=" + (bduss != null ? bduss.length() : 0));
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("jx", 0);

            byte[] tokenBytes = hexToBytes(input);
            String tokenStr = new String(tokenBytes, "UTF-8");
            JSONObject token = new JSONObject(tokenStr);
            String fileId = token.optString("id", "");
            String shareId = token.optString("share_id", token.optString("shareId", ""));
            String uk = token.optString("uk", "");
            String surl = token.optString("shareId", "");

            Leodanmu.log("Baidu play: fileId=" + fileId + " shareId=" + shareId + " uk=" + uk);

            Map<String, String> headers = buildApiHeaders();

            JSONObject downloadBody = new JSONObject();
            downloadBody.put("shareid", shareId);
            downloadBody.put("uk", uk);
            downloadBody.put("primaryid", shareId);
            downloadBody.put("fid_list", new JSONArray().put(fileId));

            String dlUrl = API_BASE + "/share/download?channel=chunlei&clienttype=0&web=1";

            JSONObject dlResp = OkHttp.postJson(dlUrl, downloadBody.toString(), headers);
            JSONObject dlData = dlResp != null ? dlResp.optJSONObject("data") : null;
            String downloadUrl = dlData != null ? dlData.optString("dlink", dlData.optString("url", "")) : "";

            if (TextUtils.isEmpty(downloadUrl)) {
                Leodanmu.log("Baidu play: no download url from API");
                if (dlResp != null) {
                    Leodanmu.log("Baidu play: API response: " + dlResp.toString());
                }
                return result;
            }

            result.put("url", downloadUrl);
            JSONObject respHeaders = new JSONObject();
            respHeaders.put("User-Agent", UA);
            result.put("header", respHeaders);
            return result;
        } catch (Exception e) {
            SpiderDebug.log("Baidu play error: " + e.getMessage());
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }

    private String extractByPattern(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    @Override
    public JSONObject generateQRCode() throws Exception {
        return DriveQRCodeUtil.generateBaiduQR();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        return DriveQRCodeUtil.checkBaiduStatus(queryToken);
    }

    private Map<String, String> buildApiHeaders() {
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
