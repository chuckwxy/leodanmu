package com.github.catvod.spider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public class NetworkUtils {

    public static String robustHttpGet(String urlStr) {
        long overallStart = System.currentTimeMillis();
        for (int retry = 0; retry < 2; retry++) {
            long attemptStart = System.currentTimeMillis();
            HttpURLConnection conn = null;
            boolean httpsFallbackWithCompatTls = false;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();

                // 处理HTTPS：优先走设备默认 TLS，失败后再回退到自定义兼容 TLS
                if (conn instanceof HttpsURLConnection) {
                    Leodanmu.log("HTTPS请求优先使用设备默认TLS: " + urlStr);
                }

                conn.setRequestMethod("GET");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setInstanceFollowRedirects(true);
                // 添加这些请求头，模拟浏览器
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setRequestProperty("Cache-Control", "no-cache");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    is.close();
                    String body = new String(baos.toByteArray(), "UTF-8");
                    String preview = body.replace('\n', ' ').replace('\r', ' ');
                    if (preview.length() > 120) preview = preview.substring(0, 120);
                    Leodanmu.log("HTTP 200(" + retry + ") cost=" + (System.currentTimeMillis() - attemptStart) + "ms len=" + body.length() + ": " + urlStr + " preview=" + preview);
                    return body;
                } else if (responseCode == 404 || responseCode == 403) {
                    Leodanmu.log("HTTP " + responseCode + "(" + retry + ") cost=" + (System.currentTimeMillis() - attemptStart) + "ms: " + urlStr);
                    break; // 不重试
                } else {
                    // 其他错误码也记录一下
                    Leodanmu.log("HTTP " + responseCode + "(" + retry + ") cost=" + (System.currentTimeMillis() - attemptStart) + "ms: " + urlStr);
                }
            } catch (Exception e) {
                Leodanmu.log("网络请求失败(" + retry + ") cost=" + (System.currentTimeMillis() - attemptStart) + "ms: " + urlStr + " - " + e.getClass().getSimpleName() + ": " +
                        (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));

                if (!httpsFallbackWithCompatTls && urlStr.startsWith("https://")) {
                    try {
                        long fallbackStart = System.currentTimeMillis();
                        URL url = new URL(urlStr);
                        HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                        try {
                            httpsConn.setSSLSocketFactory(new TLSSocketFactory());
                            Leodanmu.log("HTTPS默认TLS失败后，回退到兼容TLS: " + urlStr);
                        } catch (Exception initEx) {
                            Leodanmu.log("兼容TLS工厂初始化失败: " + initEx.getClass().getSimpleName() + ": " + initEx.getMessage());
                        }
                        httpsConn.setRequestMethod("GET");
                        httpsConn.setConnectTimeout(30000);
                        httpsConn.setReadTimeout(30000);
                        httpsConn.setRequestProperty("User-Agent", "Mozilla/5.0");
                        httpsConn.setInstanceFollowRedirects(true);
                        httpsConn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        httpsConn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                        httpsConn.setRequestProperty("Connection", "keep-alive");
                        httpsConn.setRequestProperty("Cache-Control", "no-cache");
                        int responseCode = httpsConn.getResponseCode();
                        if (responseCode == 200) {
                            InputStream is = httpsConn.getInputStream();
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = is.read(buffer)) != -1) {
                                baos.write(buffer, 0, len);
                            }
                            is.close();
                            String body = new String(baos.toByteArray(), "UTF-8");
                            String preview = body.replace('\n', ' ').replace('\r', ' ');
                            if (preview.length() > 120) preview = preview.substring(0, 120);
                            Leodanmu.log("HTTPS兼容TLS回退成功 cost=" + (System.currentTimeMillis() - fallbackStart) + "ms len=" + body.length() + ": " + urlStr + " preview=" + preview);
                            httpsConn.disconnect();
                            return body;
                        } else {
                            Leodanmu.log("HTTPS兼容TLS回退HTTP " + responseCode + " cost=" + (System.currentTimeMillis() - fallbackStart) + "ms: " + urlStr);
                        }
                        httpsConn.disconnect();
                    } catch (Exception fallbackEx) {
                        Leodanmu.log("HTTPS兼容TLS回退失败: " + fallbackEx.getClass().getSimpleName() + ": " + (fallbackEx.getMessage() != null ? fallbackEx.getMessage() : fallbackEx.getClass().getName()));
                    }
                    httpsFallbackWithCompatTls = true;
                }

                if (retry < 1) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {}
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        Leodanmu.log("robustHttpGet 返回空字符串，总耗时=" + (System.currentTimeMillis() - overallStart) + "ms: " + urlStr);
        return "";
    }

    public static String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface intf = en.nextElement();
                java.util.Enumeration<java.net.InetAddress> enumIpAddr =
                        intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() &&
                            inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }
}