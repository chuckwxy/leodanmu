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

public class QuarkDriveResolver implements CloudDrive {

    private static final String API_BASE = "https://pan.quark.cn";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    private static final Pattern SHARE_URL_PATTERN = Pattern.compile("/s/([a-zA-Z0-9]+)");
    private static final int PAGE_SIZE = 100;

    private final String cookie;

    public QuarkDriveResolver(String cookie) {
        this.cookie = cookie != null ? cookie : "";
    }

    @Override
    public String getKey() {
        return "quark";
    }

    @Override
    public boolean matchShare(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("pan.quark.cn") || url.contains("drive-hz.quark.cn");
    }

    public static class ShareInfo {
        public String pwdId;
        public String passcode;
        public String pdirFid;
    }

    public ShareInfo parseShareUrl(String url) {
        ShareInfo info = new ShareInfo();
        try {
            java.net.URL parsed = new java.net.URL(url);
            Matcher m = SHARE_URL_PATTERN.matcher(parsed.getPath());
            if (m.find()) info.pwdId = m.group(1);
            info.passcode = parsed.getQuery() != null ? getQueryParam(parsed.getQuery(), "pwd") : "";
            if (TextUtils.isEmpty(info.passcode)) {
                info.passcode = parsed.getQuery() != null ? getQueryParam(parsed.getQuery(), "password") : "";
            }
            String hash = parsed.getRef();
            if (!TextUtils.isEmpty(hash)) {
                Matcher dm = Pattern.compile("#/?list/share/([a-zA-Z0-9]+)").matcher(hash);
                if (dm.find()) info.pdirFid = dm.group(1);
            }
        } catch (Exception e) {
            SpiderDebug.log("Quark parseShareUrl error: " + e.getMessage());
        }
        return info;
    }

    private String getQueryParam(String query, String key) {
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return "";
    }

    public static class TokenResult {
        public String stoken;
        public String title;
    }

    public TokenResult getToken(ShareInfo info) throws Exception {
        JSONObject body = new JSONObject();
        body.put("pwd_id", info.pwdId);
        body.put("passcode", info.passcode != null ? info.passcode : "");

        Map<String, String> headers = buildHeaders();
        OkResult okResult = OkHttp.post(API_BASE + "/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc&__dt=" + System.currentTimeMillis(),
                body.toString(), headers);
        String resp = okResult != null ? okResult.getBody() : "";
        if (TextUtils.isEmpty(resp)) throw new Exception("empty token response");

        JSONObject json = new JSONObject(resp);
        int code = json.optInt("code", -1);
        if (code == 31001) throw new Exception("share needs passcode");
        if (code == 31002) throw new Exception("share is invalid or removed");
        if (code != 0) throw new Exception("get token failed: " + json.optString("message", "unknown") + " (" + code + ")");

        JSONObject data = json.optJSONObject("data");
        if (data == null) throw new Exception("empty token data");

        TokenResult result = new TokenResult();
        result.stoken = data.optString("stoken", "");
        result.title = data.optString("title", "");
        if (TextUtils.isEmpty(result.stoken)) throw new Exception("empty stoken");
        return result;
    }

    public static class FileEntry {
        public String fileName;
        public String fid;
        public String pdirFid;
        public long size;
        public boolean isDir;
        public boolean isVideo;
        public String path;
        public String shareFidToken;
    }

    public List<FileEntry> listFiles(TokenResult token, ShareInfo info) throws Exception {
        List<FileEntry> allFiles = new ArrayList<>();
        int page = 1;
        while (true) {
            String url = API_BASE + "/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc"
                    + "&pwd_id=" + URLEncoder.encode(info.pwdId, "UTF-8")
                    + "&stoken=" + URLEncoder.encode(token.stoken, "UTF-8")
                    + "&pdir_fid=" + URLEncoder.encode(info.pdirFid != null ? info.pdirFid : "", "UTF-8")
                    + "&_page=" + page + "&_size=" + PAGE_SIZE
                    + "&_sort=file_type:asc,updated_at:desc&__dt=" + System.currentTimeMillis();

            Map<String, String> headers = buildHeaders();
            String resp = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(resp)) break;

            JSONObject json = new JSONObject(resp);
            if (json.optInt("code", -1) != 0) break;

            JSONObject data = json.optJSONObject("data");
            if (data == null) break;

            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) break;

            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                FileEntry entry = new FileEntry();
                entry.fileName = item.optString("file_name", item.optString("name", ""));
                entry.fid = item.optString("fid", item.optString("file_id", ""));
                entry.pdirFid = item.optString("pdir_fid", item.optString("parent_fid", info.pdirFid));
                entry.size = item.optLong("size", 0);
                String category = item.optString("category", "").toLowerCase();
                entry.isDir = item.optBoolean("dir", false) || item.optBoolean("is_dir", false) || "dir".equals(category) || "folder".equals(category);
                entry.isVideo = detectVideo(item);
                entry.shareFidToken = item.optString("share_fid_token", item.optString("fid_token", item.optString("file_token", "")));
                if (i == 0 && allFiles.size() == 0 && page == 1) {
                    Leodanmu.log("Quark listFiles: first item keys=" + (item.names() != null ? item.names().toString() : "{}") + " shareFidToken=" + entry.shareFidToken);
                }
                allFiles.add(entry);
            }

            int count = json.optJSONObject("metadata") != null ? json.optJSONObject("metadata").optInt("_count", 0) : 0;
            if (count < PAGE_SIZE) break;
            page++;
        }
        return allFiles;
    }

    public List<FileEntry> getAllFilesRecursive(TokenResult token, ShareInfo info) throws Exception {
        return getAllFilesRecursive(token, info, "", new ArrayList<>(), 0, new int[]{0});
    }

    private List<FileEntry> getAllFilesRecursive(TokenResult token, ShareInfo info, String parentPath,
                                                  List<FileEntry> out, int maxCount, int[] state) throws Exception {
        List<FileEntry> files = listFiles(token, info);
        for (FileEntry file : files) {
            if (maxCount > 0 && state[0] >= maxCount) break;
            String currentPath = TextUtils.isEmpty(parentPath) ? file.fileName : parentPath + "/" + file.fileName;
            if (file.isDir) {
                ShareInfo subInfo = new ShareInfo();
                subInfo.pwdId = info.pwdId;
                subInfo.passcode = info.passcode;
                subInfo.pdirFid = file.fid;
                getAllFilesRecursive(token, subInfo, currentPath, out, maxCount, state);
            } else {
                if (file.isVideo) {
                    file.path = parentPath;
                    out.add(file);
                    state[0]++;
                }
            }
        }
        return out;
    }

    private boolean detectVideo(JSONObject item) {
        String category = item.optString("category", "").toLowerCase();
        if ("video".equals(category)) return true;
        String mime = item.optString("mime_type", "").toLowerCase();
        if (mime.contains("video")) return true;
        String name = item.optString("file_name", item.optString("name", "")).toLowerCase();
        return name.matches(".*\\.(mp4|mkv|avi|flv|ts|m2ts|mov|m4v|webm)$");
    }

    public String buildPlayTokenHex(ShareInfo info, FileEntry file) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("shareId", info.pwdId);
            payload.put("fileId", file.fid);
            payload.put("folderId", file.pdirFid != null ? file.pdirFid : "");
            payload.put("pwd", info.passcode != null ? info.passcode : JSONObject.NULL);
            payload.put("type", "video");
            payload.put("subs", new JSONArray());
            if (!TextUtils.isEmpty(file.shareFidToken)) {
                payload.put("fidToken", file.shareFidToken);
            }
            String json = payload.toString();
            StringBuilder hex = new StringBuilder();
            for (byte b : json.getBytes("UTF-8")) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static class PlayToken {
        public String shareId;
        public String fileId;
        public String folderId;
        public String pwd;
        public String type;
        public String fidToken;
    }

    public PlayToken decodePlayToken(String hexToken) {
        try {
            byte[] bytes = new byte[hexToken.length() / 2];
            for (int i = 0; i < hexToken.length(); i += 2) {
                bytes[i / 2] = (byte) Integer.parseInt(hexToken.substring(i, i + 2), 16);
            }
            String json = new String(bytes, "UTF-8");
            JSONObject obj = new JSONObject(json);
            PlayToken token = new PlayToken();
            token.shareId = obj.optString("shareId", "");
            token.fileId = obj.optString("fileId", "");
            token.folderId = obj.optString("folderId", "");
            token.pwd = obj.optString("pwd", "");
            token.type = obj.optString("type", "video");
            token.fidToken = obj.optString("fidToken", "");
            return token;
        } catch (Exception e) {
            return null;
        }
    }

    private void transferToDrive(ShareInfo info, TokenResult tokenResult, String fileId, String fidToken) throws Exception {
        if (TextUtils.isEmpty(cookie)) {
            Leodanmu.log("Quark transferToDrive: skipped, cookie empty");
            return;
        }
        Leodanmu.log("Quark transferToDrive: shareId=" + info.pwdId + " fileId=" + fileId + " fidToken=" + (fidToken != null ? !fidToken.isEmpty() : "null"));

        JSONObject body = new JSONObject();
        JSONArray fidList = new JSONArray();
        fidList.put(fileId);
        body.put("fid_list", fidList);
        JSONArray fidTokenList = new JSONArray();
        if (!TextUtils.isEmpty(fidToken)) fidTokenList.put(fidToken);
        body.put("fid_token_list", fidTokenList);
        body.put("to_pdir_fid", "0");
        body.put("pwd_id", info.pwdId);
        body.put("stoken", tokenResult.stoken);
        body.put("pdir_fid", info.pdirFid != null ? info.pdirFid : "0");
        body.put("scene", "scene");

        Map<String, String> headers = buildHeaders();
        OkResult result = OkHttp.post(API_BASE + "/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc&__dt=" + System.currentTimeMillis(),
                body.toString(), headers);
        String resp = result != null ? result.getBody() : "";
        if (TextUtils.isEmpty(resp)) {
            Leodanmu.log("Quark transferToDrive: save response empty");
            throw new Exception("save response empty");
        }

        JSONObject json = new JSONObject(resp);
        int code = json.optInt("status", json.optInt("code", -1));
        if (code != 0 && code != 200) {
            String msg = json.optString("message", json.optString("msg", "unknown"));
            Leodanmu.log("Quark transferToDrive: failed " + msg + " (" + code + ")");
            if (code != 40008) throw new Exception("save failed: " + msg);
        } else {
            Leodanmu.log("Quark transferToDrive: success code=" + code);
        }

        JSONObject data = json.optJSONObject("data");
        if (data != null) {
            JSONArray fids = data.optJSONArray("save_as_top_fids");
            if (fids != null && fids.length() > 0) {
                String savedFileId = fids.optString(0, "");
                if (!TextUtils.isEmpty(savedFileId)) {
                    Leodanmu.log("Quark transferToDrive: saved fileId=" + savedFileId);
                    DriveManager.cleanupRegistry.scheduleDelete("quark", savedFileId);
                }
            }
        }
    }

    public JSONObject play(String input, String flag) {
        try {
            Leodanmu.log("Quark play: input=" + input.substring(0, Math.min(input.length(), 60)) + " cookie(len)=" + cookie.length() + " empty=" + TextUtils.isEmpty(cookie));
            PlayToken token = decodePlayToken(input);
            if (token == null) {
                Leodanmu.log("Quark play: token decode failed, falling back to proxyPlay");
                return proxyPlay(input, flag);
            }

            ShareInfo info = new ShareInfo();
            info.pwdId = token.shareId;
            info.passcode = token.pwd;
            info.pdirFid = token.folderId;

            TokenResult tokenResult = getToken(info);

            try {
                transferToDrive(info, tokenResult, token.fileId, token.fidToken);
            } catch (Exception e) {
                Leodanmu.log("Quark transfer (non-fatal): " + e.getMessage());
            }

            JSONObject body = new JSONObject();
            body.put("stoken", tokenResult.stoken);
            body.put("pwd_id", info.pwdId);
            body.put("pdir_fid", info.pdirFid);
            body.put("file_id", token.fileId);
            body.put("force_update", false);
            if (!TextUtils.isEmpty(token.fidToken)) {
                body.put("fid_token", token.fidToken);
            }

            Map<String, String> headers = buildHeaders();
            OkResult okPlayResult = OkHttp.post(API_BASE + "/1/clouddrive/share/sharepage/video?pr=ucpro&fr=pc&__dt=" + System.currentTimeMillis(),
                    body.toString(), headers);
            String resp = okPlayResult != null ? okPlayResult.getBody() : "";
            if (!TextUtils.isEmpty(resp)) {
                JSONObject json = new JSONObject(resp);
                Leodanmu.log("Quark play: video API resp=" + json.toString());
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    String videoUrl = data.optString("video_url", "");
                    if (!TextUtils.isEmpty(videoUrl)) {
                        Leodanmu.log("Quark play: got video_url=" + videoUrl.substring(0, Math.min(videoUrl.length(), 120)));
                        JSONObject result = new JSONObject();
                        result.put("parse", 0);
                        result.put("url", videoUrl);
                        JSONObject respHeaders = new JSONObject();
                        respHeaders.put("Referer", "https://pan.quark.cn/");
                        respHeaders.put("User-Agent", UA);
                        if (!TextUtils.isEmpty(cookie)) {
                            respHeaders.put("Cookie", cookie);
                        }
                        result.put("header", respHeaders);
                        return result;
                    }
                    Leodanmu.log("Quark play: no video_url in response");
                } else {
                    Leodanmu.log("Quark play: no data in response, msg=" + json.optString("message", ""));
                }
            } else {
                Leodanmu.log("Quark play: empty response from video API");
            }
        } catch (Exception e) {
            SpiderDebug.log("Quark play error: " + e.getMessage());
        }
        return proxyPlay(input, flag);
    }

    private JSONObject proxyPlay(String input, String flag) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", input);
            JSONObject respHeaders = new JSONObject();
            respHeaders.put("Referer", "https://pan.quark.cn/");
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

    public JSONObject getVod(String url) {
        try {
            Leodanmu.log("Quark getVod: url=" + url + " cookie(len)=" + cookie.length() + " empty=" + TextUtils.isEmpty(cookie));
            ShareInfo info = parseShareUrl(url);
            if (TextUtils.isEmpty(info.pwdId)) return null;

            TokenResult token = getToken(info);
            List<FileEntry> videos = getAllFilesRecursive(token, info);

            if (videos.isEmpty()) {
                JSONObject vod = new JSONObject();
                vod.put("vod_id", url);
                vod.put("vod_name", token.title);
                vod.put("vod_pic", "");
                vod.put("vod_play_from", "quark");
                vod.put("vod_play_url", getDefaultPlayUrl(url));
                return vod;
            }

            StringBuilder playUrlSb = new StringBuilder();
            for (int i = 0; i < videos.size(); i++) {
                FileEntry file = videos.get(i);
                if (i > 0) playUrlSb.append("#");
                String playTokenHex = buildPlayTokenHex(info, file);
                String epName = file.fileName;
                if (epName.contains(".")) {
                    epName = epName.substring(0, epName.lastIndexOf('.'));
                }
                long sizeMB = file.size / (1024 * 1024);
                String displayName = "[" + sizeMB + "MB]" + epName;
                playUrlSb.append(displayName).append("$").append(playTokenHex);
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", url);
            vod.put("vod_name", !TextUtils.isEmpty(token.title) ? token.title : "云盘资源");
            vod.put("vod_pic", "");
            vod.put("vod_play_from", "quark");
            vod.put("vod_play_url", playUrlSb.toString());
            return vod;
        } catch (Exception e) {
            SpiderDebug.log("Quark getVod error: " + e.getMessage());
            return null;
        }
    }

    private String getDefaultPlayUrl(String url) {
        try {
            return "\u64AD\u653E$" + url;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public JSONObject generateQRCode() throws Exception {
        return DriveQRCodeUtil.generateQuarkQR();
    }

    @Override
    public JSONObject checkQRStatus(String queryToken) throws Exception {
        return DriveQRCodeUtil.checkUcLikeStatus("quark", queryToken);
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
