package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public class Utils {

    private static WeakReference<Activity> cachedActivity;

    public static Activity getTopActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<Object, Object> activities;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                activities = (HashMap<Object, Object>) activitiesField.get(activityThread);
            } else {
                activities = (android.util.ArrayMap<Object, Object>) activitiesField.get(activityThread);
            }
            for (Object activityRecord : activities.values()) {
                Class<?> activityRecordClass = activityRecord.getClass();
                java.lang.reflect.Field pausedField = activityRecordClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                if (!pausedField.getBoolean(activityRecord)) {
                    java.lang.reflect.Field activityField = activityRecordClass.getDeclaredField("activity");
                    activityField.setAccessible(true);
                    Activity activity = (Activity) activityField.get(activityRecord);
                    if (activity != null) {
                        cachedActivity = new WeakReference<>(activity);
                        return activity;
                    }
                }
            }
        } catch (Exception e) {
            Leodanmu.log("获取TopActivity失败: " + e.getMessage());
        }

        // 如果反射获取失败，尝试从缓存返回
        if (cachedActivity != null) {
            Activity activity = cachedActivity.get();
            if (activity != null && !activity.isFinishing()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
                    return null;
                }
                return activity;
            }
        }
        return null;
    }

    public static void safeShowToast(final Context context, final String message) {
        if (context instanceof Activity) {
            safeShowToast2((Activity) context, message);
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    public static void safeShowToast2(Activity activity, String message) {
        if (activity != null && !activity.isFinishing()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (activity.isDestroyed()) return;
            }
            safeRunOnUiThread(activity, new Runnable() {
                @Override
                public void run() {
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    public static void safeRunOnUiThread(Activity activity, Runnable runnable) {
        if (activity != null && !activity.isFinishing()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (activity.isDestroyed()) return;
            }
            activity.runOnUiThread(runnable);
        }
    }

    // ========== 新增：获取代理端口（动态） ==========
    public static int getPort() {
        int port = 9978;
        try {
            Class<?> clz = Class.forName("com.github.catvod.Proxy");
            port = (int) clz.getMethod("getPort").invoke(null);
        } catch (Exception e) {
            Leodanmu.log("❌ 获取代理端口异常: " + e.getMessage());
        }
        return port;
    }
}