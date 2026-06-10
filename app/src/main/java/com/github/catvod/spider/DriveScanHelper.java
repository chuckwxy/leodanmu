package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** 复用不夜 /api/admin/drive-scan 扫码登录流程 */
public class DriveScanHelper {

    /** 生成二维码，返回 { query_token, qr_text, qr_image_url } */
    public static JSONObject generateQRCode(String buyeHost, String driveKey) {
        try {
            String url = buyeHost + "/api/admin/drive-scan/" + driveKey + "/account/loginqrcode";
            String body = OkHttp.string(url);
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            SpiderDebug.log("DriveScan QR gen error: " + e.getMessage());
            return null;
        }
    }

    /** 轮询扫码状态，返回 { status, key, account } */
    public static JSONObject checkStatus(String buyeHost, String driveKey, String queryToken) {
        try {
            String url = buyeHost + "/api/admin/drive-scan/" + driveKey + "/account/scancheck";
            JSONObject req = new JSONObject();
            req.put("checkToken", queryToken);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            OkResult result = OkHttp.post(url, req.toString(), headers);
            if (result == null) return null;
            String body = result.getBody();
            if (TextUtils.isEmpty(body)) return null;
            return new JSONObject(body);
        } catch (Exception e) {
            SpiderDebug.log("DriveScan check error: " + e.getMessage());
            return null;
        }
    }

    /** 从扫码结果提取账号字符串（cookie/token） */
    public static String extractAccount(JSONObject result) {
        if (result == null) return "";
        Object account = result.opt("account");
        if (account instanceof String) return (String) account;
        if (account instanceof JSONObject) {
            JSONObject obj = (JSONObject) account;
            return obj.optString("cookie", "");
        }
        return "";
    }
}
