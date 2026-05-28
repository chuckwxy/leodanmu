package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import android.util.Base64;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class XHamster extends Spider {

    private static final String HOST = "https://zh.xhamster.com";
    private static final String UA = Util.CHROME;

    private OkHttpClient client;
    private String proxyUrl = "";

    private static final Class[] DEFAULT_CATEGORIES = {
            new Class("/", "热门"),
            new Class("/hd", "高清"),
            new Class("/4k", "4K"),
            new Class("/vr", "虚拟现实"),
            new Class("/newest", "最新视频"),
            new Class("/best/weekly", "最佳视频"),
            new Class("/categories/18-year-old", "18岁"),
            new Class("/categories/teen", "青年"),
            new Class("/categories/asian", "亚洲"),
            new Class("/categories/jeans", "牛仔裤"),
            new Class("/categories/office", "办公室"),
            new Class("/categories/russian", "俄罗斯"),
            new Class("/categories/amateur", "素人"),
    };

    @Override
    public void init(Context context, String extend) {
        DanmakuConfig config = DanmakuConfigManager.getConfig(context);
        proxyUrl = config != null ? config.getHttpProxyUrl() : "";
        client = buildClient();
    }

    private OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true);
        if (!TextUtils.isEmpty(proxyUrl)) {
            try {
                URL pu = new URL(proxyUrl);
                builder.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(pu.getHost(), pu.getPort())));
            } catch (Exception ignored) {
            }
        }
        return builder.build();
    }

    private Map<String, String> getHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", HOST + "/");
        h.put("Origin", HOST);
        return h;
    }

    private String get(String path) throws Exception {
        String url = path.startsWith("http") ? path : HOST + path;
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : getHeaders().entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    private String abs(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            return new URL(new URL(HOST), url).toString();
        } catch (Exception e) {
            return url;
        }
    }

    private String cleanText(String t) {
        if (t == null) return "";
        return t.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private String validPic(String u) {
        if (TextUtils.isEmpty(u)) return "";
        if (u.equals("/") || u.equals(HOST) || u.startsWith("data:image")) return "";
        if (u.matches("(?i).*(placeholder|blank\\.gif|svg|1x1\\.).*")) return "";
        if (!u.matches("(?i).*\\.(jpg|jpeg|png|webp|avif).*") && !u.matches("(?i).*(thumb|poster|image|thumb-).*")) return "";
        String full = abs(u);
        if (full.equals(HOST) || full.equals(HOST + "/")) return "";
        if (!full.startsWith("http")) return "";
        return full;
    }

    private String formatDuration(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String[] parts = raw.split(":");
        int seconds = 0;
        try {
            if (parts.length == 2) seconds = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            else if (parts.length == 3) seconds = Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
            else seconds = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private List<Vod> parseVideoCards(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        for (Element item : doc.select(".thumb-list__item.video-thumb, [data-ecommerce-list-item][data-video-id]")) {
            Element a = item.selectFirst("a[data-role=\"thumb-link\"]");
            if (a == null) continue;
            String href = abs(a.attr("href"));
            if (!href.contains("/videos/") && !href.contains("/shorts/")) continue;

            String name = cleanText(item.select(".video-thumb-info__name").text());
            if (name.isEmpty()) name = cleanText(a.attr("title"));
            if (name.isEmpty()) name = cleanText(item.attr("title"));

            Element img = item.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = validPic(img.attr("data-poster"));
                if (pic.isEmpty()) pic = validPic(img.attr("data-src"));
                if (pic.isEmpty()) pic = validPic(img.attr("src"));
            }
            if (pic.isEmpty()) {
                String style = item.select("[style*=\"background-image\"]").attr("style");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("url\\([\"']?(.*?)[\"']?\\)").matcher(style);
                if (m.find()) pic = validPic(m.group(1));
            }

            String rawDuration = item.select("[data-role=\"video-duration\"], .duration, .video-thumb__duration, .thumb-duration").text();
            String duration = formatDuration(rawDuration);

            String remarks = duration.isEmpty() ? "" : duration;
            list.add(new Vod(href, name, pic, remarks));
        }

        if (list.isEmpty()) {
            String raw = html;
            String key = "\"videoThumbProps\":[";
            int start = raw.indexOf(key);
            if (start >= 0) {
                String tail = raw.substring(start + key.length() - 1);
                int depth = 0;
                int end = -1;
                for (int i = 0; i < tail.length(); i++) {
                    if (tail.charAt(i) == '[') depth++;
                    else if (tail.charAt(i) == ']') { depth--; if (depth == 0) { end = i; break; } }
                }
                if (end > 0) try {
                    JSONArray arr = new JSONArray(tail.substring(0, end + 1));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item2 = arr.getJSONObject(i);
                        String href2 = abs(item2.optString("pageURL", ""));
                        if (href2.isEmpty()) continue;
                        String name2 = cleanText(item2.optString("title", ""));
                        String pic2 = validPic(item2.optString("imageURL", ""));
                        if (pic2.isEmpty()) pic2 = validPic(item2.optString("thumbURL", ""));
                        String dur = formatDuration(item2.optString("duration", ""));
                        list.add(new Vod(href2, name2, pic2, dur));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return list;
    }

    private List<PlaySource> extractPlaySources(String html) {
        List<PlaySource> sources = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "window\\.__INITIALS_STATE__\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;"
        ).matcher(html);
        String stateJson = null;
        if (m.find()) stateJson = m.group(1);
        if (stateJson == null) {
            m = java.util.regex.Pattern.compile("window\\.initials\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;").matcher(html);
            if (m.find()) stateJson = m.group(1);
        }

        if (stateJson != null) try {
            JSONObject state = new JSONObject(stateJson);
            walkJson(state, sources, new java.util.HashSet<>());
        } catch (Exception ignored) {
        }

        if (sources.isEmpty()) {
            m = java.util.regex.Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").matcher(html);
            while (m.find()) sources.add(new PlaySource("HLS", m.group().replaceAll("\\\\u002F|\\\\/", "/").replaceAll("&amp;", "&")));
        }
        if (sources.isEmpty()) {
            m = java.util.regex.Pattern.compile("https?://[^\\s\"'<>\"]+\\.mp4[^\\s\"'<>\"]*").matcher(html);
            while (m.find()) sources.add(new PlaySource("MP4", m.group().replaceAll("\\\\u002F|\\\\/", "/").replaceAll("&amp;", "&")));
        }

        sources.sort((a, b) -> qualityRank(b.name) - qualityRank(a.name));
        return sources;
    }

    private void walkJson(Object obj, List<PlaySource> sources, HashSet<String> seen) {
        if (obj == null) return;
        if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.length(); i++) walkJson(arr.opt(i), sources, seen);
            return;
        }
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            JSONArray keys = jo.names();
            if (keys == null) return;
            for (int i = 0; i < keys.length(); i++) {
                String k = keys.optString(i);
                Object v = jo.opt(k);
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.contains(".m3u8") && !s.contains("preview") && !s.contains("teaser") && seen.add(s))
                        sources.add(new PlaySource("HLS", s.replaceAll("\\\\u002F|\\\\/", "/").replaceAll("&amp;", "&")));
                    if (s.contains(".mp4") && !s.contains("preview") && !s.contains("teaser") && seen.add(s))
                        sources.add(new PlaySource("MP4", s.replaceAll("\\\\u002F|\\\\/", "/").replaceAll("&amp;", "&")));
                }
                if (k.equals("mediaDefinitions") && v instanceof JSONArray) {
                    JSONArray md = (JSONArray) v;
                    for (int i = 0; i < md.length(); i++) {
                        JSONObject mdo = md.optJSONObject(i);
                        if (mdo == null) continue;
                        String vu = mdo.optString("videoUrl", "");
                        if (vu.isEmpty() || vu.contains("preview") || vu.contains("teaser")) continue;
                        String q = mdo.optString("quality", "AUTO");
                        if (seen.add(vu)) sources.add(new PlaySource("HLS " + q, vu.replaceAll("\\\\u002F|\\\\/", "/").replaceAll("&amp;", "&")));
                    }
                }
                walkJson(v, sources, seen);
            }
        }
    }

    private int qualityRank(String name) {
        String n = name.toUpperCase();
        if (n.contains("2160") || n.contains("4K")) return 2160;
        if (n.contains("1440")) return 1440;
        if (n.contains("1080")) return 1080;
        if (n.contains("720")) return 720;
        if (n.contains("480")) return 480;
        return 0;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            String html = get("/");
            List<Vod> list = parseVideoCards(html);
            return Result.string(Arrays.asList(DEFAULT_CATEGORIES), list);
        } catch (Exception e) {
            return Result.string(Arrays.asList(DEFAULT_CATEGORIES), new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String path = tid != null ? tid : "/";
            int page = 1;
            try { page = Integer.parseInt(pg); } catch (NumberFormatException ignored) {}
            if (page > 1) {
                path = tid.equals("/") ? "/?page=" + page : tid + "/" + page;
            }
            String html = get(path);
            List<Vod> list = parseVideoCards(html);
            return Result.get().vod(list).page(page, page + 1, 20, list.size()).string();
        } catch (Exception e) {
            return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = abs(ids.get(0));
            String html = get(url);

            Document doc = Jsoup.parse(html);
            String title = cleanText(doc.title());
            String pic = abs(doc.selectFirst("meta[property=\"og:image\"]") != null
                    ? doc.selectFirst("meta[property=\"og:image\"]").attr("content") : "");
            String desc = cleanText(doc.selectFirst("meta[name=\"description\"]") != null
                    ? doc.selectFirst("meta[name=\"description\"]").attr("content") : "");

            List<PlaySource> sources = extractPlaySources(html);

            StringBuilder playUrl = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) playUrl.append("#");
                PlaySource ps = sources.get(i);
                JSONObject payload = new JSONObject();
                payload.put("url", ps.url);
                payload.put("name", ps.name);
                payload.put("quality", ps.name);
                String b64 = Base64.encodeToString(payload.toString().getBytes("UTF-8"), Base64.NO_WRAP);
                playUrl.append(ps.name).append("$").append(b64);
            }

            Vod vod = new Vod(url, title, pic, desc);
            vod.setVodPlayFrom("xHamster");
            vod.setVodPlayUrl(playUrl.toString());

            return Result.string(vod);
        } catch (Exception e) {
            return Result.string(new Vod());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            String kw = java.net.URLEncoder.encode(key, "UTF-8");
            String path = "/search/" + kw;
            int page = 1;
            try { page = Integer.parseInt(pg); } catch (NumberFormatException ignored) {}
            if (page > 1) path += "?page=" + page;

            String html = get(path);
            List<Vod> list = parseVideoCards(html);
            return Result.get().vod(list).page(page, page + 1, 20, list.size()).string();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            byte[] decoded = Base64.decode(id, Base64.NO_WRAP);
            String json = new String(decoded, "UTF-8");
            JSONObject info = new JSONObject(json);
            String playUrl = info.optString("url", "");
            if (playUrl.isEmpty()) return Result.get().url("").string();

            Map<String, String> header = new HashMap<>();
            header.put("Referer", abs(playUrl));
            header.put("User-Agent", UA);
            header.put("Origin", HOST);

            return Result.get().url(playUrl).header(header).string();
        } catch (Exception e) {
            return Result.get().url("").string();
        }
    }

    private static class PlaySource {
        String name;
        String url;
        PlaySource(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
