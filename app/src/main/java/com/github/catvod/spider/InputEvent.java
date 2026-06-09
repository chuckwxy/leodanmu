package com.github.catvod.spider;

public class InputEvent {
    public static class Remote {
        public final String keyword;
        public Remote(String keyword) { this.keyword = keyword; }
    }

    public static class Config {
        public final String field;
        public final String value;
        public Config(String field, String value) { this.field = field; this.value = value; }
    }
}
