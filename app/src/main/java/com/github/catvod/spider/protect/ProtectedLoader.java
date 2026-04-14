package com.github.catvod.spider.protect;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.spider.Leodanmu;
import com.github.catvod.spider.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import dalvik.system.DexClassLoader;

public final class ProtectedLoader {

    private static final Object LOCK = new Object();
    private static volatile PayloadBridge bridge;
    private static volatile boolean loadAttempted = false;
    private static volatile String lastLoadStatus = "idle";
    private static volatile boolean nativeReady = false;

    private static final String BUNDLE_INDEX_ASSET_PATH = "x/c.bin";
    private static final String BUNDLE_DEX_NAME = "ivx.dex";
    private static final String BUNDLE_META_NAME = "ivx.json";
    private static final String NATIVE_LIB_NAME = "shieldx";
    private static final String REAL_IMPL_CLASS = "com.github.catvod.spider.protect.impl.PayloadEntry";

    static {
        try {
            System.loadLibrary(NATIVE_LIB_NAME);
            nativeReady = true;
        } catch (Throwable e) {
            nativeReady = false;
        }
    }

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
                    Leodanmu.log("[shell] inner entry unavailable, fallback enabled");
                }
            }
            return bridge;
        }
    }

    public static String getLastLoadStatus() {
        return lastLoadStatus;
    }

    public static String getPayloadAssetPath() {
        return BUNDLE_INDEX_ASSET_PATH;
    }

    private static PayloadBridge tryLoadPayloadBridge(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : Utils.getAppContext();
        if (appContext == null) {
            lastLoadStatus = "no-context";
            Leodanmu.log("[shell] unable to load inner bundle: no context");
            return null;
        }
        if (!nativeReady) {
            lastLoadStatus = "native-unavailable";
            Leodanmu.log("[shell] native bridge unavailable, fallback enabled");
            return null;
        }

        try {
            JSONObject index = readBundleIndex(appContext);
            if (index == null) {
                lastLoadStatus = "index-missing";
                Leodanmu.log("[shell] inner index missing, fallback enabled");
                return null;
            }

            byte[][] parts = readBundleParts(appContext, index);
            if (parts == null || parts.length == 0) {
                lastLoadStatus = "bundle-missing";
                Leodanmu.log("[shell] inner bundle missing, fallback enabled");
                return null;
            }

            JSONObject seed = index.optJSONObject("k");
            String stage = index.optString("s", "v3-native-full");
            String gitCommit = index.optString("g", "unknown");
            String payloadRawSha256 = seed == null ? "" : seed.optString("r", "");

            int env = nativeCheckEnv();
            if (env != 0) {
                lastLoadStatus = "env-blocked:" + env;
                Leodanmu.log("[shell] native env blocked, fallback enabled: " + env);
                return null;
            }

            byte[] decoded = nativeDecodeBundle(
                    stage.getBytes(StandardCharsets.UTF_8),
                    gitCommit.getBytes(StandardCharsets.UTF_8),
                    payloadRawSha256.getBytes(StandardCharsets.UTF_8),
                    parts
            );
            if (decoded == null || decoded.length == 0) {
                lastLoadStatus = "decode-empty";
                Leodanmu.log("[shell] native decode returned empty, fallback enabled");
                return null;
            }

            File shellDir = new File(appContext.getCacheDir(), "leo_shell");
            if (!shellDir.exists()) shellDir.mkdirs();
            File dexFile = new File(shellDir, BUNDLE_DEX_NAME);
            try (FileOutputStream fos = new FileOutputStream(dexFile, false)) {
                fos.write(decoded);
            }

            JSONObject meta = buildMeta(index, decoded, dexFile);
            File metaFile = new File(shellDir, BUNDLE_META_NAME);
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
                lastLoadStatus = "inner-loaded";
                Leodanmu.log("[shell] inner entry loaded: " + REAL_IMPL_CLASS);
                return (PayloadBridge) instance;
            }

            lastLoadStatus = "inner-not-bridge";
            Leodanmu.log("[shell] inner impl invalid, fallback enabled");
            return null;
        } catch (Throwable e) {
            lastLoadStatus = "inner-error:" + e.getClass().getSimpleName();
            Leodanmu.log("[shell] inner load failed, fallback enabled: " + e.getMessage());
            return null;
        }
    }

    private static JSONObject readBundleIndex(Context context) throws Exception {
        byte[] indexRaw = readAsset(context, BUNDLE_INDEX_ASSET_PATH);
        if (indexRaw == null || indexRaw.length == 0) return null;
        return new JSONObject(new String(indexRaw, StandardCharsets.UTF_8));
    }

    private static byte[][] readBundleParts(Context context, JSONObject index) throws Exception {
        JSONArray parts = index.optJSONArray("p");
        if (parts == null || parts.length() == 0) return null;
        byte[][] out = new byte[parts.length()][];
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) return null;
            String name = part.optString("n", "");
            if (TextUtils.isEmpty(name)) return null;
            byte[] chunk = readAsset(context, "x/" + name);
            if (chunk == null || chunk.length == 0) {
                throw new IllegalStateException("bundle chunk missing: " + name);
            }
            out[i] = chunk;
        }
        return out;
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

    private static JSONObject buildMeta(JSONObject index, byte[] decoded, File dexFile) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("asset", BUNDLE_INDEX_ASSET_PATH);
        meta.put("decodedLength", decoded == null ? 0 : decoded.length);
        meta.put("dexPath", dexFile == null ? "" : dexFile.getAbsolutePath());
        meta.put("status", lastLoadStatus);
        meta.put("impl", REAL_IMPL_CLASS);
        meta.put("stage", index == null ? "v3-native-full" : index.optString("s", "v3-native-full"));
        return meta;
    }

    private static native byte[] nativeDecodeBundle(byte[] stage, byte[] gitCommit, byte[] payloadRawSha256, byte[][] parts);

    private static native int nativeCheckEnv();
}
