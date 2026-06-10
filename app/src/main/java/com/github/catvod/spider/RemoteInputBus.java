package com.github.catvod.spider;

/** 零依赖的静态事件总线，替代 EventBus 用于远程输入直发直收 */
public class RemoteInputBus {

    public interface SearchCallback {
        void onKeyword(String keyword);
    }

    public interface ConfigCallback {
        void onValue(String field, String value);
    }

    private static SearchCallback searchListener;
    private static ConfigCallback configListener;

    public static void onSearchInput(SearchCallback listener) {
        searchListener = listener;
    }

    public static void removeSearchInput() {
        searchListener = null;
    }

    public static void postSearch(String keyword) {
        if (searchListener != null) {
            searchListener.onKeyword(keyword);
        }
    }

    public static void onConfigInput(ConfigCallback listener) {
        configListener = listener;
    }

    public static void removeConfigInput() {
        configListener = null;
    }

    public static void postConfig(String field, String value) {
        if (configListener != null) {
            configListener.onValue(field, value);
        }
    }
}
