package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaProxyServer {

    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ExecutorService acceptExecutor;
    private final ExecutorService downloadExecutor;
    private final int port;
    private final Context appContext;

    public JavaProxyServer(int port, Context context) {
        this.port = port;
        this.appContext = context;
        this.acceptExecutor = Executors.newSingleThreadExecutor();
        this.downloadExecutor = new ThreadPoolExecutor(0, 64, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(128));
    }

    public boolean startServer() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            running = true;
            ProxyManager.log("[启动] Java代理服务, 端口: " + port);
            acceptExecutor.execute(this::acceptLoop);
            return true;
        } catch (Exception e) {
            ProxyManager.log("[错误] 启动失败: " + e.getMessage());
            running = false;
            return false;
        }
    }

    public void stopServer() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        downloadExecutor.shutdownNow();
        acceptExecutor.shutdownNow();
        ProxyManager.log("[停止] Java代理服务已停止");
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                downloadExecutor.execute(() -> {
                    try {
                        handleClient(client);
                    } catch (Exception e) {
                        ProxyManager.log("处理客户端请求异常: " + e.getMessage());
                    } finally {
                        try { client.close(); } catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                if (running) ProxyManager.log("[连接] 接受异常: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) throws SocketException {
        client.setTcpNoDelay(true);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String uri = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }

            String path = uri.split("\\?")[0];
            Map<String, String> params = parseQuery(uri);
            OutputStream out = client.getOutputStream();

            if ("/".equals(path)) {
                writeSimpleResponse(out, 200, "text/plain", "ok");
            } else if ("/health".equals(path)) {
                String json = "{\"status\":\"healthy\",\"type\":\"java\",\"port\":" + port + ",\"timestamp\":\"" +
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new java.util.Date()) + "\"}";
                writeSimpleResponse(out, 200, "application/json", json);
            } else if ("/proxy".equals(path)) {
                handleProxy(out, headers, params);
            } else {
                writeSimpleResponse(out, 404, "text/plain", "Not Found");
            }
        } catch (Exception e) {
            if (running) ProxyManager.log("[客户端] 处理异常: " + e.getMessage());
        }
    }

    private void handleProxy(OutputStream out, Map<String, String> headers,
                              Map<String, String> params) {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) {
            writeSimpleResponse(out, 400, "text/plain", "url参数缺失");
            return;
        }

        String source = params.get("source");
        int threadCount = parseIntParam(params.get("thread"),
                source != null ? ProxyManager.getSourceThread(appContext, source) : ProxyManager.getDefaultThread(appContext), 8);
        int chunkSizeKB = parseIntParam(params.get("chunkSize"),
                source != null ? ProxyManager.getSourceChunkSize(appContext, source) : ProxyManager.getDefaultChunkSize(appContext), 256);
        boolean autoTune = ProxyManager.isAutoTuneEnabled(appContext);

        String rangeHeader = headers.get("range");
        long[] range = parseRange(rangeHeader);
        long startPos = range[0];
        long endPos = range[1];

        okhttp3.OkHttpClient client = buildOkHttpClient();

        Map<String, String> forwardHeaders = new HashMap<>();
        String ua = headers.get("user-agent");
        if (!TextUtils.isEmpty(ua)) forwardHeaders.put("User-Agent", ua);
        String cookie = headers.get("cookie");
        if (!TextUtils.isEmpty(cookie)) forwardHeaders.put("Cookie", cookie);
        String referer = headers.get("referer");
        if (!TextUtils.isEmpty(referer)) forwardHeaders.put("Referer", referer);

        try {
            ProxyManager.log("[代理] 启动 线程" + threadCount + " 分块" + chunkSizeKB + "KB autoTune=" + autoTune);

            // Phase 1: First chunk always 100 bytes (same as Go proxy) to avoid server minimum chunk trap
            long firstEnd = startPos + 100;

            ChunkResult firstResult = downloadChunk(client, url, forwardHeaders, startPos, firstEnd, 3);

            if (firstResult == null) {
                writeSimpleResponse(out, 500, "text/plain", "首块下载失败");
                return;
            }

            // Get file size from Content-Range of actual response
            String contentRangeHeader = firstResult.responseHeaders.get("Content-Range");
            if (contentRangeHeader == null) contentRangeHeader = firstResult.responseHeaders.get("content-range");
            long fileSize = -1;
            if (!TextUtils.isEmpty(contentRangeHeader)) {
                Matcher m = CONTENT_RANGE_PATTERN.matcher(contentRangeHeader);
                if (m.find()) fileSize = Long.parseLong(m.group(3));
            }
            if (fileSize <= 0) {
                writeSimpleResponse(out, 500, "text/plain", "未获取到文件总大小");
                return;
            }

            if (endPos <= 0) endPos = fileSize - 1;
            final long finalEndPos = endPos;

            double targetSpeedMBps = calcTargetSpeed(fileSize);

            // Build & write response header
            StringBuilder headerBuilder = new StringBuilder();
            int status = firstResult.statusCode == 206 ? 206 : 200;
            headerBuilder.append("HTTP/1.1 ").append(status).append(status == 206 ? " Partial Content" : " OK").append("\r\n");
            headerBuilder.append("Content-Range: bytes ").append(startPos).append("-").append(finalEndPos).append("/").append(fileSize).append("\r\n");
            headerBuilder.append("Accept-Ranges: bytes\r\n");
            String contentType = firstResult.responseHeaders.get("Content-Type");
            if (contentType == null) contentType = firstResult.responseHeaders.get("content-type");
            if (contentType == null) contentType = "application/octet-stream";
            headerBuilder.append("Content-Type: ").append(contentType).append("\r\n");
            for (Map.Entry<String, String> entry : firstResult.responseHeaders.entrySet()) {
                String key = entry.getKey();
                if ("Content-Range".equalsIgnoreCase(key) || "Content-Length".equalsIgnoreCase(key)
                        || "Content-Type".equalsIgnoreCase(key) || "Transfer-Encoding".equalsIgnoreCase(key)
                        || "transfer-encoding".equalsIgnoreCase(key)) continue;
                headerBuilder.append(key).append(": ").append(entry.getValue()).append("\r\n");
            }
            headerBuilder.append("Connection: close\r\n\r\n");
            out.write(headerBuilder.toString().getBytes("UTF-8"));
            out.write(firstResult.data);
            out.flush();

            // Phase 3: Batch download — parallel chunks, write in order
            long pos = startPos + firstResult.data.length;
            int tuneThread = threadCount;
            long chunkSize = (long) chunkSizeKB * 1024;
            int batchChunks = tuneThread;
            long batchChunkSize = chunkSize * batchChunks;
            int consecutiveErrors = 0;

            if (autoTune) {
                ProxyManager.log("[信息] " + String.format("%.1f", fileSize/1024.0/1024.0) + "MB" +
                        " 目标" + String.format("%.1f", targetSpeedMBps) + "MB/s" +
                        " 线程" + tuneThread + " 分块" + chunkSizeKB + "KB");
            }

            while (pos <= finalEndPos) {
                long batchT0 = System.currentTimeMillis();
                long chunkEnd = Math.min(pos + batchChunkSize, finalEndPos + 1);
                int chunksThisBatch = (int) Math.min(batchChunks,
                        (chunkEnd - pos + chunkSize - 1) / chunkSize);

                ChunkResult[] results = new ChunkResult[chunksThisBatch];
                CountDownLatch latch = new CountDownLatch(chunksThisBatch);
                AtomicBoolean hasError = new AtomicBoolean(false);

                for (int i = 0; i < chunksThisBatch; i++) {
                    long cs = pos + i * chunkSize;
                    long ce = Math.min(cs + chunkSize, finalEndPos + 1);
                    final int idx = i;
                    final long fCs = cs;
                    final long fCe = ce;
                    downloadExecutor.execute(() -> {
                        try {
                            ChunkResult r = null;
                            for (int retry = 0; retry < 3; retry++) {
                                r = downloadChunk(client, url, forwardHeaders, fCs, fCe, 3);
                                if (r != null) break;
                                try { Thread.sleep((retry + 1) * 1000L); } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                            if (r == null) hasError.set(true);
                            else results[idx] = r;
                        } catch (Exception e) {
                            hasError.set(true);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                try {
                    latch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}

                if (hasError.get()) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= 3) {
                        ProxyManager.log("[中止] 批量下载连续失败");
                        return;
                    }
                    continue;
                }
                consecutiveErrors = 0;

                long batchBytes = 0;
                for (int i = 0; i < chunksThisBatch; i++) {
                    if (results[i] != null) {
                        out.write(results[i].data);
                        batchBytes += results[i].data.length;
                        pos += results[i].data.length;
                        out.flush();
                    }
                }

                // Auto-tune every batch
                if (autoTune) {
                    long batchElapsed = System.currentTimeMillis() - batchT0;
                    double curSpeed = batchElapsed > 0 ?
                            (batchBytes / 1024.0 / 1024.0) / (batchElapsed / 1000.0) : 0;

                    if (curSpeed > 0 && targetSpeedMBps > 0) {
                        int oldThread = tuneThread;
                        int oldChunk = chunkSizeKB;
                        boolean changed = false;

                        if (curSpeed < targetSpeedMBps * 0.8) {
                            if (tuneThread < 32) {
                                tuneThread = Math.min(32, tuneThread * 2);
                                changed = true;
                            } else {
                                int newChunk = Math.min(1024, (int) (chunkSizeKB * 1.5));
                                if (newChunk != chunkSizeKB) {
                                    chunkSizeKB = newChunk;
                                    tuneThread = 8;
                                    changed = true;
                                }
                            }
                        } else if (curSpeed > targetSpeedMBps * 1.5) {
                            if (tuneThread > 4) {
                                tuneThread = Math.max(4, tuneThread / 2);
                                changed = true;
                            }
                        }

                        if (changed) {
                            chunkSize = (long) chunkSizeKB * 1024;
                            batchChunks = tuneThread;
                            batchChunkSize = chunkSize * batchChunks;
                            ProxyManager.log("[调优] " + String.format("%.1f", curSpeed) +
                                    "/" + String.format("%.1f", targetSpeedMBps) + "MB/s" +
                                    " 线程" + oldThread + "→" + tuneThread +
                                    " 分块" + oldChunk + "→" + chunkSizeKB + "KB");
                        }
                    }
                }
            }

            if (autoTune) {
                ProxyManager.log("[完成] 代理下载结束 线程" + tuneThread + " 分块" + chunkSizeKB + "KB");
            }

        } catch (Exception e) {
            String em = e.getMessage();
            if (em == null || !em.contains("ECONNRESET") && !em.contains("EPIPE")
                    && !em.contains("Broken pipe") && !em.contains("Socket closed")) {
                ProxyManager.log("[异常] 代理处理: " + em);
            }
        }
    }

    // ---- Auto-tuning helpers ----

    private static double calcTargetSpeed(long fileSize) {
        long mb = fileSize / (1024L * 1024L);
        if (mb < 100) return 1.0;
        if (mb < 500) return 2.0;
        if (mb < 2000) return 5.0;
        if (mb < 8000) return 10.0;
        if (mb < 30000) return 15.0;
        return 20.0;
    }

    private static int parseIntParam(String s, int defaultVal, int fallback) {
        if (TextUtils.isEmpty(s)) return defaultVal;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private void writeSimpleResponse(OutputStream out, int status, String contentType, String body) {
        try {
            String statusText = status == 200 ? "OK" : status == 206 ? "Partial Content" :
                    status == 400 ? "Bad Request" : status == 404 ? "Not Found" : "Internal Server Error";
            String response = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + body.getBytes("UTF-8").length + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" + body;
            out.write(response.getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            ProxyManager.log("[响应] 写异常: " + e.getMessage());
        }
    }

    private okhttp3.OkHttpClient buildOkHttpClient() {
        try {
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            final javax.net.ssl.X509TrustManager trustAll = new javax.net.ssl.X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            };
            sslContext.init(null, new javax.net.ssl.TrustManager[]{trustAll}, new java.security.SecureRandom());

            return new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS)
                    .hostnameVerifier((hostname, session) -> true)
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                    .build();
        } catch (Exception e) {
            return com.github.catvod.net.OkHttp.client();
        }
    }

    private ChunkResult downloadChunk(okhttp3.OkHttpClient client, String url,
                                       Map<String, String> headers, long start, long end, int maxRetries) {
        Exception lastErr = null;
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder().url(url).get();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    reqBuilder.header(entry.getKey(), entry.getValue());
                }
                reqBuilder.header("Range", "bytes=" + start + "-" + (end - 1));

                okhttp3.Response resp = client.newCall(reqBuilder.build()).execute();
                int status = resp.code();
                if (status == 200 || status == 206) {
                    int expected = (int) Math.min(end - start, 1024 * 1024);
                    byte[] data = readLimited(resp.body().byteStream(), expected);
                    String contentType = resp.header("Content-Type", "application/octet-stream");
                    Map<String, String> respHeaders = new HashMap<>();
                    for (String name : resp.headers().names()) {
                        respHeaders.put(name, resp.header(name, ""));
                    }
                    resp.close();
                    return new ChunkResult(data, respHeaders, status, contentType);
                }
                resp.close();
                lastErr = new Exception("状态码: " + status);
            } catch (Exception e) {
                lastErr = e;
            } catch (Error e) {
                lastErr = new Exception("Fatal: " + e.getClass().getSimpleName() + " " + e.getMessage());
                break;
            }
            if (retry < maxRetries - 1) {
                try { Thread.sleep((retry + 1) * 500L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        //ProxyManager.log("[重试" + maxRetries + "] 块 " + start + "-" + (end - 1) + " 失败: " + (lastErr != null ? lastErr.getMessage() : "unknown"));
        return null;
    }

    private static byte[] readLimited(InputStream in, int maxLen) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(maxLen, 65536));
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while (total < maxLen && (n = in.read(buf, 0, Math.min(buf.length, maxLen - total))) != -1) {
            bos.write(buf, 0, n);
            total += n;
        }
        return bos.toByteArray();
    }

    private long[] parseRange(String rangeStr) {
        if (TextUtils.isEmpty(rangeStr)) return new long[]{0, -1};
        Matcher m = RANGE_PATTERN.matcher(rangeStr);
        if (!m.find()) return new long[]{0, -1};
        long start = Long.parseLong(m.group(1));
        long end = -1;
        if (m.groupCount() >= 2 && !TextUtils.isEmpty(m.group(2))) {
            end = Long.parseLong(m.group(2));
        }
        return new long[]{start, end};
    }

    private Map<String, String> parseQuery(String uri) {
        Map<String, String> params = new HashMap<>();
        int q = uri.indexOf('?');
        if (q < 0) return params;
        String query = uri.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    params.put(pair.substring(0, eq),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return params;
    }

    private static class ChunkResult {
        final byte[] data;
        final Map<String, String> responseHeaders;
        final int statusCode;
        final String contentType;

        ChunkResult(byte[] data, Map<String, String> responseHeaders, int statusCode, String contentType) {
            this.data = data;
            this.responseHeaders = responseHeaders;
            this.statusCode = statusCode;
            this.contentType = contentType;
        }
    }
}
