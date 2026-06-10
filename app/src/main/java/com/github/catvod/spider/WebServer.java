package com.github.catvod.spider;

import android.app.Activity;
import android.text.TextUtils;
import com.github.catvod.spider.entity.DanmakuItem;
import com.google.gson.Gson;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;

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

    // 远程输入已改为 RemoteInputBus 直发直收，不再使用存储 + 轮询方案

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
            Map<String, String> params = session.getParms();
            String keyword = params.get("keyword");
            if (!TextUtils.isEmpty(keyword)) {
                RemoteInputBus.postSearch(keyword);
                Leodanmu.log("📱 远程输入已通过 RemoteInputBus 发送: " + keyword);
                String responseHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                        "<style>body { font-family: sans-serif; padding: 20px; text-align: center; } .success { color: green; margin: 20px 0; }</style></head>" +
                        "<body><h2>输入成功</h2><div class='success'>已发送到TV端: " + keyword + "</div>" +
                        "<p>关键词已发送到TV端，请查看TV屏幕。</p>" +
                        "<p><a href='/'>返回搜索</a> | <a href='/input'>继续输入</a></p></body></html>";
                return newFixedLengthResponse(responseHtml);
            } else {
                return newFixedLengthResponse("请输入有效关键词");
            }
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
        else if (uri.equals("/api/config_fields")) {
            return handleConfigFields();
        }
        else if (uri.equals("/config_input")) {
            String field = session.getParms().get("field");
            return newFixedLengthResponse(getConfigInputHtml(field));
        }
        else if (uri.equals("/send_config_value")) {
            Map<String, String> params = session.getParms();
            String field = params.get("field");
            String value = params.get("value");
            if (!TextUtils.isEmpty(field) && value != null) {
                RemoteInputBus.postConfig(field, value);
                Leodanmu.log("📱 配置远程输入已通过 RemoteInputBus 发送: " + field + " = " + value);
                String confirmHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                        "<style>body { font-family: sans-serif; padding: 20px; text-align: center; } .success { color: green; }</style></head>" +
                        "<body><h2>已发送</h2><div class='success'>" + field + " = " + value + "</div>" +
                        "<p><a href='/config_input'>继续配置</a></p></body></html>";
                return newFixedLengthResponse(confirmHtml);
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing field or value");
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

    private Response handleConfigFields() {
        try {
            Activity activity = Utils.getTopActivity();
            String currentIp = NetworkUtils.getLocalIpAddress();
            StringBuilder json = new StringBuilder("[");
            String[][] fields = {
                {"http_proxy", "HTTP代理"},
                {"ylhj_host", "不夜地址"},
                {"ylhj_token", "不夜Token"},
                {"api_urls", "弹幕API地址"}
            };
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) json.append(",");
                String val = "";
                if (activity != null) {
                    DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                    switch (fields[i][0]) {
                        case "http_proxy": val = config.getHttpProxyUrl(); break;
                        case "ylhj_host": val = config.getYlhjHost(); break;
                        case "ylhj_token": val = config.getYlhjToken(); break;
                        case "api_urls": val = TextUtils.join("\n", config.getApiUrls()); break;
                    }
                }
                json.append("{\"id\":\"").append(fields[i][0])
                    .append("\",\"label\":\"").append(fields[i][1])
                    .append("\",\"value\":\"").append(escapeJson(val))
                    .append("\"}");
            }
            json.append("]");
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String getConfigInputHtml(String selectedField) {
        String preselectJs = "";
        if (!TextUtils.isEmpty(selectedField)) {
            preselectJs = "const urlField = '" + selectedField.replace("'", "\\'") + "';" +
                    "setTimeout(() => {" +
                    "  const sel = document.getElementById('fieldSelect');" +
                    "  for (let i = 0; i < sel.options.length; i++) {" +
                    "    if (sel.options[i].value === urlField) { sel.selectedIndex = i; break; }" +
                    "  }" +
                    "  updateCurrentValue();" +
                    "  document.getElementById('valueInput').focus();" +
                    "}, 200);";
        }
        return "<!DOCTYPE html><html><head><title>配置远程输入</title><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f0f2f5; margin: 0; padding: 20px; }" +
                ".container { max-width: 500px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }" +
                "h1 { text-align: center; color: #333; margin-bottom: 24px; font-size: 20px; }" +
                "label { display: block; margin-bottom: 6px; font-weight: bold; color: #555; font-size: 14px; }" +
                "select, input, textarea { width: 100%; padding: 12px; border: 1px solid #ddd; border-radius: 5px; font-size: 16px; box-sizing: border-box; margin-bottom: 16px; }" +
                "textarea { min-height: 80px; resize: vertical; font-family: monospace; }" +
                ".current-value { background: #f8f9fa; padding: 10px; border-radius: 5px; margin-bottom: 16px; font-size: 13px; color: #666; word-break: break-all; }" +
                "button { width: 100%; padding: 14px; background: #007bff; color: white; border: none; border-radius: 5px; font-size: 16px; cursor: pointer; }" +
                "button:hover { background: #0056b3; }" +
                ".status { text-align: center; margin-top: 12px; font-size: 14px; color: #28a745; }" +
                ".back-link { display: block; text-align: center; margin-top: 16px; color: #6c757d; text-decoration: none; font-size: 14px; }" +
                "</style></head><body>" +
                "<div class='container'>" +
                "<h1>📱 配置远程输入</h1>" +
                "<label for='fieldSelect'>选择配置项：</label>" +
                "<select id='fieldSelect'></select>" +
                "<div class='current-value' id='currentValue'>当前值: </div>" +
                "<label for='valueInput' id='inputLabel'>输入新值：</label>" +
                "<input type='text' id='valueInput' placeholder='请输入新值...' autofocus>" +
                "<textarea id='valueTextarea' placeholder='每行一个URL...' style='display:none'></textarea>" +
                "<button onclick='send()'>发送到TV</button>" +
                "<div class='status' id='status'></div>" +
                "<a href='/' class='back-link'>返回弹幕搜索</a>" +
                "</div>" +
                "<script>" +
                "let fields = [];" +
                "fetch('/api/config_fields').then(r=>r.json()).then(data => {" +
                "  fields = data;" +
                "  const sel = document.getElementById('fieldSelect');" +
                "  fields.forEach(f => {" +
                "    const opt = document.createElement('option');" +
                "    opt.value = f.id; opt.textContent = f.label;" +
                "    sel.appendChild(opt);" +
                "  });" +
                "  updateCurrentValue();" +
                preselectJs +
                "});" +
                "document.getElementById('fieldSelect').onchange = function() {" +
                "  updateCurrentValue();" +
                "  const f = fields.find(x => x.id === this.value);" +
                "  const isMulti = f && f.id === 'api_urls';" +
                "  document.getElementById('valueInput').style.display = isMulti ? 'none' : '';" +
                "  document.getElementById('valueTextarea').style.display = isMulti ? '' : 'none';" +
                "};" +
                "function updateCurrentValue() {" +
                "  const sel = document.getElementById('fieldSelect');" +
                "  const f = fields.find(x => x.id === sel.value);" +
                "  document.getElementById('currentValue').textContent = '当前值: ' + (f ? f.value || '(空)' : '');" +
                "}" +
                "function send() {" +
                "  const field = document.getElementById('fieldSelect').value;" +
                "  const f = fields.find(x => x.id === field);" +
                "  const isMulti = f && f.id === 'api_urls';" +
                "  const value = isMulti ? document.getElementById('valueTextarea').value.trim() : document.getElementById('valueInput').value.trim();" +
                "  if (!value) { document.getElementById('status').textContent = '请输入值'; return; }" +
                "  document.getElementById('status').textContent = '发送中...';" +
                "  fetch('/send_config_value?field=' + encodeURIComponent(field) + '&value=' + encodeURIComponent(value))" +
                "    .then(r => r.text()).then(html => { document.body.innerHTML = html; })" +
                "    .catch(e => { document.getElementById('status').textContent = '发送失败: ' + e.message; });" +
                "}" +
                "document.getElementById('valueInput').addEventListener('keydown', function(e) {" +
                "  if (e.key === 'Enter') { e.preventDefault(); send(); }" +
                "});" +
                "</script></body></html>";
    }

    /** 被 DanmakuUIHelper.cleanupAllResources() 通过反射调用 */
    public static void cleanupResources() {
        Leodanmu.log("🛑 WebServer资源已清理");
    }
}
