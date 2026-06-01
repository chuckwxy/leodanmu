package com.github.catvod.spider;

import android.app.Activity;
import android.text.TextUtils;
import com.github.catvod.spider.entity.DanmakuItem;
import com.google.gson.Gson;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class WebServer extends NanoHTTPD {

    public static int sPort = 9888;

    /** 自动检测可用端口并启动 WebServer */
    public static WebServer startWithAutoPort() {
        for (int port = 9888; port <= 9892; port++) {
            try {
                WebServer ws = new WebServer(port);
                sPort = port;
                Leodanmu.log("✅ WebServer 已启动，端口: " + port);
                return ws;
            } catch (IOException ignored) {
                Leodanmu.log("⚠️ 端口 " + port + " 被占用，尝试下一个");
            }
        }
        Leodanmu.log("❌ WebServer 启动失败: 9888-9892 均被占用");
        return null;
    }

    // 【修复】使用固定的Token，确保手机端和TV端一致
    private static final String FIXED_REMOTE_TOKEN = "default_remote_input";

    // 存储远程输入的关键词
    private static final Map<String, RemoteInputData> remoteInputMap = Collections.synchronizedMap(new HashMap<String, RemoteInputData>());
    private static final long INPUT_EXPIRE_TIME = 5 * 60 * 1000; // 5分钟过期
    private static final ScheduledExecutorService inputCleaner = Executors.newSingleThreadScheduledExecutor();

    private static class RemoteInputData {
        String keyword;
        long timestamp;

        RemoteInputData(String keyword) {
            this.keyword = keyword;
            this.timestamp = System.currentTimeMillis();
        }
    }

    static {
        // 启动清理线程，每1分钟清理一次过期的远程输入数据
        inputCleaner.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    cleanExpiredInputs();
                } catch (Exception e) {
                    // 忽略异常，继续运行
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    // 清理过期输入的兼容方法
    private static void cleanExpiredInputs() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        // 先收集需要删除的key
        synchronized (remoteInputMap) {
            for (Map.Entry<String, RemoteInputData> entry : remoteInputMap.entrySet()) {
                if (now - entry.getValue().timestamp > INPUT_EXPIRE_TIME) {
                    toRemove.add(entry.getKey());
                }
            }

            // 删除过期的数据
            for (String key : toRemove) {
                remoteInputMap.remove(key);
            }
        }
    }

    public WebServer(int port) throws IOException {
        super(port);
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

//        Leodanmu.log("🌐 WebServer收到请求: " + uri);

        if (uri.equals("/")) {
            return newFixedLengthResponse(getMainHtml());
        }
        else if (uri.equals("/input")) {
    //        Leodanmu.log("📱 返回远程输入页面");
            return newFixedLengthResponse(getInputHtml());
        }
        else if (uri.equals("/send_input")) {
            // 接收手机端输入的关键词
            Map<String, String> params = session.getParms();
            String keyword = params.get("keyword");

    //        Leodanmu.log("📱 收到远程输入请求，关键词: " + (keyword != null ? keyword : "空"));

            if (!TextUtils.isEmpty(keyword)) {
                // 【修复】使用固定的Token
                remoteInputMap.put(FIXED_REMOTE_TOKEN, new RemoteInputData(keyword));

                Leodanmu.log("💾 保存远程输入，使用固定Token: " + FIXED_REMOTE_TOKEN + ", 关键词: " + keyword);
                Leodanmu.log("💾 当前远程输入Map大小: " + remoteInputMap.size());

                // 返回一个确认页面，告诉用户操作成功
                String responseHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                        "<style>body { font-family: sans-serif; padding: 20px; text-align: center; } .success { color: green; margin: 20px 0; }</style></head>" +
                        "<body><h2>输入成功</h2><div class='success'>已发送到TV端: " + keyword + "</div>" +
                        "<p>关键词已发送到TV端，请查看TV屏幕。</p>" +
                        "<p><a href='/'>返回搜索</a> | <a href='/input'>继续输入</a></p></body></html>";
                return newFixedLengthResponse(responseHtml);
            } else {
        //        Leodanmu.log("⚠️ 远程输入关键词为空");
                return newFixedLengthResponse("请输入有效关键词");
            }
        }
        else if (uri.equals("/get_input")) {
            // TV端轮询获取输入的关键词
            // 【修复】不再需要token参数，直接使用固定的Token
          //  Leodanmu.log("📺 TV端轮询远程输入，使用固定Token: " + FIXED_REMOTE_TOKEN);
         //   Leodanmu.log("📺 当前远程输入Map内容: " + remoteInputMap.toString());

            if (remoteInputMap.containsKey(FIXED_REMOTE_TOKEN)) {
                RemoteInputData data = remoteInputMap.get(FIXED_REMOTE_TOKEN);
                String keyword = data.keyword;
                remoteInputMap.remove(FIXED_REMOTE_TOKEN); // 获取后删除，避免重复使用

        //        Leodanmu.log("✅ 找到远程输入数据: " + keyword);
        //        Leodanmu.log("🗑️ 移除Token: " + FIXED_REMOTE_TOKEN);

                return newFixedLengthResponse(keyword);
            } else {
    //            Leodanmu.log("❌ 未找到远程输入数据");
            }
            return newFixedLengthResponse("");
        }
        else if (uri.equals("/search")) {
            Map<String, String> params = session.getParms();
            String keyword = params.get("keyword");
            Activity activity = Utils.getTopActivity();

    //        Leodanmu.log("🔍 远程搜索关键词: " + keyword);
            List<DanmakuItem> results = LeoDanmakuService.manualSearch(keyword, activity);
    //        Leodanmu.log("📊 远程搜索结果数: " + (results != null ? results.size() : 0));

            return newFixedLengthResponse(new Gson().toJson(results));
        }
        else if (uri.equals("/select")) {
            Map<String, String> params = session.getParms();
            String epIdStr = params.get("epId");
            if (epIdStr != null) {
                try {
                    int epId = Integer.parseInt(epIdStr);
                    DanmakuItem item = DanmakuManager.lastDanmakuItemMap.get(epId);
                    if (item != null) {
                        Activity activity = Utils.getTopActivity();

                //        Leodanmu.log("🖥️ 远程扫码选择弹幕: " + item.getTitleWithEp());

                        if (DanmakuScanner.lastEpisodeInfo != null) {
                            String seriesName = DanmakuScanner.lastEpisodeInfo.getSeriesName();
                            String episodeNum = DanmakuScanner.lastEpisodeInfo.getEpisodeNum();

                            if (!TextUtils.isEmpty(seriesName)) {
                                DanmakuScanner.currentSeriesName = seriesName;
                            }
                            if (!TextUtils.isEmpty(episodeNum)) {
                                DanmakuScanner.currentEpisodeNum = episodeNum;
                            }
                            DanmakuScanner.lastEpisodeChangeTime = System.currentTimeMillis();

                    //        Leodanmu.log("🔄 远程推送同步剧集信息: " + seriesName + " 第" + episodeNum + "集");

                            // 保存关键词缓存
                            String currentTitle = DanmakuScanner.lastEpisodeInfo.getEpisodeName();
                            if (!TextUtils.isEmpty(currentTitle)) {
                                // 使用弹幕标题作为缓存关键词
                                String danmakuTitle = item.title;
                                if (!TextUtils.isEmpty(danmakuTitle) && !danmakuTitle.equals(currentTitle)) {
                                    com.github.catvod.spider.danmu.SharedPreferencesService.saveSearchKeywordCache(
                                            activity, currentTitle, danmakuTitle);
                                    Leodanmu.log("💾 保存关键词映射: " + currentTitle + " -> " + danmakuTitle);
                                }
                            }
                        } else {
                    //        Leodanmu.log("⚠️ 远程推送：lastEpisodeInfo为空，尝试从弹幕标题提取信息");

                            String epTitle = item.epTitle;
                            String seriesName = DanmakuScanner.extractSeriesName(epTitle);
                            String episodeNum = DanmakuScanner.extractEpisodeNum(epTitle);

                            if (!TextUtils.isEmpty(seriesName)) {
                                DanmakuScanner.currentSeriesName = seriesName;
                            }
                            if (!TextUtils.isEmpty(episodeNum)) {
                                try {
                                    DanmakuScanner.currentEpisodeNum = String.valueOf(item.getEpId());
                                } catch (Exception e) {
                                    DanmakuScanner.currentEpisodeNum = episodeNum;
                                }
                            }

                            DanmakuScanner.lastEpisodeChangeTime = System.currentTimeMillis();
                       //     Leodanmu.log("📝 远程推送提取信息: 系列=" + seriesName + ", 集数=" + episodeNum);
                        }

                        DanmakuManager.hasAutoSearched = true;
                //        Leodanmu.log("🔒 远程推送设置 hasAutoSearched = true");

                        LeoDanmakuService.pushDanmakuDirect(item, activity, false);
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK");
                    }
                } catch (NumberFormatException e) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid epId format.");
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Danmaku not found with given epId.");
        }
        else if (uri.equals("/danmaku-cache")) {
            Map<String, String> params = session.getParms();
            String cacheKeyParam = params.get("key");
            if (TextUtils.isEmpty(cacheKeyParam)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing key.");
            }
            String cacheKey = DanmakuManager.decodeCacheKey(cacheKeyParam);
            if (TextUtils.isEmpty(cacheKey)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid key.");
            }
            String cachedXml = DanmakuManager.getCachedXmlByKey(cacheKey);
            if (TextUtils.isEmpty(cachedXml)) {
                Leodanmu.log("本地缓存未命中: key=" + cacheKey);
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cache not found.");
            }
            Activity activity = Utils.getTopActivity();
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            int offsetMs = config != null ? config.getDanmakuTimeOffsetMs() : 0;
            if (offsetMs != 0) {
                Leodanmu.log("本地缓存弹幕，时间偏移: " + DanmakuUtils.formatOffsetLabel(offsetMs));
            }
            String body = DanmakuUtils.applyTimeOffset(cachedXml, offsetMs);
            Response response = newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", body);
            response.addHeader("Access-Control-Allow-Origin", "*");
            return response;
        }
        else if (uri.equals("/danmaku")) {
            Map<String, String> params = session.getParms();
            String danmakuUrl = params.get("url");

            if (TextUtils.isEmpty(danmakuUrl)) {
                String epIdStr = params.get("epId");
                if (!TextUtils.isEmpty(epIdStr)) {
                    try {
                        int epId = Integer.parseInt(epIdStr);
                        DanmakuItem item = DanmakuManager.lastDanmakuItemMap.get(epId);
                        if (item != null) {
                            danmakuUrl = item.getDanmakuUrl();
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            if (TextUtils.isEmpty(danmakuUrl)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing danmaku url.");
            }

            String xml = NetworkUtils.robustHttpGet(danmakuUrl);
            if (TextUtils.isEmpty(xml)) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Fetch danmaku failed.");
            }

            Activity activity = Utils.getTopActivity();
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            int offsetMs = config != null ? config.getDanmakuTimeOffsetMs() : 0;
            if (offsetMs != 0) {
                Leodanmu.log("本地弹幕代理收到请求，时间偏移: " + DanmakuUtils.formatOffsetLabel(offsetMs));
            }
            String body = DanmakuUtils.applyTimeOffset(xml, offsetMs);
            Response response = newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", body);
            response.addHeader("Access-Control-Allow-Origin", "*");
            return response;
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
    }

    private String getMainHtml() {
        return "<!DOCTYPE html><html><head><title>Leo弹幕搜索</title><meta name='viewport' content='width=device-width, initial-scale=1.0'><meta charset='UTF-8'><style>" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f0f2f5; margin: 0; padding: 10px; }" +
                ".container { max-width: 800px; margin: 0 auto; background-color: #fff; padding: 10px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); position: relative; } " +  // 添加 position: relative

                // 标题居中
                "h1 { color: #333; text-align: center; font-size: 1.2em; margin: 5px 0 10px 0; } " +

                // 按钮绝对定位在容器右上角
                ".remote-input-btn { background-color: #28a745; color: white; border: none; padding: 10px 15px; border-radius: 4px; cursor: pointer; font-size: 14px; position: absolute; top: 10px; right: 10px; }" +
                ".remote-input-btn:hover { background-color: #218838; }" +
                ".search-box { display: flex; margin-bottom: 8px; align-items: center; } " +
                "#keyword { flex-grow: 1; border: 1px solid #ccc; border-radius: 4px; padding: 10px; font-size: 14px; } " +
                "#reverseBtn { background-color: #6c757d; color: white; border: none; padding: 10px; border-radius: 4px; cursor: pointer; font-size: 14px; margin: 0 4px; } " +
                "#reverseBtn.active { background-color: #28a745; } " +
                "#searchBtn { background-color: #007bff; color: white; border: none; padding: 10px 15px; border-radius: 4px; cursor: pointer; font-size: 14px; margin-left: 4px; } " +
                "#searchBtn:hover { background-color: #0056b3; } " +
                ".tab-container { display: flex; overflow-x: auto; margin: 8px 0; padding: 4px 0; background-color: #f8f9fa; border-radius: 4px; } " +
                ".tab-btn { flex-shrink: 0; background-color: #6c757d; color: white; border: none; padding: 8px 12px; margin: 0 2px; border-radius: 4px; cursor: pointer; font-size: 14px; white-space: nowrap; } " +
                ".tab-btn.active { background-color: #007bff; } " +
                "#results { margin-top: 10px; max-height: 70vh; overflow-y: auto; padding-right: 5px; } " +
                ".result-group { margin-bottom: 15px; } " +
                ".result-title { font-weight: bold; margin-bottom: 8px; color: #495057; font-size: 1.1em; cursor: pointer; padding: 10px; background-color: #e9ecef; border-radius: 4px; } " +
                ".result-item { background-color: #f8f9fa; padding: 12px; border: 1px solid #dee2e6; border-radius: 4px; margin-bottom: 8px; cursor: pointer; font-size: 14px; display: none; } " +
                ".result-item:hover { background-color: #e9ecef; } " +
                ".result-info { color: #6c757d; font-size: 0.9em; margin-top: 4px; } " +
                ".no-results { text-align: center; padding: 20px; color: #6c757d; } " +
                "</style></head><body>" +
                "<div class='container'>" +
                "<div class='header-buttons'>" +
                "<h1>Leo弹幕搜索</h1>" +
                "<button class='remote-input-btn' onclick=\"window.open('/input', '_blank')\">📱 远程输入</button>" +
                "</div>" +
                "<div class='search-box'>" +
                "<button id='reverseBtn' onclick='toggleOrder()'>升序</button>" +
                "<input type='text' id='keyword' placeholder='输入关键词搜索弹幕...' autofocus>" +
                "<button id='searchBtn' onclick='search()'>搜索</button>" +
                "</div>" +
                "<div class='tab-container' id='tabContainer'></div>" +
                "<div id='results'></div>" +
                "</div>" +
                "<script>" +
                "let isReversed = false;" +
                "let groupedResults = {};" +
                "let currentTab = '';" +
                "" +
                "function toggleOrder() {" +
                "  isReversed = !isReversed;" +
                "  const reverseBtn = document.getElementById('reverseBtn');" +
                "  reverseBtn.textContent = isReversed ? '倒序' : '升序';" +
                "  reverseBtn.classList.toggle('active', isReversed);" +
                "  if (currentTab) {" +
                "    showResultsForTab(currentTab);" +
                "  }" +
                "}" +
                "" +
                "function search() {" +
                "  const keyword = document.getElementById('keyword').value.trim();" +
                "  if (!keyword) { alert('请输入关键词'); return; }" +
                "  const resultsDiv = document.getElementById('results');" +
                "  resultsDiv.innerHTML = '<div class=\\'no-results\\'>正在搜索...</div>';" +
                "  fetch('/search?keyword=' + encodeURIComponent(keyword))" +
                "    .then(response => response.json())" +
                "    .then(data => {" +
                "      groupedResults = {};" +
                "      data.forEach(item => {" +
                "        const from = item.from || '默认';" +
                "        if (!groupedResults[from]) groupedResults[from] = [];" +
                "        groupedResults[from].push(item);" +
                "      });" +
                "      renderTabs();" +
                "    })" +
                "    .catch(error => {" +
                "      console.error('搜索错误:', error);" +
                "      resultsDiv.innerHTML = '<div class=\\'no-results\\'>搜索失败: ' + error.message + '</div>';" +
                "    });" +
                "}" +
                "" +
                "function renderTabs() {" +
                "  const tabContainer = document.getElementById('tabContainer');" +
                "  tabContainer.innerHTML = '';" +
                "  const tabs = Object.keys(groupedResults).sort();" +
                "  if (tabs.length === 0) { document.getElementById('results').innerHTML = '<div class=\\'no-results\\'>未找到结果</div>'; return; }" +
                "  tabs.forEach((tabName, index) => {" +
                "    const tabBtn = document.createElement('button');" +
                "    tabBtn.className = 'tab-btn';" +
                "    tabBtn.textContent = tabName;" +
                "    tabBtn.onclick = () => {" +
                "      currentTab = tabName;" +
                "      document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));" +
                "      tabBtn.classList.add('active');" +
                "      showResultsForTab(tabName);" +
                "    };" +
                "    tabContainer.appendChild(tabBtn);" +
                "  });" +
                "  currentTab = tabs[0];" +
                "  tabContainer.children[0].classList.add('active');" +
                "  showResultsForTab(currentTab);" +
                "}" +
                "" +
                "function showResultsForTab(tabName) {" +
                "  const resultsDiv = document.getElementById('results');" +
                "  resultsDiv.innerHTML = '';" +
                "  let items = groupedResults[tabName] || [];" +
                "  if (isReversed) items = [...items].reverse();" +
                "  if (items.length === 0) { resultsDiv.innerHTML = '<div class=\\'no-results\\'>该来源下无结果</div>'; return; }" +
                "  const animeGroups = {};" +
                "  items.forEach(item => {" +
                "    const animeTitle = item.animeTitle || item.title;" +
                "    if (!animeGroups[animeTitle]) animeGroups[animeTitle] = [];" +
                "    animeGroups[animeTitle].push(item);" +
                "  });" +
                "  Object.keys(animeGroups).sort().forEach(animeTitle => {" +
                "    const groupDiv = document.createElement('div');" +
                "    groupDiv.className = 'result-group';" +
                "    const titleDiv = document.createElement('div');" +
                "    titleDiv.className = 'result-title';" +
                "    titleDiv.textContent = `${animeTitle} (${animeGroups[animeTitle].length}集)`;" +
                "    titleDiv.onclick = () => {" +
                "      const subItems = groupDiv.querySelectorAll('.result-item');" +
                "      subItems.forEach(subItem => {" +
                "        subItem.style.display = subItem.style.display === 'block' ? 'none' : 'block';" +
                "      });" +
                "    };" +
                "    groupDiv.appendChild(titleDiv);" +
                "    animeGroups[animeTitle].forEach(item => {" +
                "      const div = document.createElement('div');" +
                "      div.className = 'result-item';" +
                "      div.innerHTML = `<div>${item.title}</div><div class='result-info'>${item.epTitle || ''}</div>`;" +
                "      div.onclick = (e) => { e.stopPropagation(); select(item.epId); };" +
                "      groupDiv.appendChild(div);" +
                "    });" +
                "    resultsDiv.appendChild(groupDiv);" +
                "  });" +
                "}" +
                "" +
                "function select(epId) {" +
                "  fetch('/select?epId=' + epId)" +
                "    .then(response => {" +
                "      if (response.ok) { alert('弹幕推送成功!'); } " +
                "      else { response.text().then(text => alert('弹幕推送失败: ' + text)); }" +
                "    })" +
                "    .catch(error => { console.error('推送错误:', error); alert('推送失败: ' + error.message); });" +
                "}" +
                "</script>" +
                "</body></html>";
    }

    private String getInputHtml() {
        return "<!DOCTYPE html><html><head><title>远程输入</title><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f5f5f5; margin: 0; padding: 20px; }" +
                ".container { max-width: 500px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }" +
                "h1 { text-align: center; color: #333; margin-bottom: 20px; }" +
                ".form-group { margin-bottom: 20px; }" +
                "label { display: block; margin-bottom: 8px; font-weight: bold; color: #555; }" +
                "textarea { width: 100%; padding: 12px; border: 1px solid #ddd; border-radius: 5px; font-size: 16px; resize: vertical; min-height: 100px; box-sizing: border-box; }" +
                ".btn-group { display: flex; gap: 10px; }" +
                "button { flex: 1; padding: 12px; border: none; border-radius: 5px; font-size: 16px; cursor: pointer; }" +
                ".send-btn { background-color: #007bff; color: white; }" +
                ".send-btn:hover { background-color: #0056b3; }" +
                ".back-btn { background-color: #6c757d; color: white; }" +
                ".back-btn:hover { background-color: #545b62; }" +
                "</style>" +
                "</head><body>" +
                "<div class='container'>" +
                "<h1>📱 远程输入到TV</h1>" +
                "<div class='form-group'>" +
                "<label for='keywordInput'>输入搜索关键词：</label>" +
                "<textarea id='keywordInput' placeholder='请输入要搜索的影片名称...' autofocus></textarea>" +
                "</div>" +
                "<div class='btn-group'>" +
                "<button class='back-btn' onclick=\"window.location.href='/';\">返回搜索</button>" +
                "<button class='send-btn' onclick='sendInput()'>发送到TV</button>" +
                "</div>" +
                "</div>" +
                "<script>" +
                "function sendInput() {" +
                "  const keyword = document.getElementById('keywordInput').value.trim();" +
                "  if (!keyword) {" +
                "    alert('请输入关键词');" +
                "    return;" +
                "  }" +
                "  fetch('/send_input?keyword=' + encodeURIComponent(keyword))" +
                "    .then(response => response.text())" +
                "    .then(html => {" +
                "      document.body.innerHTML = html;" +
                "    })" +
                "    .catch(error => {" +
                "      console.error('发送失败:', error);" +
                "      alert('发送失败: ' + error.message);" +
                "    });" +
                "}" +
                "// 回车键发送" +
                "document.getElementById('keywordInput').addEventListener('keydown', function(e) {" +
                "  if (e.key === 'Enter' && !e.shiftKey) {" +
                "    e.preventDefault();" +
                "    sendInput();" +
                "  }" +
                "});" +
                "</script>" +
                "</body></html>";
    }

    /** 被 DanmakuUIHelper.cleanupAllResources() 通过反射调用 */
    public static void cleanupResources() {
        synchronized (remoteInputMap) {
            remoteInputMap.clear();
        }
        Leodanmu.log("🛑 WebServer资源已清理");
    }
}
