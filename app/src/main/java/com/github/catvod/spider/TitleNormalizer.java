package com.github.catvod.spider;

import android.text.TextUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TitleNormalizer {

    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[-/.]?(\\d{2})[-/.]?(\\d{2})");
    private static final Pattern SEASON_PATTERN = Pattern.compile("(?:第\\s*([一二三四五六七八九十两0-9]+)\\s*季|season\\s*([0-9]{1,2})|s([0-9]{1,2})(?:e[0-9]{1,3})?|part\\s*([0-9]{1,2})|第\\s*([一二三四五六七八九十两0-9]+)\\s*部)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_PATTERN = Pattern.compile("(?:第\\s*([一二三四五六七八九十两0-9]+)\\s*[集话]|(?:^|\\D)e[p]?\\s*([0-9]{1,3})(?:\\D|$)|s[0-9]{1,2}e([0-9]{1,3}))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD_PATTERN = Pattern.compile("(?:第\\s*([一二三四五六七八九十两0-9]+)\\s*期|([0-9]{8})\\s*期)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPECIAL_PATTERN = Pattern.compile("(纯享|加更|花絮|彩蛋|预告|特别篇|先导片|番外|SP|OVA|OAD)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_PATTERN = Pattern.compile("(?i)(from\\s+[a-z0-9_-]+|4k|hdr|杜比|蓝光|超清|国语|中字|双语)");
    private static final Pattern TAIL_SEASON_PATTERN = Pattern.compile("^(.*?)(?:\\s+)?([0-9]{1,2})$");

    public static TitleMatchInfo parse(String raw) {
        TitleMatchInfo info = new TitleMatchInfo();
        info.raw = raw == null ? "" : raw;
        String text = normalize(raw);
        info.normalized = text;

        Matcher special = SPECIAL_PATTERN.matcher(text);
        if (special.find()) {
            info.isSpecial = true;
            info.specialTag = special.group(1).toLowerCase(Locale.ROOT);
        }

        Matcher date = DATE_PATTERN.matcher(text);
        if (date.find()) {
            info.dateCode = date.group(1) + date.group(2) + date.group(3);
            info.isVariety = true;
        }

        Matcher season = SEASON_PATTERN.matcher(text);
        if (season.find()) {
            info.seasonNum = normalizeNumber(firstNonEmpty(season.group(1), season.group(2), season.group(3), season.group(4), season.group(5)));
        }

        Matcher period = PERIOD_PATTERN.matcher(text);
        if (period.find()) {
            info.periodNum = normalizeNumber(firstNonEmpty(period.group(1), period.group(2)));
            info.isVariety = true;
        }

        Matcher episode = EPISODE_PATTERN.matcher(text);
        if (episode.find()) {
            String ep = normalizeNumber(firstNonEmpty(episode.group(1), episode.group(2), episode.group(3)));
            if (TextUtils.isEmpty(info.periodNum)) {
                info.episodeNum = ep;
            }
        }

        String core = text;
        core = SPECIAL_PATTERN.matcher(core).replaceAll(" ");
        core = DATE_PATTERN.matcher(core).replaceAll(" ");
        core = SEASON_PATTERN.matcher(core).replaceAll(" ");
        core = PERIOD_PATTERN.matcher(core).replaceAll(" ");
        core = EPISODE_PATTERN.matcher(core).replaceAll(" ");
        core = NOISE_PATTERN.matcher(core).replaceAll(" ");
        core = core.replaceAll("[\\[【(（][^\\]】)）]{0,40}[\\]】)）]", " ");
        core = core.replaceAll("\\s+", " ").trim();

        if (TextUtils.isEmpty(info.seasonNum) && TextUtils.isEmpty(info.periodNum) && !TextUtils.isEmpty(core)) {
            Matcher tailSeason = TAIL_SEASON_PATTERN.matcher(core);
            if (tailSeason.matches()) {
                String prefix = tailSeason.group(1) == null ? "" : tailSeason.group(1).trim();
                String tail = normalizeNumber(tailSeason.group(2));
                if (!TextUtils.isEmpty(prefix)
                        && prefix.length() >= 2
                        && !prefix.matches(".*第$")
                        && !prefix.matches(".*[0-9]$")
                        && !TextUtils.equals(tail, "0")) {
                    info.seasonNum = tail;
                    core = prefix;
                }
            }
        }

        info.coreTitle = core;
        return info;
    }

    public static int score(TitleMatchInfo target, TitleMatchInfo candidate) {
        int score = 0;
        if (!TextUtils.isEmpty(target.coreTitle) && !TextUtils.isEmpty(candidate.coreTitle)) {
            if (target.coreTitle.equals(candidate.coreTitle)) score += 60;
            else if (target.coreTitle.contains(candidate.coreTitle) || candidate.coreTitle.contains(target.coreTitle)) score += 35;
        }
        score += compareField(target.seasonNum, candidate.seasonNum, 20, -20);
        score += compareField(target.episodeNum, candidate.episodeNum, 30, -30);
        score += compareField(target.periodNum, candidate.periodNum, 30, -30);
        score += compareField(target.dateCode, candidate.dateCode, 35, -25);

        if (target.isSpecial && candidate.isSpecial) {
            if (TextUtils.equals(target.specialTag, candidate.specialTag)) score += 10;
        } else if (target.isSpecial != candidate.isSpecial) {
            score -= 20;
        }
        return score;
    }

    private static int compareField(String a, String b, int same, int diff) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return 0;
        return TextUtils.equals(a, b) ? same : diff;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.replace('（', '(').replace('）', ')')
                .replace('【', '[').replace('】', ']')
                .replace('：', ':');
        text = NOISE_PATTERN.matcher(text).replaceAll(" ");
        text = text.toLowerCase(Locale.ROOT);
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    private static String normalizeNumber(String s) {
        if (TextUtils.isEmpty(s)) return "";
        s = s.trim();
        if (s.matches("[一二三四五六七八九十两]+")) {
            s = String.valueOf(parseChineseNumber(s));
        }
        while (s.startsWith("0") && s.length() > 1) s = s.substring(1);
        return s;
    }

    private static int parseChineseNumber(String text) {
        if (TextUtils.isEmpty(text)) return 0;
        if ("十".equals(text)) return 10;
        if (text.contains("十")) {
            String[] parts = text.split("十", -1);
            int tens = TextUtils.isEmpty(parts[0]) ? 1 : chineseDigit(parts[0]);
            int ones = parts.length > 1 && !TextUtils.isEmpty(parts[1]) ? chineseDigit(parts[1]) : 0;
            return tens * 10 + ones;
        }
        return chineseDigit(text);
    }

    private static int chineseDigit(String text) {
        if (TextUtils.isEmpty(text)) return 0;
        switch (text) {
            case "一": return 1;
            case "二":
            case "两": return 2;
            case "三": return 3;
            case "四": return 4;
            case "五": return 5;
            case "六": return 6;
            case "七": return 7;
            case "八": return 8;
            case "九": return 9;
            default:
                try { return Integer.parseInt(text); } catch (Exception e) { return 0; }
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return "";
    }
}
