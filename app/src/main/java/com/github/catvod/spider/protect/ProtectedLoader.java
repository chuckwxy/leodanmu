package com.github.catvod.spider.protect;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.spider.Leodanmu;
import com.github.catvod.spider.Utils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.json.JSONArray;

import dalvik.system.DexClassLoader;

/**
 * V1 最小壳骨架：
 * 1) 先让 payload.bin 真正进入产物链；
 * 2) 运行时能提取/解码/尝试装载；
 * 3) 装载失败时安全回退到外层稳定实现。
 */
public final class ProtectedLoader {

    private static final Object LOCK = new Object();
    private static volatile PayloadBridge bridge;
    private static volatile boolean loadAttempted = false;
    private static volatile String lastLoadStatus = "idle";

    private static final String PAYLOAD_INDEX_ASSET_PATH = "payload/index.json";
    private static final String PAYLOAD_DEX_NAME = "payload.dex";
    private static final String PAYLOAD_META_NAME = "payload.meta.json";
    private static final String REAL_IMPL_CLASS = "com.github.catvod.spider.protect.impl.PayloadEntry";

    private ProtectedLoader() {
    }

    public static PayloadBridge getBridge(Context context) {
        if (bridge != null) return bridge;
        synchronized (LOCK) {
            if (bridge != null) return bridge;
            if (!loadAttempted) {
                loadAttempted = true;
                bridge = tryLoadPayloadBridge(context);
                if (bridge == null) {
                    lastLoadStatus = "fallback";
                    bridge = new RealLeodanmu();
                    Leodanmu.log("[shell] payload 主入口未启用，已回退兜底实现");
                }
            }
            return bridge;
        }
    }

    public static String getLastLoadStatus() {
        return lastLoadStatus;
    }

    public static String getPayloadAssetPath() {
        return PAYLOAD_INDEX_ASSET_PATH;
    }

    private static PayloadBridge tryLoadPayloadBridge(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : Utils.getAppContext();
        if (appContext == null) {
            lastLoadStatus = "no-context";
            Leodanmu.log("[shell] 无法加载 payload：context 为空");
            return null;
        }

        try {
            byte[] raw = readPayloadBundle(appContext);
            if (raw == null || raw.length == 0) {
                lastLoadStatus = "payload-missing";
                Leodanmu.log("[shell] segmented payload 不存在，保持 fallback");
                return null;
            }

            byte[] decoded = decodePayload(raw);
            File shellDir = new File(appContext.getCacheDir(), "leo_shell");
            if (!shellDir.exists()) shellDir.mkdirs();
            File dexFile = new File(shellDir, PAYLOAD_DEX_NAME);
            try (FileOutputStream fos = new FileOutputStream(dexFile, false)) {
                fos.write(decoded);
            }

            JSONObject meta = buildMeta(raw, decoded, dexFile);
            File metaFile = new File(shellDir, PAYLOAD_META_NAME);
            try (FileOutputStream fos = new FileOutputStream(metaFile, false)) {
                fos.write(meta.toString(2).getBytes(StandardCharsets.UTF_8));
            }

            DexClassLoader classLoader = new DexClassLoader(
                    dexFile.getAbsolutePath(),
                    shellDir.getAbsolutePath(),
                    null,
                    ProtectedLoader.class.getClassLoader()
            );

            Class<?> implClass = classLoader.loadClass(REAL_IMPL_CLASS);
            Object instance = implClass.newInstance();
            if (instance instanceof PayloadBridge) {
                lastLoadStatus = "payload-loaded";
                Leodanmu.log("[shell] payload 已加载: " + REAL_IMPL_CLASS);
                return (PayloadBridge) instance;
            }

            lastLoadStatus = "payload-not-bridge";
            Leodanmu.log("[shell] payload 实现未实现 PayloadBridge，保持 fallback");
            return null;
        } catch (Throwable e) {
            lastLoadStatus = "payload-error:" + e.getClass().getSimpleName();
            Leodanmu.log("[shell] payload 加载失败，保持 fallback: " + e.getMessage());
            return null;
        }
    }

    private static byte[] readAsset(Context context, String path) {
        try (InputStream is = context.getAssets().open(path)) {
            byte[] buffer = new byte[8192];
            int len;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                return baos.toByteArray();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readPayloadBundle(Context context) throws Exception {
        byte[] indexRaw = readAsset(context, PAYLOAD_INDEX_ASSET_PATH);
        if (indexRaw == null || indexRaw.length == 0) return null;
        JSONObject index = new JSONObject(new String(indexRaw, StandardCharsets.UTF_8));
        JSONArray parts = index.optJSONArray("parts");
        if (parts == null || parts.length() == 0) return null;
        try (ByteArrayOutputStream merged = new ByteArrayOutputStream()) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                String name = part.optString("name", "");
                if (TextUtils.isEmpty(name)) continue;
                byte[] chunk = readAsset(context, "payload/" + name);
                if (chunk == null || chunk.length == 0) {
                    throw new IllegalStateException("payload part missing: " + name);
                }
                merged.write(chunk);
            }
            return merged.toByteArray();
        }
    }

    private static byte[] decodePayload(byte[] raw) {
        byte[] key = buildKey();
        byte[] out = Arrays.copyOf(raw, raw.length);
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (out[i] ^ key[i % key.length]);
        }
        return out;
    }

    private static byte[] buildKey() {
        String p1 = "Leo";
        String p2 = "Shell";
        String p3 = "V1";
        return (p1 + ":" + p2 + ":" + p3).getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject buildMeta(byte[] raw, byte[] decoded, File dexFile) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("asset", PAYLOAD_INDEX_ASSET_PATH);
        meta.put("rawLength", raw == null ? 0 : raw.length);
        meta.put("decodedLength", decoded == null ? 0 : decoded.length);
        meta.put("dexPath", dexFile == null ? "" : dexFile.getAbsolutePath());
        meta.put("status", lastLoadStatus);
        meta.put("impl", REAL_IMPL_CLASS);
        return meta;
    }
}
