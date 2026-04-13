package com.github.catvod.spider.protect;

import android.content.Context;

import java.util.HashMap;
import java.util.List;

public interface PayloadBridge {
    void init(Context context, String extend) throws Exception;

    String homeContent(boolean filter);

    String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend);

    String detailContent(List<String> ids);

    String playerContent(String flag, String id, List<String> vipFlags);

    String liveContent(String url) throws Exception;
}
