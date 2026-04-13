package com.github.catvod.spider.protect;

import android.content.Context;

import com.github.catvod.spider.Leodanmu;

import java.util.HashMap;
import java.util.List;

/**
 * V1 最小壳骨架：先把真实实现入口抽出来，当前仍回退到外层稳定实现。
 * 后续逐步把核心逻辑迁入 payload-only，再收紧 fallback。
 */
public class RealLeodanmu implements PayloadBridge {

    @Override
    public void init(Context context, String extend) throws Exception {
        Leodanmu.initShellFallback(context, extend);
    }

    @Override
    public String homeContent(boolean filter) {
        return Leodanmu.homeContentShellFallback(filter);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return Leodanmu.categoryContentShellFallback(tid, pg, filter, extend);
    }

    @Override
    public String detailContent(List<String> ids) {
        return Leodanmu.detailContentShellFallback(ids);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return Leodanmu.playerContentShellFallback(flag, id, vipFlags);
    }

    @Override
    public String liveContent(String url) throws Exception {
        return Leodanmu.liveContentShellFallback(url);
    }
}
