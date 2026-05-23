package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WanouJuhe extends Spider {

    private String siteUrl = "https://wogg.xxooo.cf";

    private static final String[][] CATEGORIES = {
            {"44", "\u7487\u5f69"}, {"1", "\u7535\u5f71"}, {"2", "\u7535\u89c6\u5267"},
            {"3", "\u52a8\u6f2b"}, {"4", "\u7efc\u827a"}, {"5", "\u97f3\u4e50"},
            {"6", "\u77ed\u5267"}, {"46", "\u7eaa\u5f55\u7247"}
    };

    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", Util.CHROME);
        return header;
    }

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) siteUrl = extend;
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (String[] cat : CATEGORIES) classes.add(new Class(cat[0], cat[1]));
        List<Vod> list = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeader()));
            Elements items = doc.select(".module-item");
            for (int i = 0; i < Math.min(items.size(), 20); i++) {
                Element item = items.get(i);
                String href = item.select("a").attr("href");
                String name = item.select(".module-item-pic img").attr("alt");
                String pic = item.select(".module-item-pic img").attr("data-src");
                if (TextUtils.isEmpty(pic)) pic = item.select(".module-item-pic img").attr("src");
                if (!pic.startsWith("http")) pic = siteUrl + pic;
                String remark = item.select(".module-item-text").text();
                if (!TextUtils.isEmpty(href) && !TextUtils.isEmpty(name)) {
                    list.add(new Vod(href, name, pic, remark));
                }
            }
        } catch (Exception ignored) {
        }
        return Result.string(classes, list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String cateUrl = siteUrl + "/vodshow/" + tid + "--------" + pg + "---.html";
        List<Vod> list = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(OkHttp.string(cateUrl, getHeader()));
            for (Element item : doc.select(".module-item")) {
                String href = item.select("a").attr("href");
                String name = item.select(".module-item-pic img").attr("alt");
                String pic = item.select(".module-item-pic img").attr("data-src");
                if (TextUtils.isEmpty(pic)) pic = item.select(".module-item-pic img").attr("src");
                if (!pic.startsWith("http")) pic = siteUrl + pic;
                String remark = item.select(".module-item-text").text();
                if (!TextUtils.isEmpty(href) && !TextUtils.isEmpty(name)) {
                    list.add(new Vod(href, name, pic, remark));
                }
            }
        } catch (Exception ignored) {
        }
        return Result.string(list);
    }

    @Override
    public String detailContent(List<String> ids) {
        String detailUrl = ids.get(0);
        if (!detailUrl.startsWith("http")) detailUrl = siteUrl + detailUrl;
        try {
            Document doc = Jsoup.parse(OkHttp.string(detailUrl, getHeader()));
            String vodName = doc.select(".page-title").text();
            String vodPic = doc.select(".mobile-play .lazyload").attr("data-src");
            if (TextUtils.isEmpty(vodPic)) vodPic = doc.select(".video-cover img").attr("src");
            if (!TextUtils.isEmpty(vodPic) && !vodPic.startsWith("http")) vodPic = siteUrl + vodPic;

            Elements panLinks = doc.select(".module-row-info p");
            List<String> baiduUrls = new ArrayList<>();
            List<String> quarkUrls = new ArrayList<>();
            List<String> pan115Urls = new ArrayList<>();
            List<String> aliUrls = new ArrayList<>();
            List<String> xunleiUrls = new ArrayList<>();
            List<String> pan123Urls = new ArrayList<>();
            List<String> otherUrls = new ArrayList<>();

            for (Element p : panLinks) {
                String text = p.text().trim();
                if (!text.startsWith("http")) continue;
                if (text.contains("pan.baidu.com")) baiduUrls.add(text);
                else if (text.contains("quark.cn")) quarkUrls.add(text);
                else if (text.contains("115.com") || text.contains("115cdn.com")) pan115Urls.add(text);
                else if (text.contains("aliyundrive.com")) aliUrls.add(text);
                else if (text.contains("xunlei.com")) xunleiUrls.add(text);
                else if (text.contains("123pan.com") || text.contains("123684.com")) pan123Urls.add(text);
                else otherUrls.add(text);
            }

            List<String> playFrom = new ArrayList<>();
            List<String> playUrl = new ArrayList<>();

            addDriveLine(playFrom, playUrl, "\u767e\u5ea6\u7f51\u76d8", baiduUrls);
            addDriveLine(playFrom, playUrl, "\u5938\u514b\u7f51\u76d8", quarkUrls);
            addDriveLine(playFrom, playUrl, "115\u7f51\u76d8", pan115Urls);
            addDriveLine(playFrom, playUrl, "\u963f\u91cc\u4e91\u76d8", aliUrls);
            addDriveLine(playFrom, playUrl, "\u8fc5\u96f7\u4e91\u76d8", xunleiUrls);
            addDriveLine(playFrom, playUrl, "123\u4e91\u76d8", pan123Urls);
            addDriveLine(playFrom, playUrl, "\u5176\u4ed6\u7f51\u76d8", otherUrls);

            if (playFrom.isEmpty()) {
                playFrom.add("\u6682\u65e0\u8d44\u6e90");
                playUrl.add("\u64ad\u653e$https://www.douban.com");
            }

            Vod vod = new Vod();
            vod.setVodId(ids.get(0));
            vod.setVodName(vodName);
            vod.setVodPic(vodPic);
            vod.setVodPlayFrom(TextUtils.join("$$$", playFrom));
            vod.setVodPlayUrl(TextUtils.join("$$$", playUrl));

            for (Element dd : doc.select(".video-info-itemtitle")) {
                String key = dd.text();
                Element next = dd.nextElementSibling();
                if (next == null) continue;
                String val = next.text();
                if (key.contains("\u5bfc\u6f14")) vod.setVodDirector(val);
                else if (key.contains("\u4e3b\u6f14")) vod.setVodActor(val);
                else if (key.contains("\u5267\u60c5")) vod.setVodContent(val);
            }
            return Result.string(vod);
        } catch (Exception e) {
            return "";
        }
    }

    private void addDriveLine(List<String> playFrom, List<String> playUrl, String name, List<String> urls) {
        if (urls.isEmpty()) return;
        playFrom.add(name);
        List<String> episodes = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            episodes.add("\u94fe\u63a5" + (i + 1) + "$" + urls.get(i));
        }
        playUrl.add(TextUtils.join("#", episodes));
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        String searchUrl = siteUrl + "/vodsearch/-------------.html?wd=" + URLEncoder.encode(key) + "&page=" + pg;
        List<Vod> list = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(OkHttp.string(searchUrl, getHeader()));
            for (Element item : doc.select(".module-search-item")) {
                String href = item.select("a").attr("href");
                String name = item.select(".module-item-pic img").attr("alt");
                String pic = item.select(".module-item-pic img").attr("data-src");
                if (TextUtils.isEmpty(pic)) pic = item.select(".module-item-pic img").attr("src");
                if (!pic.startsWith("http")) pic = siteUrl + pic;
                String remark = item.select(".module-item-text").text();
                if (!TextUtils.isEmpty(href) && !TextUtils.isEmpty(name)) {
                    list.add(new Vod(href, name, pic, remark));
                }
            }
        } catch (Exception ignored) {
        }
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return Result.get().url(id).string();
    }
}
