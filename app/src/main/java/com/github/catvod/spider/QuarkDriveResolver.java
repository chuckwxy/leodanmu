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
    private static final String DRIVE_API_BASE = "https://drive.quark.cn/1/clouddrive";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    private static final Pattern SHARE_URL_PATTERN = Pattern.compile("/s/([a-zA-Z0-9]+)");
    private static final int PAGE_SIZE = 100;
    private static final String LEOOPEN_DIR_NAME = "Leoopen";

    private final String cookie;
    private String leoopenDirId;

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

    public String getFreshFidToken(TokenResult token, ShareInfo info, String targetFileId) throws Exception {
        String stokenEncoded = URLEncoder.encode(token.stoken, "UTF-8");
        String pdirEncoded = URLEncoder.encode(info.pdirFid != null ? info.pdirFid : "", "UTF-8");
        String url = API_BASE + "/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc"
                + "&pwd_id=" + URLEncoder.encode(info.pwdId, "UTF-8")
                + "&stoken=" + stokenEncoded
                + "&pdir_fid=" + pdirEncoded
                + "&_page=1&_size=100"
                + "&_sort=file_type:asc,updated_at:desc&__dt=" + System.currentTimeMillis();

        Map<String, String> headers = buildHeaders();
        String resp = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(resp)) throw new Exception("empty detail response");

        JSONObject json = new JSONObject(resp);
        JSONObject data = json.optJSONObject("data");
        if (data == null) throw new Exception("no data in detail response: " + json.optString("message", ""));

        JSONArray list = data.optJSONArray("list");
        if (list == null) throw new Exception("no list in detail response");

        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String fid = item.optString("fid", "");
            if (fid.equals(targetFileId)) {
                String freshToken = item.optString("share_fid_token", "");
                if (!TextUtils.isEmpty(freshToken)) return freshToken;
                break;
            }
        }
        throw new Exception("file not found in fresh detail listing");
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
                    + "&_sort=file_name:asc&__dt=" + System.currentTimeMillis();

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
                String rawFidToken = "";
                if (item.has("share_fid_token")) rawFidToken = item.optString("share_fid_token");
                else if (item.has("fid_token")) rawFidToken = item.optString("fid_token");
                else if (item.has("obj_fid_token")) rawFidToken = item.optString("obj_fid_token");
                else if (item.has("file_token")) rawFidToken = item.optString("file_token");
                else if (item.has("token")) rawFidToken = item.optString("token");
                entry.shareFidToken = rawFidToken;
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

    private synchronized String getOrCreateLeoopenDir() throws Exception {
        if (!TextUtils.isEmpty(leoopenDirId)) return leoopenDirId;
        if (TextUtils.isEmpty(cookie)) return "0";

        Map<String, String> headers = buildHeaders();
        String listUrl = API_BASE + "/1/clouddrive/file/sort?pr=ucpro&fr=pc&pdir_fid=0&_page=1&_size=50&sort=file_type:asc,file_name:asc";
        String listResp = OkHttp.string(listUrl, headers);
        if (!TextUtils.isEmpty(listResp)) {
            JSONObject json = new JSONObject(listResp);
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                JSONArray list = data.optJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        if (item != null && LEOOPEN_DIR_NAME.equals(item.optString("file_name")) && item.optInt("file_type", -1) == 0) {
                            leoopenDirId = item.optString("fid");
                            SpiderDebug.log("Quark: found existing " + LEOOPEN_DIR_NAME + " fid=" + leoopenDirId);
                            return leoopenDirId;
                        }
                    }
                }
            }
        }

        JSONObject body = new JSONObject();
        body.put("pdir_fid", "0");
        body.put("file_name", LEOOPEN_DIR_NAME);
        body.put("dir_init_lock", false);
        body.put("file_type", 0);
        OkResult result = OkHttp.post(API_BASE + "/1/clouddrive/file?pr=ucpro&fr=pc", body.toString(), headers);
        String resp = result != null ? result.getBody() : "";
        if (TextUtils.isEmpty(resp)) throw new Exception("create dir response empty");
        JSONObject json = new JSONObject(resp);
        JSONObject data = json.optJSONObject("data");
        if (data != null && data.optBoolean("finish", false)) {
            leoopenDirId = data.optString("fid");
            SpiderDebug.log("Quark: created " + LEOOPEN_DIR_NAME + " fid=" + leoopenDirId);
            return leoopenDirId;
        }
        throw new Exception("create " + LEOOPEN_DIR_NAME + " failed: " + json.optString("message", resp));
    }

    private String transferToDrive(ShareInfo info, TokenResult tokenResult, String fileId, String fidToken, String toDirFid) throws Exception {
        if (TextUtils.isEmpty(cookie)) {
            SpiderDebug.log("Quark transferToDrive: skipped, cookie empty");
            return "";
        }
        JSONObject body = new JSONObject();
        JSONArray fidList = new JSONArray();
        fidList.put(fileId);
        body.put("fid_list", fidList);
        JSONArray fidTokenList = new JSONArray();
        if (!TextUtils.isEmpty(fidToken)) fidTokenList.put(fidToken);
        body.put("fid_token_list", fidTokenList);
        body.put("to_pdir_fid", TextUtils.isEmpty(toDirFid) ? "0" : toDirFid);
        body.put("pwd_id", info.pwdId);
        body.put("stoken", tokenResult.stoken);
        body.put("pdir_fid", info.pdirFid != null ? info.pdirFid : "0");
        body.put("scene", "SCENE_SAVE");

        Map<String, String> headers = buildHeaders();
        OkResult result = OkHttp.post(API_BASE + "/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc&__dt=" + System.currentTimeMillis(),
                body.toString(), headers);
        String resp = result != null ? result.getBody() : "";
        if (TextUtils.isEmpty(resp)) {
            SpiderDebug.log("Quark transferToDrive: save response empty");
            throw new Exception("save response empty");
        }

        JSONObject json = new JSONObject(resp);
        int code = json.optInt("status", json.optInt("code", -1));
        if (code != 0 && code != 200) {
            String msg = json.optString("message", json.optString("msg", "unknown"));
            SpiderDebug.log("Quark transferToDrive: failed " + msg + " (" + code + ")");
            if (code != 40008 && code != 400) throw new Exception("save failed: " + msg);
            return "";
        }
        SpiderDebug.log("Quark transferToDrive: success code=" + code);

        String savedFileId = "";
        JSONObject saveData = json.optJSONObject("data");
        if (saveData != null) {
            JSONObject taskResp = saveData.optJSONObject("task_resp");
            if (taskResp != null) {
                JSONObject taskData = taskResp.optJSONObject("data");
                if (taskData != null) {
                    JSONObject saveAs = taskData.optJSONObject("save_as");
                    if (saveAs != null) {
                        JSONArray fids = saveAs.optJSONArray("save_as_top_fids");
                        if (fids != null && fids.length() > 0) {
                            savedFileId = fids.optString(0, "");
                        }
                    }
                }
            }
        }
        if (!TextUtils.isEmpty(savedFileId)) {
            SpiderDebug.log("Quark transferToDrive: saved fileId=" + savedFileId);
            DriveManager.cleanupRegistry.scheduleDelete("quark", savedFileId, toDirFid, cookie);
        }
        return savedFileId;
    }

    public JSONObject play(String input, String flag) {
        try {
            PlayToken token = decodePlayToken(input);
            if (token == null) {
                SpiderDebug.log("Quark play: token decode failed, falling back to proxyPlay");
                return proxyPlay(input, flag);
            }

            ShareInfo info = new ShareInfo();
            info.pwdId = token.shareId;
            info.passcode = token.pwd;
            info.pdirFid = token.folderId;

            TokenResult tokenResult = getToken(info);

            String freshFidToken = token.fidToken;
            try {
                freshFidToken = getFreshFidToken(tokenResult, info, token.fileId);
            } catch (Exception e) {
                SpiderDebug.log("Quark play: getFreshFidToken failed, using stale token: " + e.getMessage());
            }

            String leoopenFid = "0";
            try {
                leoopenFid = getOrCreateLeoopenDir();
            } catch (Exception e) {
                SpiderDebug.log("Quark play: getOrCreateLeoopenDir failed, saving to root: " + e.getMessage());
            }

            String savedFileId = "";
            try {
                savedFileId = transferToDrive(info, tokenResult, token.fileId, freshFidToken, leoopenFid);
            } catch (Exception e) {
                SpiderDebug.log("Quark play: transfer failed (non-fatal): " + e.getMessage());
            }

            if (!TextUtils.isEmpty(savedFileId)) {
                String downloadUrl = getQuarkDownloadUrl(savedFileId);
                if (!TextUtils.isEmpty(downloadUrl)) {
                    return buildMultiQualityResult(downloadUrl, savedFileId, token.fileId);
                }
                SpiderDebug.log("Quark play: failed to get download_url from file API");
            } else {
                SpiderDebug.log("Quark play: no savedFileId, trying file/download with original fid");
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
            result.put("jx", 0);
            if (!TextUtils.isEmpty(input)) {
                JSONArray urls = new JSONArray();
                urls.put("RAW");
                urls.put(input);
                result.put("url", urls);
            } else {
                result.put("url", input);
            }
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

    private String getQuarkDownloadUrl(String savedFileId) throws Exception {
        Map<String, String> headers = buildHeaders();
        JSONObject dlBody = new JSONObject();
        JSONArray fids = new JSONArray();
        fids.put(savedFileId);
        dlBody.put("fids", fids);
        OkResult okDlResult = OkHttp.post(API_BASE + "/1/clouddrive/file/download?pr=ucpro&fr=pc&__dt=" + System.currentTimeMillis(),
                dlBody.toString(), headers);
        String dlResp = okDlResult != null ? okDlResult.getBody() : "";
        if (TextUtils.isEmpty(dlResp)) return "";
        JSONObject dlJson = new JSONObject(dlResp);
        JSONArray dlData = dlJson.optJSONArray("data");
        if (dlData != null && dlData.length() > 0) {
            String url = dlData.optJSONObject(0).optString("download_url", "");
            if (!TextUtils.isEmpty(url)) {
                return url;
            }
        }
        return "";
    }

    private JSONArray getVideoPlayUrls(String savedFileId) {
        return getVideoPlayUrls(savedFileId, null);
    }

    private JSONArray getVideoPlayUrls(String savedFileId, String altFileId) {
        String[] fidsToTry;
        if (altFileId != null && !altFileId.equals(savedFileId)) {
            fidsToTry = new String[]{savedFileId, altFileId};
        } else {
            fidsToTry = new String[]{savedFileId};
        }
        for (String fid : fidsToTry) {
            if (TextUtils.isEmpty(fid)) continue;
            JSONArray result = getVideoPlayQualities(fid);
            if (result != null) return result;
            SpiderDebug.log("Quark getVideoPlayUrls: fid=" + fid + " failed, trying next");
        }
        return null;
    }

    private JSONArray getVideoPlayQualities(String fid) {
        try {
            Map<String, String> headers = new HashMap<>();
            if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Referer", "https://pan.quark.cn/");
            headers.put("User-Agent", UA);
            String url = API_BASE + "/1/clouddrive/file/play?pr=ucpro&fr=pc"
                    + "&fid=" + URLEncoder.encode(fid, "UTF-8")
                    + "&resolution=low,normal,high,super,2k,4k"
                    + "&supports=fmp4_av,m3u8,dolby_vision";
            String resp = OkHttp.string(url, headers);
            if (TextUtils.isEmpty(resp)) return null;
            JSONObject json = new JSONObject(resp);
            int code = json.optInt("status", json.optInt("code", -1));
            if (code != 0 && code != 200) {
                SpiderDebug.log("Quark getVideoPlayQualities: api error code=" + code + " fid=" + fid);
                return null;
            }
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            JSONArray videoList = data.optJSONArray("video_list");
            if (videoList == null || videoList.length() == 0) return null;
            JSONArray out = new JSONArray();
            for (int i = 0; i < videoList.length(); i++) {
                JSONObject item = videoList.optJSONObject(i);
                if (item == null) continue;
                String resolution = item.optString("resolution", "");
                String playUrl = item.optString("url", "");
                if (TextUtils.isEmpty(resolution) || TextUtils.isEmpty(playUrl)) continue;
                JSONObject q = new JSONObject();
                String label = resolution;
                if ("raw".equals(resolution)) label = "RAW";
                else if ("low".equals(resolution)) label = "普画";
                else if ("normal".equals(resolution)) label = "标清";
                else if ("high".equals(resolution)) label = "高清";
                else if ("super".equals(resolution)) label = "超清";
                else if ("2k".equals(resolution)) label = "2K";
                else if ("4k".equals(resolution)) label = "4K";
                q.put("name", label);
                q.put("url", playUrl);
                out.put(q);
            }
            if (out.length() > 0) {
                return out;
            }
        } catch (Exception e) {
            SpiderDebug.log("Quark getVideoPlayQualities error: " + e.getMessage());
        }
        return null;
    }

    private JSONObject buildMultiQualityResult(String downloadUrl, String savedFileId, String originalFileId) throws Exception {
        JSONObject result = new JSONObject();
        result.put("parse", 0);
        result.put("jx", 0);
        result.put("proxy", "/proxy/?do=quark");
        JSONObject respHeaders = new JSONObject();
        respHeaders.put("Referer", "https://pan.quark.cn/");
        respHeaders.put("User-Agent", UA);
        if (!TextUtils.isEmpty(cookie)) {
            respHeaders.put("Cookie", cookie);
        }
        result.put("header", respHeaders);
        JSONArray qualities = getVideoPlayUrls(savedFileId, originalFileId);
        String proxyUrl = "http://127.0.0.1:5575/proxy?thread=10&chunkSize=256&url=" + URLEncoder.encode(downloadUrl, "UTF-8");
        JSONArray urls = new JSONArray();
        urls.put("\u4EE3\u7406RAW");
        urls.put(proxyUrl);
        urls.put("RAW");
        urls.put(downloadUrl);
        if (qualities != null) {
            for (int i = 0; i < qualities.length(); i++) {
                JSONObject q = qualities.getJSONObject(i);
                urls.put(q.getString("name"));
                urls.put(q.getString("url"));
            }
        }
        result.put("url", urls);
        return result;
    }

    public JSONObject getVod(String url) {
        try {
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
