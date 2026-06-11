package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DriveQRCodeUtil {

    private static final long SESSION_TTL = 5 * 60 * 1000;
    private static final Map<String, SessionData> sessions = new HashMap<>();

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_SCANNED = "SCANNED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final String UCLIKE_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/72.0.3626.81 Safari/537.36 SE 2.X MetaSr 1.0";
    private static final String ALI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String PAN115_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String BAIDU_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.61 Safari/537.36";

    private static final String QUARK_CLIENT_ID = "532";
    private static final String UC_CLIENT_ID = "381";
    private static final String QUARK_VERSION = "1.2";
    private static final String UC_VERSION = "1.2";

    private static class SessionData {
        String platform;
        long expireAt;
        Map<String, String> data;

        SessionData(String platform, Map<String, String> data) {
            this.platform = platform;
            this.expireAt = System.currentTimeMillis() + SESSION_TTL;
            this.data = new HashMap<>(data);
        }
    }

    private static synchronized String createSession(String platform, Map<String, String> data) {
        cleanup();
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new SessionData(platform, data));
        return token;
    }

    private static synchronized SessionData getSession(String queryToken, String expectedPlatform) {
        cleanup();
        SessionData session = sessions.get(queryToken);
        if (session == null) return null;
        if (session.expireAt <= System.currentTimeMillis()) {
            sessions.remove(queryToken);
            return null;
        }
        if (expectedPlatform != null && !expectedPlatform.equals(session.platform)) return null;
        return session;
    }

    private static synchronized void deleteSession(String queryToken) {
        sessions.remove(queryToken);
    }

    private static synchronized void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, SessionData>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAt <= now) it.remove();
        }
    }

    public static JSONObject generateQRCode(String driveKey) throws Exception {
        switch (driveKey) {
            case "quark": return generateQuarkQR();
            case "uc": return generateUcQR();
            case "ali": return generateAliQR();
            case "115": return generate115QR();
            case "baidu": return generateBaiduQR();
            default: throw new Exception("unsupported drive: " + driveKey);
        }
    }

    public static JSONObject checkQRStatus(String driveKey, String queryToken) throws Exception {
        switch (driveKey) {
            case "quark": return checkUcLikeStatus("quark", queryToken);
            case "uc": return checkUcLikeStatus("uc", queryToken);
            case "ali": return checkAliStatus(queryToken);
            case "115": return check115Status(queryToken);
            case "baidu": return checkBaiduStatus(queryToken);
            default: throw new Exception("unsupported drive: " + driveKey);
        }
    }

    // ========== Helpers ==========

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String pickCookies(Map<String, List<String>> respHeaders) {
        if (respHeaders == null) return "";
        List<String> setCookie = respHeaders.get("Set-Cookie");
        if (setCookie == null) setCookie = respHeaders.get("set-cookie");
        if (setCookie == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String c : setCookie) {
            String val = c.split(";")[0].trim();
            if (!val.isEmpty()) sb.append(val).append("; ");
        }
        return sb.toString();
    }

    private static String pickCookieValue(Map<String, List<String>> respHeaders, String name) {
        String raw = extractSetCookieName(respHeaders, name);
        if (TextUtils.isEmpty(raw)) return "";
        return extractCookieValue(raw, name);
    }

    private static String extractCookieValue(String cookieText, String name) {
        if (TextUtils.isEmpty(cookieText)) return "";
        String pattern = name.replaceAll("([.*+?^${}()|\\[\\]\\\\])", "\\\\$1") + "=([^;]+)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cookieText);
        return m.find() ? m.group(1) : "";
    }

    private static String extractSetCookieName(Map<String, List<String>> respHeaders, String name) {
        if (respHeaders == null) return "";
        List<String> setCookie = respHeaders.get("Set-Cookie");
        if (setCookie == null) setCookie = respHeaders.get("set-cookie");
        if (setCookie == null) return "";
        String lower = (";" + name + "=").toLowerCase();
        for (String c : setCookie) {
            if ((";" + c).toLowerCase().contains(lower)) {
                return c.split(";")[0].trim();
            }
        }
        return "";
    }

    private static JSONObject parseBody(OkResult result) throws Exception {
        if (result == null) return null;
        String body = result.getBody();
        return TextUtils.isEmpty(body) ? null : new JSONObject(body);
    }



    // ========== Quark / UC (UcLike Protocol) ==========

    public static JSONObject generateQuarkQR() throws Exception {
        return generateUcLikeQR("quark");
    }

    public static JSONObject generateUcQR() throws Exception {
        return generateUcLikeQR("uc");
    }

    private static JSONObject generateUcLikeQR(String platform) throws Exception {
        boolean isUc = platform.equals("uc");
        String clientId = isUc ? UC_CLIENT_ID : QUARK_CLIENT_ID;
        String version = isUc ? UC_VERSION : QUARK_VERSION;
        String getTokenUrl = isUc
                ? "https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin"
                : "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin";

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UCLIKE_UA);
        if (isUc) headers.put("Referer", "https://drive.uc.cn");

        long requestTime = isUc ? System.currentTimeMillis() : 0;
        StringBuilder urlBuilder = new StringBuilder(getTokenUrl);
        urlBuilder.append("?client_id=").append(urlEncode(clientId));
        urlBuilder.append("&v=").append(urlEncode(version));
        if (isUc) urlBuilder.append("&request_id=").append(requestTime);

        OkResult result = OkHttp.getResult(urlBuilder.toString(), headers);
        JSONObject json = parseBody(result);
        if (json == null) throw new Exception(platform + " qr token response empty");

        JSONObject data = json.optJSONObject("data");
        String token = "";
        if (data != null) {
            token = data.optString("token", "");
            if (TextUtils.isEmpty(token)) {
                JSONObject members = data.optJSONObject("members");
                if (members != null) token = members.optString("token", "");
            }
        }
        if (TextUtils.isEmpty(token)) throw new Exception(platform + " no token in response");

        String qrTemplate;
        if (isUc) {
            qrTemplate = "https://su.uc.cn/1_n0ZCv?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt&token={token}&client_id={clientId}&uc_biz_str=S%3Acustom%7CC%3Atitlebar_fix";
        } else {
            qrTemplate = "https://su.quark.cn/4_eMHBJ?token={token}&client_id={clientId}&ssb=weblogin&uc_param_str=&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400";
        }

        String qrText = qrTemplate
                .replace("{token}", urlEncode(token))
                .replace("{clientId}", urlEncode(clientId));

        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("token", token);
        sessionData.put("cookies", pickCookies(result.getResp()));
        if (isUc) sessionData.put("request_id", String.valueOf(requestTime));

        String queryToken = createSession(platform, sessionData);

        JSONObject resp = new JSONObject();
        resp.put("query_token", queryToken);
        resp.put("qr_text", qrText);
        resp.put("qr_preview", "");
        return resp;
    }

    public static JSONObject checkUcLikeStatus(String platform, String queryToken) throws Exception {
        boolean isUc = platform.equals("uc");
        String clientId = isUc ? UC_CLIENT_ID : QUARK_CLIENT_ID;
        String version = isUc ? UC_VERSION : QUARK_VERSION;
        String checkUrl = isUc
                ? "https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken"
                : "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken";
        Leodanmu.log("QR checkUcLikeStatus: polling " + platform + " token=" + queryToken);

        SessionData session = getSession(queryToken, platform);
        if (session == null) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_EXPIRED);
            return resp;
        }

        String token = session.data.get("token");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UCLIKE_UA);
        if (isUc) headers.put("Referer", "https://drive.uc.cn");

        StringBuilder urlBuilder = new StringBuilder(checkUrl);
        urlBuilder.append("?token=").append(urlEncode(token));
        urlBuilder.append("&client_id=").append(urlEncode(clientId));
        urlBuilder.append("&v=").append(urlEncode(version));
        if (isUc) urlBuilder.append("&__t=").append(System.currentTimeMillis());
        String requestId = session.data.get("request_id");
        if (requestId != null) urlBuilder.append("&request_id=").append(urlEncode(requestId));

        OkResult result = OkHttp.getResult(urlBuilder.toString(), headers);
        JSONObject json = parseBody(result);
        if (json == null) {
            Leodanmu.log("QR checkUcLikeStatus: " + platform + " response null");
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_NEW);
            return resp;
        }

        int statusCode = json.optInt("status", 0);
        Leodanmu.log("QR checkUcLikeStatus: " + platform + " status=" + statusCode + " body=" + json.toString());
        if (statusCode == 2000000) {
            JSONObject dataObj = json.optJSONObject("data");
            String serviceTicket = "";
            if (dataObj != null) {
                JSONObject members = dataObj.optJSONObject("members");
                if (members != null) serviceTicket = members.optString("service_ticket", "");
            }
            String cookie = getUcLikeFullCookie(platform, serviceTicket, session.data.get("cookies"));
            deleteSession(queryToken);

            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_CONFIRMED);
            resp.put("key", platform);
            resp.put("account", cookie);
            return resp;
        }

        if (statusCode == 50004002) {
            deleteSession(queryToken);
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_EXPIRED);
            return resp;
        }

        JSONObject resp = new JSONObject();
        resp.put("status", STATUS_NEW);
        return resp;
    }

    private static String getUcLikeFullCookie(String platform, String serviceTicket, String initialCookies) throws Exception {
        boolean isUc = platform.equals("uc");
        String accountInfoUrl = isUc
                ? "https://drive.uc.cn/account/info"
                : "https://pan.quark.cn/account/info";
        String cloudApiUrl = isUc
                ? "https://pc-api.uc.cn/1/clouddrive/transfer/upload/pdir"
                : "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/dir";
        String referer = isUc ? "https://drive.uc.cn" : null;

        StringBuilder cookies = new StringBuilder();
        if (!TextUtils.isEmpty(initialCookies)) cookies.append(initialCookies);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UCLIKE_UA);
        if (referer != null) headers.put("Referer", referer);
        if (cookies.length() > 0) headers.put("Cookie", cookies.toString().trim());

        StringBuilder accUrl = new StringBuilder(accountInfoUrl);
        accUrl.append("?st=").append(urlEncode(serviceTicket));
        accUrl.append("&fr=pc&platform=pc");

        OkResult accResult = OkHttp.getResult(accUrl.toString(), headers);
        String accCookies = pickCookies(accResult.getResp());
        if (!TextUtils.isEmpty(accCookies)) {
            cookies.append(accCookies);
        }
        headers.put("Cookie", cookies.toString().trim());

        String cloudUrl;
        if (isUc) {
            cloudUrl = cloudApiUrl + "?pr=UCBrowser&fr=pc";
        } else {
            cloudUrl = cloudApiUrl + "?pr=ucpro&fr=pc&uc_param_str=&aver=1";
        }

        OkResult cloudResult;
        if (isUc) {
            Map<String, String> postHeaders = new HashMap<>(headers);
            postHeaders.put("Content-Type", "application/json");
            cloudResult = OkHttp.post(cloudUrl, "{}", postHeaders);
        } else {
            cloudResult = OkHttp.getResult(cloudUrl, headers);
        }

        String cloudCookies = pickCookies(cloudResult.getResp());
        if (!TextUtils.isEmpty(cloudCookies)) {
            cookies.append(cloudCookies);
        }

        return cookies.toString().trim();
    }

    // ========== Aliyun Drive ==========

    public static JSONObject generateAliQR() throws Exception {
        String generateUrl = "https://passport.aliyundrive.com/newlogin/qrcode/generate.do";

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ALI_UA);

        StringBuilder urlBuilder = new StringBuilder(generateUrl);
        urlBuilder.append("?appName=aliyun_drive&fromSite=52&appEntrance=web&isMobile=false");
        urlBuilder.append("&lang=zh_CN&returnUrl=&bizParams=&_bx_v=2.2.3");

        OkResult result = OkHttp.getResult(urlBuilder.toString(), headers);
        JSONObject json = parseBody(result);
        if (json == null) throw new Exception("ali qr response empty");

        JSONObject content = json.optJSONObject("content");
        JSONObject data = content != null ? content.optJSONObject("data") : null;
        if (data == null) throw new Exception("ali qr data empty");

        String codeContent = data.optString("codeContent", "");
        String ck = data.optString("ck", "");
        String t = data.optString("t", "");
        if (TextUtils.isEmpty(codeContent) || TextUtils.isEmpty(ck) || TextUtils.isEmpty(t)) {
            throw new Exception("ali qr params missing");
        }

        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("ck", ck);
        sessionData.put("t", t);
        String queryToken = createSession("ali", sessionData);

        JSONObject resp = new JSONObject();
        resp.put("query_token", queryToken);
        resp.put("qr_text", codeContent);
        resp.put("qr_preview", "");
        return resp;
    }

    public static JSONObject checkAliStatus(String queryToken) throws Exception {
        SessionData session = getSession(queryToken, "ali");
        if (session == null) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_EXPIRED);
            return resp;
        }

        String ck = session.data.get("ck");
        String t = session.data.get("t");

        String bodyStr = "ck=" + urlEncode(ck)
                + "&t=" + urlEncode(t)
                + "&appName=aliyun_drive"
                + "&appEntrance=web"
                + "&isMobile=false"
                + "&lang=zh_CN"
                + "&returnUrl="
                + "&navlanguage=zh-CN"
                + "&bizParams=";

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ALI_UA);
        headers.put("Content-Type", "application/x-www-form-urlencoded");

        String checkUrl = "https://passport.aliyundrive.com/newlogin/qrcode/query.do"
                + "?appName=aliyun_drive&fromSite=52&_bx_v=2.2.3";

        OkResult result = OkHttp.post(checkUrl, bodyStr, headers);
        JSONObject json = parseBody(result);
        if (json == null) {
            Leodanmu.log("QR checkAliStatus: response null");
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_NEW);
            return resp;
        }

        JSONObject content = json.optJSONObject("content");
        JSONObject data = content != null ? content.optJSONObject("data") : null;
        Leodanmu.log("QR checkAliStatus: " + (data != null ? data.toString() : "null"));
        if (data == null) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_NEW);
            return resp;
        }

        String qrCodeStatus = data.optString("qrCodeStatus", "").toUpperCase();
        if ("CONFIRMED".equals(qrCodeStatus)) {
            String bizExt = data.optString("bizExt", "");
            if (TextUtils.isEmpty(bizExt)) throw new Exception("ali no bizExt");
            byte[] decoded = Base64.decode(bizExt, Base64.DEFAULT);
            String bizJson = new String(decoded, "UTF-8");
            JSONObject bizObj = new JSONObject(bizJson);
            JSONObject loginResult = bizObj.optJSONObject("pds_login_result");
            String refreshToken = loginResult != null ? loginResult.optString("refreshToken", "") : "";
            if (TextUtils.isEmpty(refreshToken)) throw new Exception("ali no refreshToken in bizExt");
            deleteSession(queryToken);

            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_CONFIRMED);
            resp.put("key", "ali");
            resp.put("account", refreshToken);
            return resp;
        }

        if ("SCANED".equals(qrCodeStatus)) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_SCANNED);
            return resp;
        }
        if ("CANCELED".equals(qrCodeStatus)) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_CANCELED);
            return resp;
        }

        JSONObject resp = new JSONObject();
        resp.put("status", STATUS_NEW);
        return resp;
    }

    // ========== 115 ==========

    public static JSONObject generate115QR() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", PAN115_UA);
        headers.put("Referer", "https://115.com/");

        OkResult result = OkHttp.getResult("https://qrcodeapi.115.com/api/1.0/web/1.0/token", headers);
        JSONObject json = parseBody(result);
        if (json == null) throw new Exception("115 qr token response empty");

        JSONObject data = json.optJSONObject("data");
        if (data == null) throw new Exception("115 qr data empty");

        String qrcode = data.optString("qrcode", "");
        String uid = data.optString("uid", "");
        String time = data.optString("time", "");
        String sign = data.optString("sign", "");
        if (TextUtils.isEmpty(qrcode) || TextUtils.isEmpty(uid) || TextUtils.isEmpty(time) || TextUtils.isEmpty(sign)) {
            throw new Exception("115 qr params missing");
        }

        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("uid", uid);
        sessionData.put("time", time);
        sessionData.put("sign", sign);
        String queryToken = createSession("115", sessionData);

        JSONObject resp = new JSONObject();
        resp.put("query_token", queryToken);
        resp.put("qr_text", qrcode);
        resp.put("qr_preview", "");
        return resp;
    }

    public static JSONObject check115Status(String queryToken) throws Exception {
        SessionData session = getSession(queryToken, "115");
        if (session == null) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_EXPIRED);
            return resp;
        }

        String uid = session.data.get("uid");
        String time = session.data.get("time");
        String sign = session.data.get("sign");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", PAN115_UA);
        headers.put("Referer", "https://115.com/");

        String checkUrl = "https://qrcodeapi.115.com/get/status/?_=" + (System.currentTimeMillis() / 1000)
                + "&sign=" + urlEncode(sign)
                + "&time=" + urlEncode(time)
                + "&uid=" + urlEncode(uid);

        OkResult result = OkHttp.getResult(checkUrl, headers);
        JSONObject json = parseBody(result);
        if (json == null) {
            Leodanmu.log("QR check115Status: response null");
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_NEW);
            return resp;
        }

        JSONObject data = json.optJSONObject("data");
        Leodanmu.log("QR check115Status: " + (data != null ? data.toString() : "null"));
        if (data == null) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_NEW);
            return resp;
        }

        int statusValue = data.optInt("status", -1);
        if (statusValue == 2) {
            Map<String, String> loginHeaders = new HashMap<>();
            loginHeaders.put("User-Agent", PAN115_UA);
            loginHeaders.put("Content-Type", "application/x-www-form-urlencoded");
            loginHeaders.put("Referer", "https://115.com/");

            String loginBody = "account=" + urlEncode(uid) + "&app=android";
            OkResult loginResult = OkHttp.post("https://passportapi.115.com/app/1.0/android/1.0/login/qrcode", loginBody, loginHeaders);
            JSONObject loginJson = parseBody(loginResult);
            if (loginJson == null || loginJson.optInt("state", 0) != 1) {
                String msg = loginJson != null ? loginJson.optString("message", "unknown") : "unknown";
                throw new Exception("115 login failed: " + msg);
            }

            String cookie = pickCookies(loginResult.getResp());
            deleteSession(queryToken);

            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_CONFIRMED);
            resp.put("key", "a115");
            resp.put("account", cookie);
            return resp;
        }

        if (statusValue == 1) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_SCANNED);
            return resp;
        }
        if (statusValue == 0) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_NEW);
            return resp;
        }

        deleteSession(queryToken);
        JSONObject resp = new JSONObject();
        resp.put("status", STATUS_EXPIRED);
        return resp;
    }

    // ========== Baidu ==========

    public static JSONObject generateBaiduQR() throws Exception {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                + Long.toHexString(System.currentTimeMillis());
        String t3 = String.valueOf(System.currentTimeMillis());
        String t1 = String.valueOf(System.currentTimeMillis() / 1000);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BAIDU_UA);
        headers.put("Referer", "https://pan.baidu.com/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        StringBuilder urlBuilder = new StringBuilder("https://passport.baidu.com/v2/api/getqrcode");
        urlBuilder.append("?lp=pc&qrloginfrom=pc");
        urlBuilder.append("&gid=").append(urlEncode(requestId));
        urlBuilder.append("&apiver=v3");
        urlBuilder.append("&tt=").append(urlEncode(t3));
        urlBuilder.append("&tpl=netdisk");
        urlBuilder.append("&logPage=traceId%3Apc_loginv5_").append(t1).append("%2ClogPage%3Aloginv5");
        urlBuilder.append("&_=").append(urlEncode(t3));

        OkResult result = OkHttp.getResult(urlBuilder.toString(), headers);
        JSONObject json = parseBody(result);
        if (json == null) throw new Exception("baidu qr response empty");

        JSONObject data = json.optJSONObject("data");
        if (data == null) data = json;
        String imgurl = data.optString("imgurl", "");
        String sign = data.optString("sign", "");
        if (TextUtils.isEmpty(imgurl) || TextUtils.isEmpty(sign)) {
            throw new Exception("baidu qr params missing");
        }

        String qrImageUrl;
        if (imgurl.startsWith("http")) {
            qrImageUrl = imgurl;
        } else {
            qrImageUrl = "https://" + imgurl;
        }

        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("t1", t1);
        sessionData.put("t3", t3);
        sessionData.put("channelId", sign);
        sessionData.put("requestId", requestId);
        String queryToken = createSession("baidu", sessionData);

        JSONObject resp = new JSONObject();
        resp.put("query_token", queryToken);
        resp.put("qr_image_url", qrImageUrl);
        resp.put("qr_preview", qrImageUrl);
        return resp;
    }

    public static JSONObject checkBaiduStatus(String queryToken) throws Exception {
        SessionData session = getSession(queryToken, "baidu");
        if (session == null) {
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_EXPIRED);
            return resp;
        }

        String channelId = session.data.get("channelId");
        String requestId = session.data.get("requestId");
        String t1 = session.data.get("t1");
        String t3 = session.data.get("t3");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BAIDU_UA);
        headers.put("Referer", "https://pan.baidu.com/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        StringBuilder checkUrl = new StringBuilder("https://passport.baidu.com/channel/unicast");
        checkUrl.append("?channel_id=").append(urlEncode(channelId));
        checkUrl.append("&gid=").append(urlEncode(requestId));
        checkUrl.append("&tpl=netdisk&_sdkFrom=1&apiver=v3");
        checkUrl.append("&tt=").append(urlEncode(t3));
        checkUrl.append("&_=").append(urlEncode(t3));

        OkResult result = OkHttp.getResult(checkUrl.toString(), headers);
        JSONObject json = parseBody(result);
        if (json == null) {
            Leodanmu.log("QR checkBaiduStatus: response null");
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_NEW);
            return resp;
        }

        JSONObject data = json.optJSONObject("data");
        if (data == null) data = json;
        Leodanmu.log("QR checkBaiduStatus: " + json.toString());

        String channelV = data.optString("channel_v", "");
        if (!TextUtils.isEmpty(channelV)) {
            JSONObject parsed;
            try {
                parsed = new JSONObject(channelV);
            } catch (Exception e) {
                JSONObject resp = new JSONObject();
                resp.put("status", STATUS_NEW);
                return resp;
            }
            String v = parsed.optString("v", "");
            if (!TextUtils.isEmpty(v)) {
                String cookie = getBaiduCookie(v, t1, t3);
                String bduss = extractCookieValue(cookie, "BDUSS");
                if (TextUtils.isEmpty(bduss)) bduss = extractCookieValue(cookie, "BDUSS_BFESS");
                deleteSession(queryToken);

                JSONObject resp = new JSONObject();
                resp.put("status", STATUS_CONFIRMED);
                resp.put("key", "baidu");
                resp.put("account", cookie);
                resp.put("bduss", bduss);
                return resp;
            }
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_SCANNED);
            return resp;
        }

        if (data.has("data")) {
            deleteSession(queryToken);
            JSONObject resp = new JSONObject();
            resp.put("status", STATUS_EXPIRED);
            return resp;
        }

        JSONObject resp = new JSONObject();
        resp.put("status", STATUS_NEW);
        return resp;
    }

    private static String getBaiduCookie(String bduss, String t1, String t3) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BAIDU_UA);
        headers.put("Referer", "https://pan.baidu.com/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        StringBuilder loginUrl = new StringBuilder("https://passport.baidu.com/v3/login/main/qrbdusslogin");
        loginUrl.append("?v=").append(urlEncode(t3));
        loginUrl.append("&bduss=").append(urlEncode(bduss));
        loginUrl.append("&u=").append(urlEncode("https://pan.baidu.com/disk/main#/index?category=all"));
        loginUrl.append("&loginVersion=v5&qrcode=1&tpl=netdisk&maskId=&fileId=");
        loginUrl.append("&apiver=v3&tt=").append(urlEncode(t3));
        loginUrl.append("&traceid=&time=").append(urlEncode(t1));
        loginUrl.append("&alg=v3&elapsed=1");

        OkResult loginResult = OkHttp.getResult(loginUrl.toString(), headers);
        JSONObject loginJson = parseBody(loginResult);
        String extractedBduss = "";
        String stoken = "";

        if (loginJson != null) {
            JSONObject loginData = loginJson.optJSONObject("data");
            if (loginData == null) loginData = loginJson;
            extractedBduss = findValueByKey(loginData, "bduss");
            stoken = findValueByKey(loginData, "stoken");
        }

        if (TextUtils.isEmpty(extractedBduss)) {
            extractedBduss = pickCookieValue(loginResult.getResp(), "BDUSS");
        }
        if (TextUtils.isEmpty(extractedBduss)) {
            extractedBduss = pickCookieValue(loginResult.getResp(), "BDUSS_BFESS");
        }
        if (TextUtils.isEmpty(stoken)) {
            stoken = pickCookieValue(loginResult.getResp(), "STOKEN");
        }
        if (TextUtils.isEmpty(stoken)) {
            stoken = pickCookieValue(loginResult.getResp(), "STOKEN_BFESS");
        }
        if (TextUtils.isEmpty(extractedBduss)) {
            throw new Exception("baidu scan success but no BDUSS");
        }

        String ubi = "";
        String ubiRaw = findValueByKey(loginJson, "ubi");
        if (!TextUtils.isEmpty(ubiRaw)) ubi = urlEncode(ubiRaw);

        StringBuilder cookieStr = new StringBuilder();
        cookieStr.append("BDUSS=").append(extractedBduss).append("; ");
        if (!TextUtils.isEmpty(stoken)) cookieStr.append("STOKEN=").append(stoken).append("; ");
        if (!TextUtils.isEmpty(ubi)) cookieStr.append("UBI=").append(ubi).append("; ");
        cookieStr.append("BDUSS_BFESS=").append(extractedBduss).append("; ");
        if (!TextUtils.isEmpty(stoken)) cookieStr.append("STOKEN_BFESS=").append(stoken).append("; ");
        if (!TextUtils.isEmpty(ubi)) cookieStr.append("UBI_BFESS=").append(ubi).append("; ");
        cookieStr.append("PTOKEN=; PTOKEN_BFESS=; newlogin=1; ");

        // auth redirect
        Map<String, String> authHeaders = new HashMap<>();
        authHeaders.put("User-Agent", BAIDU_UA);
        authHeaders.put("Referer", "https://pan.baidu.com/");
        authHeaders.put("Cookie", cookieStr.toString());

        String authUrl = "https://passport.baidu.com/v3/login/api/auth/?return_type=5&tpl=netdisk&u="
                + urlEncode("https://pan.baidu.com/disk/home");

        OkResult authResult = OkHttp.getResult(authUrl, authHeaders);
        Map<String, List<String>> authRespHeaders = authResult.getResp();
        List<String> location = authRespHeaders.get("Location");
        if (location == null) location = authRespHeaders.get("location");

        if (location != null && !location.isEmpty()) {
            String redirectUrl = location.get(0);
            OkResult finalResult = OkHttp.getResult(redirectUrl, authHeaders);
            String finalBduss = pickCookieValue(finalResult.getResp(), "BDUSS");
            if (TextUtils.isEmpty(finalBduss)) finalBduss = pickCookieValue(finalResult.getResp(), "BDUSS_BFESS");
            if (TextUtils.isEmpty(finalBduss)) finalBduss = extractedBduss;
            String finalStokenCookie = extractSetCookieName(finalResult.getResp(), "STOKEN_BFESS");
            if (TextUtils.isEmpty(finalStokenCookie)) finalStokenCookie = extractSetCookieName(finalResult.getResp(), "STOKEN");

            StringBuilder result = new StringBuilder();
            result.append("BDUSS=").append(finalBduss).append("; ");
            if (!TextUtils.isEmpty(finalStokenCookie)) result.append(finalStokenCookie).append("; ");

            List<String> setCookies = finalResult.getResp().get("Set-Cookie");
            if (setCookies == null) setCookies = finalResult.getResp().get("set-cookie");
            if (setCookies != null) {
                String[] extraNames = {"BAIDUID", "BIDUPSID", "PSTM"};
                for (String name : extraNames) {
                    String val = pickCookieValue(finalResult.getResp(), name);
                    if (!TextUtils.isEmpty(val)) {
                        result.append(name).append("=").append(val).append("; ");
                    }
                }
            }
            return result.toString();
        }

        StringBuilder result = new StringBuilder();
        result.append("BDUSS=").append(extractedBduss).append("; ");
        if (!TextUtils.isEmpty(stoken)) result.append("STOKEN=").append(stoken).append("; ");
        return result.toString();
    }

    private static String findValueByKey(JSONObject obj, String targetKey) {
        if (obj == null || TextUtils.isEmpty(targetKey)) return "";
        String lower = targetKey.toLowerCase();
        java.util.LinkedList<Object> queue = new java.util.LinkedList<>();
        queue.add(obj);
        while (!queue.isEmpty()) {
            Object node = queue.poll();
            if (node instanceof JSONObject) {
                JSONObject jObj = (JSONObject) node;
                Iterator<String> keys = jObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = jObj.opt(key);
                    if (key.toLowerCase().equals(lower) && val instanceof String && !TextUtils.isEmpty((String) val)) {
                        return (String) val;
                    }
                    if (val instanceof JSONObject) {
                        queue.add(val);
                    } else if (val instanceof org.json.JSONArray) {
                        org.json.JSONArray arr = (org.json.JSONArray) val;
                        for (int i = 0; i < arr.length(); i++) {
                            Object item = arr.opt(i);
                            if (item instanceof JSONObject) queue.add(item);
                        }
                    }
                }
            }
        }
        return obj.optString(targetKey, "");
    }
}
