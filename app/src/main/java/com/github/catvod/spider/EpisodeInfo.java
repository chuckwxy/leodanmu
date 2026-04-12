package com.github.catvod.spider;

import java.util.List;
import java.util.ArrayList;

public class EpisodeInfo {
    private String episodeNum;
    private String episodeName;          // 保留原字段，用于兼容
    private String episodeYear;
    private String episodeSeasonNum;
    private String seriesName;
    private String fileName;
    private String episodeUrl;
    private List<String> episodeNames;   // 新增：多候选名称列表
    private String searchKeyword;
    private String specialTag;
    private String specialType;
    private String specialSuffix;
    private String episodeDateCode;

    public EpisodeInfo() {
        episodeNames = new ArrayList<>(); // 初始化
    }

    public String getEpisodeNum() {
        return episodeNum;
    }

    public void setEpisodeNum(String episodeNum) {
        this.episodeNum = episodeNum;
    }

    public String getEpisodeName() {
        return episodeName;
    }

    public void setEpisodeName(String episodeName) {
        this.episodeName = episodeName;
    }

    public String getEpisodeYear() {
        return episodeYear;
    }

    public void setEpisodeYear(String episodeYear) {
        this.episodeYear = episodeYear;
    }

    public String getEpisodeSeasonNum() {
        return episodeSeasonNum;
    }

    public void setEpisodeSeasonNum(String episodeSeasonNum) {
        this.episodeSeasonNum = episodeSeasonNum;
    }

    public String getSeriesName() {
        return seriesName;
    }

    public void setSeriesName(String seriesName) {
        this.seriesName = seriesName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getEpisodeUrl() {
        return episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    // ========== 新增 ==========
    public List<String> getEpisodeNames() {
        return episodeNames;
    }

    public void setEpisodeNames(List<String> episodeNames) {
        this.episodeNames = episodeNames;
    }

    public String getSearchKeyword() {
        return searchKeyword;
    }

    public void setSearchKeyword(String searchKeyword) {
        this.searchKeyword = searchKeyword;
    }

    public String getSpecialTag() {
        return specialTag;
    }

    public void setSpecialTag(String specialTag) {
        this.specialTag = specialTag;
    }

    public String getSpecialType() {
        return specialType;
    }

    public void setSpecialType(String specialType) {
        this.specialType = specialType;
    }

    public String getSpecialSuffix() {
        return specialSuffix;
    }

    public void setSpecialSuffix(String specialSuffix) {
        this.specialSuffix = specialSuffix;
    }

    public String getEpisodeDateCode() {
        return episodeDateCode;
    }

    public void setEpisodeDateCode(String episodeDateCode) {
        this.episodeDateCode = episodeDateCode;
    }
    // =========================

    @Override
    public String toString() {
        return "EpisodeInfo{" +
                "episodeNum='" + episodeNum + '\'' +
                ", episodeName='" + episodeName + '\'' +
                ", episodeYear='" + episodeYear + '\'' +
                ", episodeSeasonNum='" + episodeSeasonNum + '\'' +
                ", seriesName='" + seriesName + '\'' +
                ", fileName='" + fileName + '\'' +
                ", episodeUrl='" + episodeUrl + '\'' +
                ", episodeNames=" + episodeNames + '\'' +
                ", searchKeyword='" + searchKeyword + '\'' +
                ", specialTag='" + specialTag + '\'' +
                ", specialType='" + specialType + '\'' +
                ", specialSuffix='" + specialSuffix + '\'' +
                ", episodeDateCode='" + episodeDateCode + '\'' +
                '}';
    }
}