package com.github.catvod.spider;

/** 零依赖的静态事件总线，替代 EventBus 用于远程输入直发直收 */
public class RemoteInputBus {

    private static java.util.function.Consumer<String> searchListener;
    private static java.util.function.BiConsumer<String, String> configListener;

    public static void onSearchInput(java.util.function.Consumer<String> listener) {
        searchListener = listener;
    }

    public static void removeSearchInput() {
        searchListener = null;
    }

    public static void postSearch(String keyword) {
        if (searchListener != null) {
            searchListener.accept(keyword);
        }
    }

    public static void onConfigInput(java.util.function.BiConsumer<String, String> listener) {
        configListener = listener;
    }

    public static void removeConfigInput() {
        configListener = null;
    }

    public static void postConfig(String field, String value) {
        if (configListener != null) {
            configListener.accept(field, value);
        }
    }
}
