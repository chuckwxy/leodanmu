package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UcDriveResolver implements CloudDrive {

    private static final String API_BASE = "https://drive.uc.cn";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    private static final Pattern SHARE_URL_PATTERN = Pattern.compile("/(?:s|share)/([a-zA-Z0-9]+)");
    private static final int PAGE_SIZE = 100;

    private final String cookie;

    public UcDriveResolver(String cookie) {
        this.cookie = cookie != null ? cookie : "";
    }

    @Override
    public String getKey() { return "uc"; }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("drive.uc.cn") || url.contains("fast.uc.cn");
    }

    private String extractShareId(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            Matcher m = SHARE_URL_PATTERN.matcher(parsed.getPath());
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    private void transferToDrive(String shareId, String fileId) throws Exception {
        if (TextUtils.isEmpty(cookie)) return;

        Map<String, String> headers = buildHeaders();
        JSONObject body = new JSONObject();
        body.put("share_id", shareId);
        body.put("fid", fileId);

        OkResult result = OkHttp.post(API_BASE + "/share/save", body.toString(), headers);
        String resp = result != null ? result.getBody() : "";
        if (TextUtils.isEmpty(resp)) throw new Exception("save response empty");

        JSONObject json = new JSONObject(resp);
        int code = json.optInt("status", json.optInt("code", -1));
        if (code != 0 && code != 200) {
            String msg = json.optString("message", json.optString("msg", "unknown"));
            SpiderDebug.log("UC transferToDrive: " + msg + " (" + code + ")");
            if (code != 40008) throw new Exception("save failed: " + msg);
        }

        DriveManager.cleanupRegistry.scheduleDelete("uc", fileId);
    }

    @Override
    public JSONObject getVod(String url) {
        try {
            Leodanmu.log("UC getVod: url=" + url + " cookie(len)=" + cookie.length() + " empty=" + TextUtils.isEmpty(cookie));
            String shareId = extractShareId(url);
            if (TextUtils.isEmpty(shareId)) return null;

            JSONObject body = new JSONObject();
            body.put("pwd", "");
            body.put("share_id", shareId);
            body.put("page_size", PAGE_SIZE);
            body.put("page", 1);

            Map<String, String> headers = buildHeaders();
            OkResult okResult = OkHttp.post(API_BASE + "/share/info", body.toString(), headers);
            String resp = okResult != null ? okResult.getBody() : "";
            if (TextUtils.isEmpty(resp)) return null;

            JSONObject json = new JSONObject(resp);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;

            String title = data.optString("title", data.optString("share_title", "\u4E91\u76D8\u8D44\u6E90"));
            String fileId = data.optString("fid", data.optString("file_id", ""));
            String fileName = data.optString("file_name", data.optString("name", title));

            StringBuilder playUrlSb = new StringBuilder();
            int epIndex = 1;
            JSONObject downloadInfo = getDownloadUrl(shareId, fileId);
            if (downloadInfo != null) {
                String downloadUrl = downloadInfo.optString("url", "");
                if (!TextUtils.isEmpty(downloadUrl)) {
                    if (epIndex > 1) playUrlSb.append("#");
                    String epName = fileName;
                    if (epName.contains(".")) epName = epName.substring(0, epName.lastIndexOf('.'));
                    playUrlSb.append(epName).append("$").append(downloadUrl);
                    epIndex++;
                }
            }

            if (playUrlSb.length() == 0) {
                playUrlSb.append("\u64AD\u653E").append("$").append(url);
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", title);
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "uc");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("UC getVod error: " + e.getMessage());
            return null;
        }
    }

    private JSONObject getDownloadUrl(String shareId, String fid) {
        try {
            JSONObject body = new JSONObject();
            body.put("share_id", shareId);
            body.put("fid", fid);
            Map<String, String> headers = buildHeaders();
            OkResult okResult = OkHttp.post(API_BASE + "/share/download", body.toString(), headers);
            String resp = okResult != null ? okResult.getBody() : "";
            if (TextUtils.isEmpty(resp)) return null;
            JSONObject json = new JSONObject(resp);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            String downloadUrl = data.optString("url", data.optString("download_url", ""));
            if (TextUtils.isEmpty(downloadUrl)) return null;
            JSONObject result = new JSONObject();
            result.put("url", downloadUrl);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public JSONObject play(String input, String flag) {
        try {
            Leodanmu.log("UC play: input=" + input.substring(0, Math.min(input.length(), 80)) + " flag=" + flag + " cookie(len)=" + cookie.length() + " empty=" + TextUtils.isEmpty(cookie));
            String shareId = flag;
            try {
                transferToDrive(shareId, input);
            } catch (Exception e) {
                Leodanmu.log("UC transfer (non-fatal): " + e.getMessage());
            }

            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", input);
            JSONObject respHeaders = new JSONObject();
            respHeaders.put("Referer", API_BASE + "/");
            respHeaders.put("User-Agent", UA);
            if (!TextUtils.isEmpty(cookie)) {
                respHeaders.put("Cookie", cookie);
            }
            result.put("header", respHeaders);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public JSONObject generateQRCode() throws Exception {
        return DriveQRCodeUtil.generateUcQR();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        return DriveQRCodeUtil.checkUcLikeStatus("uc", queryToken);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", API_BASE + "/");
        headers.put("Origin", API_BASE);
        headers.put("Accept", "application/json, text/plain, */*");
        if (!TextUtils.isEmpty(cookie)) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }
}
