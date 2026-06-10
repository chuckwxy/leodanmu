package com.github.catvod.spider;

import org.json.JSONObject;

public interface CloudDrive {
    String getKey();
    boolean matchShare(String url);
    JSONObject getVod(String url) throws Exception;
    JSONObject play(String input, String flag) throws Exception;
}
