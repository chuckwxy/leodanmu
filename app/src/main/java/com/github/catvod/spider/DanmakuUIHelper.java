package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.github.catvod.spider.entity.DanmakuItem;
import com.github.catvod.spider.danmu.SharedPreferencesService;
import com.github.catvod.net.OkHttp;


import java.io.InputStream;
import java.net.URLEncoder;
import java.util.*;

import okhttp3.Response;

public class DanmakuUIHelper {

    // ========== 深色主题颜色（保留，作为默认值） ==========
    private static final int DARK_BG_PRIMARY = 0x1A000000;
    private static final int DARK_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int DARK_TEXT_SECONDARY = 0xFFCCCCCC;
    private static final int DARK_TEXT_TERTIARY = 0xFF999999;
    private static final int DARK_FOCUS_BORDER = 0xFFFFFFFF;

    // ========== 主题颜色定义 ==========
    private static class ThemeColors {
        int bgPrimary;      // 主背景色
        int bgSecondary;    // 次级背景（输入框等）
        int textPrimary;    // 主要文字
        int textSecondary;  // 次要文字
        int textTertiary;   // 提示文字
        int focusBorder;    // 焦点边框
        int inputBg;        // 输入框背景
        int divider;        // 分割线

        ThemeColors(int bgPrimary, int bgSecondary, int textPrimary, int textSecondary,
                    int textTertiary, int focusBorder, int inputBg, int divider) {
            this.bgPrimary = bgPrimary;
            this.bgSecondary = bgSecondary;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textTertiary = textTertiary;
            this.focusBorder = focusBorder;
            this.inputBg = inputBg;
            this.divider = divider;
        }
    }

    // 深色主题（默认）
    private static final ThemeColors DARK_THEME = new ThemeColors(
            0xFF1E1E1E, // bgPrimary
            0xFF2D2D2D, // bgSecondary
            0xFFFFFFFF, // textPrimary
            0xFFCCCCCC, // textSecondary
            0xFF999999, // textTertiary
            0xFFFFFFFF, // focusBorder
            0xFF2D2D2D, // inputBg
            0xFF444444  // divider
    );

    // 浅色主题（iOS风格）
    private static final ThemeColors LIGHT_THEME = new ThemeColors(
            0xFFFFFFFF, // bgPrimary
            0xFFF5F5F5, // bgSecondary
            0xFF000000, // textPrimary
            0xFF666666, // textSecondary
            0xFF999999, // textTertiary
            0xFF007AFF, // focusBorder (iOS蓝)
            0xFFF0F0F0, // inputBg
            0xFFE0E0E0  // divider
    );

    // 获取当前主题颜色
    private static ThemeColors getThemeColors(Activity activity) {
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
        return config.getTheme() == 1 ? LIGHT_THEME : DARK_THEME;
    }

    private static boolean isReversed;
    private static List<DanmakuItem> currentItems = new ArrayList<>();



    private static final Map<Activity, Boolean> activeActivities = new WeakHashMap<>();

    // ========== 辅助方法：设置毛玻璃背景 ==========
    private static void setBlurBackground(View view, Activity activity, ThemeColors colors) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dpToPx(activity, 8));
        if (colors == LIGHT_THEME) {
            // 浅色主题：纯白背景（不透明），无模糊
            drawable.setColor(0xFFFFFFFF);
            view.setBackground(drawable);
        } else {
            // 深色主题：半透明黑背景（原有效果）
            drawable.setColor(0x80000000); // 50% 黑
            view.setBackground(drawable);
        }
    }

    // ========== 辅助方法：设置主题按钮选中样式 ==========
    private static void setButtonSelected(Button button, boolean selected, ThemeColors colors, Activity activity) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dpToPx(activity, 6));
        if (selected) {
            drawable.setColor(LIGHT_THEME.focusBorder);
            button.setTextColor(Color.WHITE);
        } else {
            drawable.setColor(Color.TRANSPARENT);
            button.setTextColor(colors.textPrimary);
        }
        drawable.setStroke(0, Color.TRANSPARENT);
        button.setBackground(drawable);
    }

    private static Button makeProxyIcon(Activity activity, String text, int size, int margin, ThemeColors colors) {
        Button btn = new Button(activity);
        btn.setText(text);
        btn.setTextSize(10);
        btn.setTextColor(colors.textPrimary);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        btn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(margin, dpToPx(activity, 4), margin, dpToPx(activity, 4));
        btn.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(colors.bgSecondary);
        bg.setStroke(dpToPx(activity, 1), colors.divider);
        btn.setBackground(bg);
        btn.setTag(false);
        return btn;
    }

    private static void setProxyIconSelected(Button btn, boolean selected, ThemeColors colors, Activity activity) {
        btn.setTag(selected);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (selected) {
            bg.setColor(LIGHT_THEME.focusBorder);
            btn.setTextColor(Color.WHITE);
        } else {
            bg.setColor(colors.bgSecondary);
            btn.setTextColor(colors.textPrimary);
        }
        bg.setStroke(dpToPx(activity, 1), colors.divider);
        btn.setBackground(bg);
    }

    // ========== 资源清理 ==========
    public static void cleanupAllResources() {
        try {
            Leodanmu.log("🧹 开始清理UI资源...");
            currentItems.clear();
            activeActivities.clear();
            try {
                java.lang.reflect.Method cleanupMethod = WebServer.class.getDeclaredMethod("cleanupResources");
                cleanupMethod.setAccessible(true);
                cleanupMethod.invoke(null);
                Leodanmu.log("🛑 清理：WebServer资源已清理");
            } catch (Exception e) {
            }
            Leodanmu.log("✅ 所有UI资源已清理完成");
        } catch (Exception e) {
            Leodanmu.log("❌ 清理资源时发生异常: " + e.getMessage());
        }
    }

    public static void registerActivity(Activity activity) {
        activeActivities.put(activity, true);
        Leodanmu.log("📱 注册Activity: " + activity.getClass().getSimpleName());
    }

    public static void unregisterActivity(Activity activity) {
        activeActivities.remove(activity);
        Leodanmu.log("📱 注销Activity: " + activity.getClass().getSimpleName());
        if (activeActivities.isEmpty()) {
            cleanupAllResources();
        }
    }

    // ========== 边框按钮核心方法 ==========
    private static Button createBorderButton(Activity activity, String text) {
        ThemeColors colors = getThemeColors(activity);
        return createBorderButton(activity, text, colors);
    }

    private static Button createBorderButton(Activity activity, String text, ThemeColors colors) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(colors.textPrimary);
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setPadding(dpToPx(activity, 6), dpToPx(activity, 4),
                dpToPx(activity, 6), dpToPx(activity, 4));

        updateButtonBorder(button, false, activity, colors);

        button.setOnFocusChangeListener((v, hasFocus) -> {
            updateButtonBorder((Button) v, hasFocus, activity, colors);
            if (hasFocus) {
                v.setScaleX(1.05f);
                v.setScaleY(1.05f);
            } else {
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
            }
        });

        return button;
    }

    // 创建无缩放效果的边框按钮（保留边框高亮，但无缩放动画）
    private static Button createStaticBorderButton(Activity activity, String text) {
        ThemeColors colors = getThemeColors(activity);
        return createStaticBorderButton(activity, text, colors);
    }

    private static Button createStaticBorderButton(Activity activity, String text, ThemeColors colors) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(colors.textPrimary);
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setPadding(dpToPx(activity, 6), dpToPx(activity, 4),
                dpToPx(activity, 6), dpToPx(activity, 4));
        updateButtonBorder(button, false, activity, colors);

        button.setOnFocusChangeListener((v, hasFocus) -> {
            updateButtonBorder((Button) v, hasFocus, activity, colors);
        });

        return button;
    }

    private static void updateButtonBorder(Button button, boolean focused, Activity activity) {
        ThemeColors colors = getThemeColors(activity);
        updateButtonBorder(button, focused, activity, colors);
    }

    private static void updateButtonBorder(Button button, boolean focused, Activity activity, ThemeColors colors) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dpToPx(activity, 6));
        int borderWidth = focused ? dpToPx(activity, 2) : 0;
        int borderColor = focused ? colors.focusBorder : Color.TRANSPARENT;
        drawable.setStroke(borderWidth, borderColor);
        button.setBackground(drawable);
    }

    // 为EditText添加焦点边框
    private static void setupEditTextBorder(EditText editText, Activity activity) {
        ThemeColors colors = getThemeColors(activity);
        setupEditTextBorder(editText, activity, colors);
    }

    private static void setupEditTextBorder(EditText editText, Activity activity, ThemeColors colors) {
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable drawable = new GradientDrawable();
            if (colors == DARK_THEME) {
                drawable.setColor(colors.bgSecondary);
            } else {
                drawable.setColor(colors.inputBg);
            }
            drawable.setCornerRadius(dpToPx(activity, 6));
            int borderWidth = hasFocus ? dpToPx(activity, 2) : 0;
            int borderColor = hasFocus ? colors.focusBorder : Color.TRANSPARENT;
            drawable.setStroke(borderWidth, borderColor);
            editText.setBackground(drawable);
        });
        GradientDrawable initialDrawable = new GradientDrawable();
        if (colors == DARK_THEME) {
            initialDrawable.setColor(colors.bgSecondary);
        } else {
            initialDrawable.setColor(colors.inputBg);
        }
        initialDrawable.setCornerRadius(dpToPx(activity, 6));
        initialDrawable.setStroke(0, Color.TRANSPARENT);
        editText.setBackground(initialDrawable);
    }

    private static void setupTransparentEditTextBorder(EditText editText, Activity activity, ThemeColors colors) {
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(Color.TRANSPARENT);
            drawable.setCornerRadius(dpToPx(activity, 6));
            int borderWidth = hasFocus ? dpToPx(activity, 2) : 0;
            int borderColor = hasFocus ? colors.focusBorder : Color.TRANSPARENT;
            drawable.setStroke(borderWidth, borderColor);
            editText.setBackground(drawable);
        });
        GradientDrawable initialDrawable = new GradientDrawable();
        initialDrawable.setColor(Color.TRANSPARENT);
        initialDrawable.setCornerRadius(dpToPx(activity, 6));
        initialDrawable.setStroke(0, Color.TRANSPARENT);
        editText.setBackground(initialDrawable);
    }

    private static Button createGridResultButton(Activity activity, DanmakuItem item, AlertDialog dialog) {
        ThemeColors colors = getThemeColors(activity);
        Button button = new Button(activity);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);

        String displayText = item.epTitle;
        if (TextUtils.isEmpty(displayText)) displayText = item.title;

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int screenWidthDp = (int) (dm.widthPixels / dm.density);
        int maxLength = 25;
        if (screenWidthDp < 480) maxLength = 30;
        else if (screenWidthDp > 720) maxLength = 20;
        if (displayText.length() > maxLength) displayText = displayText.substring(0, maxLength) + "...";

        button.setText(displayText);
        button.setTextSize(13);
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setSingleLine(false);
        button.setGravity(Gravity.CENTER);
        int padding = dpToPx(activity, 6);
        button.setPadding(padding, padding, padding, padding);
        button.setMinHeight(dpToPx(activity, 48));

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            button.setTooltipText(item.getTitleWithEp());
        }

        button.setTag(item);

        boolean isCurrentDanmaku = matchesCurrentDanmaku(item);
        int currentDanmakuColor = colors == LIGHT_THEME ? 0xFF8A6500 : 0xFFC2A14A;

        // 创建统一的背景 Drawable（包含背景色和圆角，初始无边框）
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dpToPx(activity, 8));
        if (colors == LIGHT_THEME) {
            background.setColor(0xFFFFFFFF); // 纯白背景
            button.setTextColor(isCurrentDanmaku ? currentDanmakuColor : 0xFF000000); // 黑色文字 / 当前推送色
        } else {
            background.setColor(0x80000000); // 半透明黑背景
            button.setTextColor(isCurrentDanmaku ? currentDanmakuColor : colors.textPrimary); // 常规白字 / 当前推送色
        }
        background.setStroke(0, Color.TRANSPARENT); // 初始无边框
        button.setBackground(background);

        // 焦点监听：缩放 + 阴影 + 边框
        button.setOnFocusChangeListener((v, hasFocus) -> {
            // 缩放动画
            v.animate()
                    .scaleX(hasFocus ? 1.05f : 1.0f)
                    .scaleY(hasFocus ? 1.05f : 1.0f)
                    .setDuration(200)
                    .start();
            // 阴影
            if (hasFocus) {
                v.setElevation(dpToPx(activity, 8));
            } else {
                v.setElevation(0);
            }
            button.setTextColor(isCurrentDanmaku
                    ? currentDanmakuColor
                    : (colors == LIGHT_THEME ? 0xFF000000 : colors.textPrimary));
            // 修改边框
            GradientDrawable drawable = (GradientDrawable) v.getBackground();
            if (hasFocus) {
                drawable.setStroke(dpToPx(activity, 2), colors.focusBorder);
            } else {
                drawable.setStroke(0, Color.TRANSPARENT);
            }
            v.setBackground(drawable); // 重新设置以应用边框变化
        });

        button.setOnClickListener(v -> {
            DanmakuItem selected = (DanmakuItem) v.getTag();
            Leodanmu.recordDanmakuUrl(selected, false);
            LeoDanmakuService.pushDanmakuDirect(selected, activity, false);
            dialog.dismiss();
        });

        button.setOnLongClickListener(v -> {
            DanmakuItem it = (DanmakuItem) v.getTag();
            Utils.safeShowToast(activity, it.getTitleWithEp());
            return true;
        });

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int margin = dpToPx(activity, 4);
        params.setMargins(margin, margin, margin, margin);
        button.setLayoutParams(params);

        return button;
    }

    // ========== 合并配置对话框（自动推送左，二维码右，新增主题切换） ==========
    public static void showCombinedConfigDialog(Context ctx) {
        if (!(ctx instanceof Activity)) return;
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) return;

        final DanmakuConfig[] configHolder = new DanmakuConfig[1];
        try {
            DanmakuConfig preloadConfig = DanmakuConfigManager.loadConfig(activity);
            configHolder[0] = preloadConfig == null ? new DanmakuConfig() : preloadConfig;
            Leodanmu.updateHookStatus("idle", "local-config", "DanmakuConfigManager", "loadConfig", "", "");
        } catch (Exception e) {
            Leodanmu.updateHookStatus("configDialog", "exception", "", "", "", e.getMessage());
            configHolder[0] = DanmakuConfigManager.loadConfig(activity);
            if (configHolder[0] == null) configHolder[0] = new DanmakuConfig();
        }

        activity.runOnUiThread(() -> {
            try {
                ThemeColors colors = getThemeColors(activity);
                DanmakuConfig config = configHolder[0] != null ? configHolder[0] : DanmakuConfigManager.loadConfig(activity);

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                // 外层 ScrollView
                ScrollView rootScroll = new ScrollView(activity);
                rootScroll.setBackgroundColor(Color.TRANSPARENT);
                rootScroll.setClipChildren(false);
                rootScroll.setClipToPadding(false);

                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setPadding(dpToPx(activity, 24), dpToPx(activity, 20),
                        dpToPx(activity, 24), dpToPx(activity, 20));

                // 圆角背景
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(colors.bgPrimary);
                bg.setCornerRadius(dpToPx(activity, 16));
                mainLayout.setBackground(bg);

                TextView title = new TextView(activity);
                title.setText("Leo弹幕配置");
                title.setTextSize(24);
                title.setTextColor(colors.textPrimary);
                title.setGravity(Gravity.CENTER);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setPadding(0, 0, 0, dpToPx(activity, 8));
                mainLayout.addView(title);

                // 本机IP小字
                LinearLayout ipLine = new LinearLayout(activity);
                ipLine.setOrientation(LinearLayout.HORIZONTAL);
                ipLine.setGravity(Gravity.CENTER);
                ipLine.setPadding(0, 0, 0, dpToPx(activity, 12));

                TextView ipLabel = new TextView(activity);
                ipLabel.setText("本机IP: ");
                ipLabel.setTextSize(12);
                ipLabel.setTextColor(colors.textSecondary);
                ipLine.addView(ipLabel);

                TextView ipValue = new TextView(activity);
                String ip = NetworkUtils.getLocalIpAddress();
                ipValue.setText(ip);
                ipValue.setTextSize(12);
                ipValue.setTextColor(colors.textPrimary);
                ipValue.setTypeface(null, android.graphics.Typeface.BOLD);
                ipLine.addView(ipValue);

                mainLayout.addView(ipLine);

                // API地址
                TextView apiLabel = new TextView(activity);
                apiLabel.setText("弹幕API地址");
                apiLabel.setTextSize(14);
                apiLabel.setTextColor(colors.textPrimary);
                apiLabel.setPadding(0, 0, 0, dpToPx(activity, 8));
                mainLayout.addView(apiLabel);

                EditText apiInput = new EditText(activity);
                apiInput.setText(TextUtils.join("\n", config.getApiUrls()));
                apiInput.setHint("例如: https://example.com/87654321");
                apiInput.setMinLines(3);
                apiInput.setMaxLines(5);
                apiInput.setTextColor(colors.textPrimary);
                apiInput.setTextSize(13);
                apiInput.setPadding(dpToPx(activity, 12), dpToPx(activity, 12),
                        dpToPx(activity, 12), dpToPx(activity, 12));
                apiInput.setHintTextColor(colors.textTertiary);
                setupEditTextBorder(apiInput, activity, colors);
                mainLayout.addView(apiInput);

                // 主内容区：左侧两张卡片并排，底部二维码单独放置
                int labelWidth = dpToPx(activity, 104);
                int sectionGap = dpToPx(activity, 12);
                int rowGap = dpToPx(activity, 8);
                int cardHeight = dpToPx(activity, 220);
                DisplayMetrics screenDm = activity.getResources().getDisplayMetrics();
                int screenWidthDp = (int) (screenDm.widthPixels / screenDm.density);
                boolean isCompactWidth = screenWidthDp < 500;

                LinearLayout cardsRow = new LinearLayout(activity);
                cardsRow.setOrientation(isCompactWidth ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                cardsRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cardsRow.setGravity(Gravity.TOP);
                cardsRow.setClipChildren(false);
                cardsRow.setClipToPadding(false);
                cardsRow.setPadding(0, dpToPx(activity, 16), 0, 0);

                LinearLayout settingsWrap = new LinearLayout(activity);
                settingsWrap.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams settingsWrapParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (isCompactWidth) {
                    settingsWrapParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    settingsWrapParams.weight = 0;
                    settingsWrapParams.rightMargin = 0;
                    settingsWrapParams.bottomMargin = dpToPx(activity, 8);
                } else {
                    settingsWrapParams.rightMargin = dpToPx(activity, 8);
                }
                settingsWrap.setLayoutParams(settingsWrapParams);

                TextView settingsHeader = new TextView(activity);
                settingsHeader.setText("常用设置");
                settingsHeader.setTextSize(13);
                settingsHeader.setTextColor(colors.textSecondary);
                settingsHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                settingsHeader.setPadding(dpToPx(activity, 4), 0, 0, dpToPx(activity, 6));
                settingsWrap.addView(settingsHeader);

                LinearLayout settingsCard = new LinearLayout(activity);
                settingsCard.setOrientation(LinearLayout.VERTICAL);
                settingsCard.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cardHeight));
                settingsCard.setPadding(dpToPx(activity, 14), dpToPx(activity, 12),
                        dpToPx(activity, 14), dpToPx(activity, 12));
                GradientDrawable settingsBg = new GradientDrawable();
                settingsBg.setColor(colors.bgSecondary);
                settingsBg.setCornerRadius(dpToPx(activity, 12));
                settingsCard.setBackground(settingsBg);

                // 第零行：时移
                LinearLayout offsetRow = new LinearLayout(activity);
                offsetRow.setOrientation(LinearLayout.HORIZONTAL);
                offsetRow.setGravity(Gravity.CENTER_VERTICAL);
                offsetRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                offsetRow.setClipChildren(false);
                offsetRow.setFocusable(false);
                offsetRow.setFocusableInTouchMode(false);

                TextView offsetLabel = new TextView(activity);
                offsetLabel.setText("时移");
                offsetLabel.setTextSize(14);
                offsetLabel.setTextColor(colors.textPrimary);
                offsetLabel.setPadding(dpToPx(activity, 6), 0, dpToPx(activity, 8), 0);
                offsetLabel.setFocusable(false);
                LinearLayout.LayoutParams offsetLabelParams = new LinearLayout.LayoutParams(
                        labelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                offsetLabelParams.gravity = Gravity.CENTER_VERTICAL;
                offsetLabel.setLayoutParams(offsetLabelParams);

                final int[] currentOffset = new int[]{config.getDanmakuTimeOffsetMs()};

                View offsetSpacer = new View(activity);
                LinearLayout.LayoutParams offsetSpacerParams = new LinearLayout.LayoutParams(0, 0, 1);
                offsetSpacer.setLayoutParams(offsetSpacerParams);
                offsetRow.addView(offsetLabel);
                offsetRow.addView(offsetSpacer);

                Button offsetDecBtn = new Button(activity);
                offsetDecBtn.setText("－");
                offsetDecBtn.setTextSize(16);
                offsetDecBtn.setTextColor(colors.textPrimary);
                offsetDecBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                offsetDecBtn.setFocusable(true);
                offsetDecBtn.setFocusableInTouchMode(true);
                offsetDecBtn.setClickable(true);

                int iconSize = dpToPx(activity, 30);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
                iconParams.setMargins(dpToPx(activity, 2), dpToPx(activity, 6), dpToPx(activity, 2), dpToPx(activity, 6));
                offsetDecBtn.setLayoutParams(iconParams);
                offsetDecBtn.setPadding(0, 0, 0, 0);

                GradientDrawable iconBg = new GradientDrawable();
                iconBg.setShape(GradientDrawable.OVAL);
                iconBg.setColor(colors.bgSecondary);
                iconBg.setStroke(dpToPx(activity, 1), colors.divider);
                offsetDecBtn.setBackground(iconBg);

                TextView offsetVal = new TextView(activity);
                offsetVal.setText(formatOffsetMs(currentOffset[0]));
                offsetVal.setTextSize(12);
                offsetVal.setTextColor(colors.textSecondary);
                offsetVal.setGravity(Gravity.CENTER);
                offsetVal.setPadding(dpToPx(activity, 4), 0, dpToPx(activity, 4), 0);

                Button offsetIncBtn = new Button(activity);
                offsetIncBtn.setText("＋");
                offsetIncBtn.setTextSize(16);
                offsetIncBtn.setTextColor(colors.textPrimary);
                offsetIncBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                offsetIncBtn.setFocusable(true);
                offsetIncBtn.setFocusableInTouchMode(true);
                offsetIncBtn.setClickable(true);
                offsetIncBtn.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
                offsetIncBtn.setPadding(0, 0, 0, 0);
                ((ViewGroup.MarginLayoutParams) offsetIncBtn.getLayoutParams()).setMargins(
                        dpToPx(activity, 2), dpToPx(activity, 6), dpToPx(activity, 2), dpToPx(activity, 6));

                GradientDrawable iconBg2 = new GradientDrawable();
                iconBg2.setShape(GradientDrawable.OVAL);
                iconBg2.setColor(colors.bgSecondary);
                iconBg2.setStroke(dpToPx(activity, 1), colors.divider);
                offsetIncBtn.setBackground(iconBg2);

                View.OnFocusChangeListener iconFocus = (v, hasFocus) -> {
                    GradientDrawable circleBg = new GradientDrawable();
                    circleBg.setShape(GradientDrawable.OVAL);
                    circleBg.setColor(colors.bgSecondary);
                    if (hasFocus) {
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start();
                        circleBg.setStroke(dpToPx(activity, 2), colors.focusBorder);
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                        circleBg.setStroke(dpToPx(activity, 1), colors.divider);
                    }
                    v.setBackground(circleBg);
                };
                offsetDecBtn.setOnFocusChangeListener(iconFocus);
                offsetIncBtn.setOnFocusChangeListener(iconFocus);

                offsetDecBtn.setOnClickListener(v -> {
                    currentOffset[0] = Math.max(-300000, currentOffset[0] - 500);
                    offsetVal.setText(formatOffsetMs(currentOffset[0]));
                });

                offsetIncBtn.setOnClickListener(v -> {
                    currentOffset[0] = Math.min(300000, currentOffset[0] + 500);
                    offsetVal.setText(formatOffsetMs(currentOffset[0]));
                });

                offsetRow.addView(offsetDecBtn);
                offsetRow.addView(offsetVal);
                offsetRow.addView(offsetIncBtn);
                settingsCard.addView(offsetRow);

                // 第一行：推送提示开关
                LinearLayout toastRow = new LinearLayout(activity);
                toastRow.setOrientation(LinearLayout.HORIZONTAL);
                toastRow.setGravity(Gravity.CENTER_VERTICAL);
                toastRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                toastRow.setClipChildren(false);
                toastRow.setFocusable(false);
                toastRow.setFocusableInTouchMode(false);

                LinearLayout.LayoutParams toastRowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                toastRowParams.topMargin = rowGap;
                toastRow.setLayoutParams(toastRowParams);

                TextView toastLabel = new TextView(activity);
                toastLabel.setText("推送提示");
                toastLabel.setTextSize(14);
                toastLabel.setTextColor(colors.textPrimary);
                toastLabel.setPadding(dpToPx(activity, 6), 0, dpToPx(activity, 8), 0);
                toastLabel.setFocusable(false);
                LinearLayout.LayoutParams toastLabelParams = new LinearLayout.LayoutParams(
                        labelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                toastLabelParams.gravity = Gravity.CENTER_VERTICAL;
                toastLabel.setLayoutParams(toastLabelParams);

                Switch toastSwitch = new Switch(activity);
                toastSwitch.setChecked(config.isPushToastEnabled());
                toastSwitch.setFocusable(true);
                toastSwitch.setFocusableInTouchMode(true);
                toastSwitch.setClickable(true);

                toastSwitch.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start();
                        GradientDrawable border = new GradientDrawable();
                        border.setShape(GradientDrawable.RECTANGLE);
                        border.setCornerRadius(dpToPx(activity, 16));
                        border.setStroke(dpToPx(activity, 2), colors.focusBorder);
                        v.setBackground(border);
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                        v.setBackground(null);
                    }
                });

                toastRow.addView(toastLabel);
                toastRow.addView(toastSwitch);
                settingsCard.addView(toastRow);

                // 第二行：自动推送弹幕
                LinearLayout autoPushRow = new LinearLayout(activity);
                autoPushRow.setOrientation(LinearLayout.HORIZONTAL);
                autoPushRow.setGravity(Gravity.CENTER_VERTICAL);
                autoPushRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                autoPushRow.setClipChildren(false);
                autoPushRow.setFocusable(false);
                autoPushRow.setFocusableInTouchMode(false);

                LinearLayout.LayoutParams autoRowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                autoRowParams.topMargin = rowGap;
                autoPushRow.setLayoutParams(autoRowParams);

                TextView switchLabel = new TextView(activity);
                switchLabel.setText("自动推送弹幕");
                switchLabel.setTextSize(14);
                switchLabel.setTextColor(colors.textPrimary);
                switchLabel.setPadding(dpToPx(activity, 6), 0, dpToPx(activity, 8), 0);
                switchLabel.setFocusable(false);
                LinearLayout.LayoutParams switchLabelParams = new LinearLayout.LayoutParams(
                        labelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                switchLabelParams.gravity = Gravity.CENTER_VERTICAL;
                switchLabel.setLayoutParams(switchLabelParams);

                Switch autoSwitch = new Switch(activity);
                autoSwitch.setChecked(config.isAutoPushEnabled());
                autoSwitch.setFocusable(true);
                autoSwitch.setFocusableInTouchMode(true);
                autoSwitch.setClickable(true);

                autoSwitch.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start();
                        GradientDrawable border = new GradientDrawable();
                        border.setShape(GradientDrawable.RECTANGLE);
                        border.setCornerRadius(dpToPx(activity, 16));
                        border.setStroke(dpToPx(activity, 2), colors.focusBorder);
                        v.setBackground(border);
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                        v.setBackground(null);
                    }
                });

                autoPushRow.addView(switchLabel);
                autoPushRow.addView(autoSwitch);
                settingsCard.addView(autoPushRow);

                // ========== 主题切换行 ==========
                LinearLayout themeRow = new LinearLayout(activity);
                themeRow.setOrientation(LinearLayout.HORIZONTAL);
                themeRow.setGravity(Gravity.CENTER_VERTICAL);
                themeRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                themeRow.setClipChildren(false);
                themeRow.setFocusable(false);
                themeRow.setFocusableInTouchMode(false);

                LinearLayout.LayoutParams themeRowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                themeRowParams.topMargin = rowGap;
                themeRow.setLayoutParams(themeRowParams);
                themeRow.setPadding(0, dpToPx(activity, 2), 0, dpToPx(activity, 2));

                TextView themeLabel = new TextView(activity);
                themeLabel.setText("主题");
                themeLabel.setTextSize(14);
                themeLabel.setTextColor(colors.textPrimary);
                themeLabel.setPadding(dpToPx(activity, 6), 0, dpToPx(activity, 8), 0);
                themeLabel.setFocusable(false);
                LinearLayout.LayoutParams themeLabelParams = new LinearLayout.LayoutParams(
                        labelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                themeLabelParams.gravity = Gravity.CENTER_VERTICAL;
                themeLabel.setLayoutParams(themeLabelParams);

                Button darkThemeBtn = new Button(activity);
                Button lightThemeBtn = new Button(activity);
                darkThemeBtn.setText("深色");
                lightThemeBtn.setText("浅色");
                darkThemeBtn.setTextSize(13);
                lightThemeBtn.setTextSize(13);
                darkThemeBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                lightThemeBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                darkThemeBtn.setPadding(dpToPx(activity, 6), 0,
                        dpToPx(activity, 6), 0);
                lightThemeBtn.setPadding(dpToPx(activity, 6), 0,
                        dpToPx(activity, 6), 0);

                LinearLayout.LayoutParams themeBtnParams = new LinearLayout.LayoutParams(
                        0, dpToPx(activity, 36), 1);
                themeBtnParams.setMargins(dpToPx(activity, 4), 0, dpToPx(activity, 4), 0);
                darkThemeBtn.setLayoutParams(themeBtnParams);
                lightThemeBtn.setLayoutParams(themeBtnParams);

                // 设置初始选中样式
                if (config.getTheme() == 0) {
                    setButtonSelected(darkThemeBtn, true, colors, activity);
                    setButtonSelected(lightThemeBtn, false, colors, activity);
                } else {
                    setButtonSelected(darkThemeBtn, false, colors, activity);
                    setButtonSelected(lightThemeBtn, true, colors, activity);
                }

                // 自定义焦点监听器：仅改变边框
                View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
                    GradientDrawable drawable = (GradientDrawable) v.getBackground();
                    if (hasFocus) {
                        drawable.setStroke(dpToPx(activity, 2), colors.focusBorder);
                    } else {
                        drawable.setStroke(0, Color.TRANSPARENT);
                    }
                    v.setBackground(drawable);
                };
                darkThemeBtn.setOnFocusChangeListener(focusListener);
                lightThemeBtn.setOnFocusChangeListener(focusListener);

                themeRow.addView(themeLabel);
                themeRow.addView(darkThemeBtn);
                themeRow.addView(lightThemeBtn);
                settingsCard.addView(themeRow);

                // ========== 代理切换行 ==========
                LinearLayout proxyRow = new LinearLayout(activity);
                proxyRow.setOrientation(LinearLayout.HORIZONTAL);
                proxyRow.setGravity(Gravity.CENTER_VERTICAL);
                proxyRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                proxyRow.setClipChildren(false);
                proxyRow.setFocusable(false);
                proxyRow.setFocusableInTouchMode(false);

                LinearLayout.LayoutParams proxyRowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                proxyRowParams.topMargin = rowGap;
                proxyRow.setLayoutParams(proxyRowParams);
                proxyRow.setPadding(0, dpToPx(activity, 2), 0, dpToPx(activity, 2));

                TextView proxyLabel = new TextView(activity);
                proxyLabel.setText("代理");
                proxyLabel.setTextSize(14);
                proxyLabel.setTextColor(colors.textPrimary);
                proxyLabel.setPadding(dpToPx(activity, 6), 0, dpToPx(activity, 8), 0);
                proxyLabel.setFocusable(false);
                LinearLayout.LayoutParams proxyLabelParams = new LinearLayout.LayoutParams(
                        labelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                proxyLabelParams.gravity = Gravity.CENTER_VERTICAL;
                proxyLabel.setLayoutParams(proxyLabelParams);

                int currentProxyType = config.getProxyType();
                int proxyIconSize = dpToPx(activity, 22);

                // 三个圆形图标按钮，各占 1/3 宽度，居中（与上方主题按钮对齐）
                Button autoProxyBtn = makeProxyIcon(activity, "Au", proxyIconSize, 0, colors);
                Button goProxyBtn = makeProxyIcon(activity, "Go", proxyIconSize, 0, colors);
                Button javaProxyBtn = makeProxyIcon(activity, "Ja", proxyIconSize, 0, colors);

                setProxyIconSelected(autoProxyBtn, currentProxyType == 0, colors, activity);
                setProxyIconSelected(goProxyBtn, currentProxyType == 1, colors, activity);
                setProxyIconSelected(javaProxyBtn, currentProxyType == 2, colors, activity);

                View.OnFocusChangeListener proxyIconFocus = (v, hasFocus) -> {
                    Button btn = (Button) v;
                    boolean selected = btn.getTag() != null && (boolean) btn.getTag();
                    GradientDrawable proxyIconBg = new GradientDrawable();
                    proxyIconBg.setShape(GradientDrawable.OVAL);
                    proxyIconBg.setColor(selected ? LIGHT_THEME.focusBorder : colors.bgSecondary);
                    proxyIconBg.setStroke(hasFocus ? dpToPx(activity, 2) : dpToPx(activity, 1),
                            hasFocus ? colors.focusBorder : colors.divider);
                    if (hasFocus) {
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start();
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                    }
                    btn.setBackground(proxyIconBg);
                };
                autoProxyBtn.setOnFocusChangeListener(proxyIconFocus);
                goProxyBtn.setOnFocusChangeListener(proxyIconFocus);
                javaProxyBtn.setOnFocusChangeListener(proxyIconFocus);

                proxyRow.addView(proxyLabel);
                LinearLayout.LayoutParams proxySlotParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);

                LinearLayout slot1 = new LinearLayout(activity);
                slot1.setLayoutParams(proxySlotParams);
                slot1.setGravity(Gravity.CENTER);
                slot1.addView(autoProxyBtn);
                proxyRow.addView(slot1);

                LinearLayout slot2 = new LinearLayout(activity);
                slot2.setLayoutParams(proxySlotParams);
                slot2.setGravity(Gravity.CENTER);
                slot2.addView(goProxyBtn);
                proxyRow.addView(slot2);

                LinearLayout slot3 = new LinearLayout(activity);
                slot3.setLayoutParams(proxySlotParams);
                slot3.setGravity(Gravity.CENTER);
                slot3.addView(javaProxyBtn);
                proxyRow.addView(slot3);
                settingsCard.addView(proxyRow);
                settingsWrap.addView(settingsCard);

                LinearLayout actionWrap = new LinearLayout(activity);
                actionWrap.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams actionWrapParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (isCompactWidth) {
                    actionWrapParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    actionWrapParams.weight = 0;
                    actionWrapParams.leftMargin = 0;
                } else {
                    actionWrapParams.leftMargin = dpToPx(activity, 8);
                }
                actionWrap.setLayoutParams(actionWrapParams);

                TextView actionsHeader = new TextView(activity);
                actionsHeader.setText("工具操作");
                actionsHeader.setTextSize(13);
                actionsHeader.setTextColor(colors.textSecondary);
                actionsHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                actionsHeader.setPadding(dpToPx(activity, 4), 0, 0, dpToPx(activity, 6));
                actionWrap.addView(actionsHeader);

                LinearLayout actionCard = new LinearLayout(activity);
                actionCard.setOrientation(LinearLayout.VERTICAL);
                actionCard.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cardHeight));
                actionCard.setPadding(dpToPx(activity, 14), dpToPx(activity, 8),
                        dpToPx(activity, 14), dpToPx(activity, 8));
                GradientDrawable actionBg = new GradientDrawable();
                actionBg.setColor(colors.bgSecondary);
                actionBg.setCornerRadius(dpToPx(activity, 12));
                actionCard.setBackground(actionBg);

                int actionButtonHeight = dpToPx(activity, 40);
                int actionButtonGap = dpToPx(activity, 4);

                // 第四行：布局按钮
                Button remotePushBtn = createStaticBorderButton(activity, "Leo远程推送", colors);
                remotePushBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams remotePushParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, actionButtonHeight);
                remotePushBtn.setLayoutParams(remotePushParams);
                remotePushBtn.setOnClickListener(v -> {
                    try {
                        String localIp = NetworkUtils.getLocalIpAddress();
                        String remoteInputUrl = "http://" + localIp + ":9888";
                        showFloatingQRCodeDialog(activity, remoteInputUrl, "");
                    } catch (Exception e) {
                        Leodanmu.log("打开Leo远程推送二维码失败: " + e.getMessage());
                    }
                });
                actionCard.addView(remotePushBtn);

                // 第四行：布局按钮
                Button toolBtn = createStaticBorderButton(activity, "布局");
                toolBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, actionButtonHeight);
                toolParams.topMargin = actionButtonGap;
                toolBtn.setLayoutParams(toolParams);
                toolBtn.setOnClickListener(v -> showLpConfigDialog(activity));
                actionCard.addView(toolBtn);

                // 第五行：清空缓存按钮
                Button clearCacheBtn = createStaticBorderButton(activity, "清空缓存", colors);
                clearCacheBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, actionButtonHeight);
                clearParams.topMargin = actionButtonGap;
                clearCacheBtn.setLayoutParams(clearParams);
                clearCacheBtn.setOnClickListener(v -> {
                    Leodanmu.clearCache(activity);
                    Utils.safeShowToast(activity, "缓存已清空");
                    Leodanmu.log("用户手动清空缓存");
                });
                actionCard.addView(clearCacheBtn);

                // 第六行：弹幕日志（追加，不改前面5行布局）
                Button logBtn = createStaticBorderButton(activity, "弹幕日志");
                logBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, actionButtonHeight);
                logParams.topMargin = actionButtonGap;
                logBtn.setLayoutParams(logParams);
                logBtn.setOnClickListener(v -> {
                    try {
                        showLogDialog(activity);
                    } catch (Exception e) {
                        Leodanmu.log("打开弹幕日志失败: " + e.getMessage());
                    }
                });
                actionCard.addView(logBtn);

                // 代理状态显示
                TextView proxyStatusView = new TextView(activity);
                String proxyTypeName = ProxyManager.getProxyTypeName();
                String proxyStatus = ProxyManager.getProxyStatusText();
                proxyStatusView.setText(proxyTypeName + "：" + proxyStatus);
                proxyStatusView.setTextSize(12);
                proxyStatusView.setTextColor(colors.textSecondary);
                proxyStatusView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 30));
                statusParams.topMargin = actionButtonGap;
                proxyStatusView.setLayoutParams(statusParams);
                proxyStatusView.setPadding(dpToPx(activity, 6), 0, dpToPx(activity, 6), 0);
                actionCard.addView(proxyStatusView);

                View actionFill = new View(activity);
                actionFill.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                actionCard.addView(actionFill);
                actionWrap.addView(actionCard);

                cardsRow.addView(settingsWrap);
                cardsRow.addView(actionWrap);
                mainLayout.addView(cardsRow);

                // 底部按钮
                LinearLayout btnLayout = new LinearLayout(activity);
                btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                btnLayout.setGravity(Gravity.CENTER);

                Button resetBtn = createBorderButton(activity, "重置", colors);
                Button saveBtn = createBorderButton(activity, "保存生效", colors);

                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        0, dpToPx(activity, 44), 1);
                btnParams.setMargins(dpToPx(activity, 6), 0, dpToPx(activity, 6), 0);
                resetBtn.setLayoutParams(btnParams);
                saveBtn.setLayoutParams(btnParams);

                btnLayout.addView(resetBtn);
                btnLayout.addView(saveBtn);
                mainLayout.addView(btnLayout);

                // 将主布局放入 ScrollView
                rootScroll.addView(mainLayout);
                builder.setView(rootScroll);
                AlertDialog dialog = builder.create();
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

                // 代理按钮点击监听器（必须在此处设置，以便引用 dialog）
                autoProxyBtn.setOnClickListener(v -> {
                    if (config.getProxyType() != 0) {
                        config.setProxyType(0);
                        DanmakuConfigManager.saveConfig(activity, config);
                        dialog.dismiss();
                        showCombinedConfigDialog(activity);
                    }
                });
                goProxyBtn.setOnClickListener(v -> {
                    config.setProxyType(1);
                    DanmakuConfigManager.saveConfig(activity, config);
                    dialog.dismiss();
                    showCombinedConfigDialog(activity);
                    ProxyManager.switchToGoProxy(activity.getApplicationContext());
                });
                javaProxyBtn.setOnClickListener(v -> {
                    config.setProxyType(2);
                    DanmakuConfigManager.saveConfig(activity, config);
                    dialog.dismiss();
                    showCombinedConfigDialog(activity);
                    ProxyManager.switchToJavaProxy(activity.getApplicationContext());
                });

                // 主题按钮点击监听器
                darkThemeBtn.setOnClickListener(v -> {
                    if (config.getTheme() != 0) {
                        config.setTheme(0);
                        DanmakuConfigManager.saveConfig(activity, config);
                        dialog.dismiss();
                        showCombinedConfigDialog(activity);
                    }
                });
                lightThemeBtn.setOnClickListener(v -> {
                    if (config.getTheme() != 1) {
                        config.setTheme(1);
                        DanmakuConfigManager.saveConfig(activity, config);
                        dialog.dismiss();
                        showCombinedConfigDialog(activity);
                    }
                });

                resetBtn.setOnClickListener(v -> {
                    apiInput.setText("");
                    autoSwitch.setChecked(true);
                    Utils.safeShowToast(activity, "已重置为默认值");
                });

                saveBtn.setOnClickListener(v -> {
                    String text = apiInput.getText().toString();
                    String[] lines = text.split("\n");
                    Set<String> newUrls = new HashSet<>();
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!TextUtils.isEmpty(trimmed) && trimmed.startsWith("http")) {
                            newUrls.add(trimmed);
                        }
                    }
                    DanmakuConfig latestConfig = DanmakuConfigManager.loadConfig(activity);
                    if (latestConfig == null) latestConfig = new DanmakuConfig();
                    if (!newUrls.isEmpty()) {
                        latestConfig.setApiUrls(newUrls);
                    }
                    latestConfig.setAutoPushEnabled(autoSwitch.isChecked());
                    latestConfig.setPushToastEnabled(toastSwitch.isChecked());
                    latestConfig.setTheme(config.getTheme());
                    latestConfig.setDanmakuTimeOffsetMs(currentOffset[0]);
                    DanmakuConfigManager.saveConfig(activity, latestConfig);
                    Utils.safeShowToast(activity, "配置已保存");
                    Leodanmu.log("已保存新配置，时移偏移: " + currentOffset[0] + "ms");
                    // 实时重推当前弹幕，使时移即时生效
                    int lastId = DanmakuManager.lastDanmakuId;
                    if (lastId > 0) {
                        DanmakuItem currentItem = DanmakuManager.lastDanmakuItemMap.get(lastId);
                        if (currentItem != null) {
                            Leodanmu.log("时移调整，实时重推: " + currentItem.getEpTitle());
                            LeoDanmakuService.pushDanmakuDirect(currentItem, activity, false, false, true);
                        }
                    }
                    dialog.dismiss();
                });

                safeShowDialog(activity, dialog);
            } catch (Exception e) {
                Leodanmu.log("显示合并配置对话框异常: " + e.getMessage());
            }
        });
    }

    // 独立的布局配置对话框
    public static void showLpConfigDialog(Context ctx) {
        if (!(ctx instanceof Activity)) return;
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            try {
                ThemeColors colors = getThemeColors(activity);
                // 重新获取最新配置
                DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                Leodanmu.log("布局配置加载: width=" + Leodanmu.getRuntimeLpWidth() + ", height=" + Leodanmu.getRuntimeLpHeight() + ", alpha=" + Leodanmu.getRuntimeLpAlpha());

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setBackgroundColor(colors.bgPrimary);
                mainLayout.setPadding(dpToPx(activity, 24), dpToPx(activity, 20),
                        dpToPx(activity, 24), dpToPx(activity, 20));

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(colors.bgPrimary);
                bg.setCornerRadius(dpToPx(activity, 16));
                mainLayout.setBackground(bg);

                TextView title = new TextView(activity);
                title.setText("布局配置");
                title.setTextSize(24);
                title.setTextColor(colors.textPrimary);
                title.setGravity(Gravity.CENTER);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setPadding(0, dpToPx(activity, 8), 0, dpToPx(activity, 20));
                mainLayout.addView(title);

                // 宽度
                LinearLayout widthLayout = new LinearLayout(activity);
                widthLayout.setOrientation(LinearLayout.HORIZONTAL);
                widthLayout.setGravity(Gravity.CENTER_VERTICAL);
                TextView widthLabel = new TextView(activity);
                widthLabel.setText("宽度 (0.1-1.0):");
                widthLabel.setTextSize(14);
                widthLabel.setTextColor(colors.textPrimary);
                widthLabel.setPadding(0, 0, dpToPx(activity, 10), 0);
                EditText widthInput = new EditText(activity);
                widthInput.setText(String.valueOf(config.getLpWidth()));
                widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                widthInput.setBackgroundColor(colors.inputBg);
                widthInput.setTextColor(colors.textPrimary);
                widthInput.setHintTextColor(colors.textTertiary);
                widthLayout.addView(widthLabel);
                widthLayout.addView(widthInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                mainLayout.addView(widthLayout);

                // 高度
                LinearLayout heightLayout = new LinearLayout(activity);
                heightLayout.setOrientation(LinearLayout.HORIZONTAL);
                heightLayout.setGravity(Gravity.CENTER_VERTICAL);
                TextView heightLabel = new TextView(activity);
                heightLabel.setText("高度 (0.1-1.0):");
                heightLabel.setTextSize(14);
                heightLabel.setTextColor(colors.textPrimary);
                heightLabel.setPadding(0, 0, dpToPx(activity, 10), 0);
                EditText heightInput = new EditText(activity);
                heightInput.setText(String.valueOf(config.getLpHeight()));
                heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                heightInput.setBackgroundColor(colors.inputBg);
                heightInput.setTextColor(colors.textPrimary);
                heightInput.setHintTextColor(colors.textTertiary);
                heightLayout.addView(heightLabel);
                heightLayout.addView(heightInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                mainLayout.addView(heightLayout);

                // 透明度
                LinearLayout alphaLayout = new LinearLayout(activity);
                alphaLayout.setOrientation(LinearLayout.HORIZONTAL);
                alphaLayout.setGravity(Gravity.CENTER_VERTICAL);
                TextView alphaLabel = new TextView(activity);
                alphaLabel.setText("透明 (0.1-1.0):");
                alphaLabel.setTextSize(14);
                alphaLabel.setTextColor(colors.textPrimary);
                alphaLabel.setPadding(0, 0, dpToPx(activity, 10), 0);
                EditText alphaInput = new EditText(activity);
                alphaInput.setText(String.valueOf(config.getLpAlpha()));
                alphaInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                alphaInput.setBackgroundColor(colors.inputBg);
                alphaInput.setTextColor(colors.textPrimary);
                alphaInput.setHintTextColor(colors.textTertiary);
                alphaLayout.addView(alphaLabel);
                alphaLayout.addView(alphaInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                mainLayout.addView(alphaLayout);

                LinearLayout btnLayout = new LinearLayout(activity);
                btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                btnLayout.setGravity(Gravity.CENTER);

                Button saveBtn = createBorderButton(activity, "保存", colors);
                Button cancelBtn = createBorderButton(activity, "取消", colors);

                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        0, dpToPx(activity, 44), 1);
                btnParams.setMargins(dpToPx(activity, 6), 0, dpToPx(activity, 6), 0);
                saveBtn.setLayoutParams(btnParams);
                cancelBtn.setLayoutParams(btnParams);

                btnLayout.addView(saveBtn);
                btnLayout.addView(cancelBtn);
                mainLayout.addView(btnLayout);

                builder.setView(mainLayout);
                AlertDialog dialog = builder.create();
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

                saveBtn.setOnClickListener(v -> {
                    try {
                        float width = Float.parseFloat(widthInput.getText().toString());
                        float height = Float.parseFloat(heightInput.getText().toString());
                        float alpha = Float.parseFloat(alphaInput.getText().toString());
                        if (width > 1.0f) width = 1.0f;
                        if (width < 0.1f) width = 0.1f;
                        if (height > 1.0f) height = 1.0f;
                        if (height < 0.1f) height = 0.1f;
                        if (alpha > 1.0f) alpha = 1.0f;
                        if (alpha < 0.1f) alpha = 0.1f;
                        config.setLpWidth(width);
                        config.setLpHeight(height);
                        config.setLpAlpha(alpha);
                        DanmakuConfigManager.saveConfig(activity, config);
                        Leodanmu.log("布局配置保存: width=" + width + ", height=" + height + ", alpha=" + alpha);
                        Utils.safeShowToast(activity, "布局配置已保存");
                        dialog.dismiss();
                    } catch (NumberFormatException e) {
                        Utils.safeShowToast(activity, "请输入有效的数字");
                    }
                });

                cancelBtn.setOnClickListener(v -> dialog.dismiss());
                safeShowDialog(activity, dialog);
            } catch (Exception e) {
                Leodanmu.log("显示布局配置对话框异常: " + e.getMessage());
            }
        });
    }

    // 旧配置对话框重定向到合并对话框
    public static void showConfigDialog(Context ctx) {
        showCombinedConfigDialog(ctx);
    }

    // ========== 日志对话框 ==========
    public static void showLogDialog(Context ctx) {
        if (!(ctx instanceof Activity)) return;
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            try {
                ThemeColors colors = getThemeColors(activity);
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setBackgroundColor(colors.bgPrimary);

                LinearLayout titleLayout = new LinearLayout(activity);
                titleLayout.setOrientation(LinearLayout.VERTICAL);
                titleLayout.setBackgroundColor(colors.bgSecondary);
                titleLayout.setPadding(dpToPx(activity, 20), dpToPx(activity, 16),
                        dpToPx(activity, 20), dpToPx(activity, 16));

                TextView titleText = new TextView(activity);
                titleText.setText("Leo弹幕日志");
                titleText.setTextSize(20);
                titleText.setTextColor(colors.textPrimary);
                titleText.setTypeface(null, android.graphics.Typeface.BOLD);
                titleLayout.addView(titleText);
                mainLayout.addView(titleLayout);

                ScrollView scrollView = new ScrollView(activity);
                scrollView.setBackgroundColor(colors.bgPrimary);
                LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
                scrollView.setLayoutParams(scrollParams);

                TextView logView = new TextView(activity);
                String logContent = Leodanmu.getLogContent();
                if (ProxyManager.hasLogs()) {
                    logContent += "\n\n===== 代理日志 =====\n" + ProxyManager.getLogContent();
                }
                logView.setText(logContent);
                logView.setTextSize(11);
                logView.setTextColor(colors.textSecondary);
                logView.setPadding(dpToPx(activity, 16), dpToPx(activity, 16),
                        dpToPx(activity, 16), dpToPx(activity, 16));
                logView.setBackgroundColor(colors.inputBg);
                logView.setTypeface(android.graphics.Typeface.MONOSPACE);
                logView.setLineSpacing(dpToPx(activity, 1), 1.4f);
                scrollView.addView(logView);
                mainLayout.addView(scrollView);

                LinearLayout btnLayout = new LinearLayout(activity);
                btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                btnLayout.setGravity(Gravity.CENTER);
                btnLayout.setPadding(dpToPx(activity, 16), dpToPx(activity, 12),
                        dpToPx(activity, 16), dpToPx(activity, 12));
                btnLayout.setBackgroundColor(colors.bgPrimary);

                Button clearButton = createBorderButton(activity, "清空", colors);
                Button copyButton = createBorderButton(activity, "复制日志", colors);
                Button closeButton = createBorderButton(activity, "关闭", colors);

                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        0, dpToPx(activity, 44), 1);
                btnParams.setMargins(dpToPx(activity, 6), 0, dpToPx(activity, 6), 0);
                clearButton.setLayoutParams(btnParams);
                copyButton.setLayoutParams(btnParams);
                closeButton.setLayoutParams(btnParams);

                btnLayout.addView(clearButton);
                btnLayout.addView(copyButton);
                btnLayout.addView(closeButton);
                mainLayout.addView(btnLayout);

                builder.setView(mainLayout);
                AlertDialog dialog = builder.create();

                clearButton.setOnClickListener(v -> {
                    Leodanmu.clearLogs();
                    ProxyManager.clearLogs();
                    dialog.dismiss();
                    showLogDialog(ctx);
                });

                copyButton.setOnClickListener(v -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            String currentLog = logView.getText() == null ? "" : logView.getText().toString();
                            ClipData clip = ClipData.newPlainText("leo_danmaku_log", currentLog);
                            cm.setPrimaryClip(clip);
                            Utils.safeShowToast(activity, "已复制弹幕日志");
                        } else {
                            Utils.safeShowToast(activity, "复制日志失败：剪贴板不可用");
                        }
                    } catch (Exception e) {
                        Utils.safeShowToast(activity, "复制日志失败: " + e.getMessage());
                    }
                });

                closeButton.setOnClickListener(v -> dialog.dismiss());

                safeShowDialog(activity, dialog);
            } catch (Exception e) {
                Leodanmu.log("显示日志对话框异常: " + e.getMessage());
            }
        });
    }

    // ========== 搜索对话框（整体可滚动，圆角固定） ==========
    public static void showSearchDialog(Activity activity, String initialKeyword) {
        registerActivity(activity);

        if (activity.isFinishing() || activity.isDestroyed()) {
            unregisterActivity(activity);
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                ThemeColors colors = getThemeColors(activity);
                DanmakuConfig config = DanmakuConfigManager.getConfig(activity);

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                // 外层 ScrollView，作为圆角背景容器
                ScrollView rootScroll = new ScrollView(activity);
                rootScroll.setClipChildren(false);
                rootScroll.setClipToPadding(false);
                int bgColor;
                if (config.getTheme() == 1) { // 浅色主题
                    bgColor = colors.bgPrimary; // 不透明
                } else { // 深色主题
                    bgColor = 0x1A000000; // 纯黑半透明（原有效果）
                }
                GradientDrawable scrollBg = new GradientDrawable();
                scrollBg.setColor(bgColor);
                scrollBg.setCornerRadius(dpToPx(activity, 16));
                rootScroll.setBackground(scrollBg);

                // 主布局（透明背景）
                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setPadding(dpToPx(activity, 15), dpToPx(activity, 10),
                        dpToPx(activity, 15), dpToPx(activity, 10));
                mainLayout.setClipChildren(false);
                mainLayout.setClipToPadding(false);
                mainLayout.setBackgroundColor(Color.TRANSPARENT);

                // 标题
                TextView title = new TextView(activity);
                title.setText("Leo弹幕搜索");
                title.setTextSize(14);
                title.setTextColor(colors.textPrimary);
                title.setGravity(Gravity.CENTER);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setPadding(0, dpToPx(activity, 2), 0, dpToPx(activity, 5));
                mainLayout.addView(title);

                // 搜索框容器
                LinearLayout searchContainer = new LinearLayout(activity);
                searchContainer.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                searchContainer.setGravity(Gravity.CENTER);
                searchContainer.setClipChildren(false);
                searchContainer.setClipToPadding(false);

                LinearLayout searchLayout = new LinearLayout(activity);
                searchLayout.setOrientation(LinearLayout.HORIZONTAL);
                searchLayout.setGravity(Gravity.CENTER_VERTICAL);
                searchLayout.setClipChildren(false);
                searchLayout.setClipToPadding(false);

                final EditText searchInput = new EditText(activity);
                searchInput.setHint("输入关键词...");
                String cachedKeyword = SharedPreferencesService.getSearchKeywordCache(activity, initialKeyword);
                searchInput.setText(cachedKeyword);
                searchInput.setHintTextColor(colors.textTertiary);
                searchInput.setBackgroundColor(Color.TRANSPARENT);
                searchInput.setPadding(dpToPx(activity, 12), dpToPx(activity, 10),
                        dpToPx(activity, 12), dpToPx(activity, 10));
                searchInput.setTextSize(14);
                searchInput.setTextColor(colors.textPrimary);
                LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                        dpToPx(activity, 300), dpToPx(activity, 44));
                inputParams.setMargins(0, 0, dpToPx(activity, 8), 0);
                searchInput.setLayoutParams(inputParams);
                if (config.getTheme() == 1) {
                    setupEditTextBorder(searchInput, activity, colors);
                } else {
                    setupTransparentEditTextBorder(searchInput, activity, colors);
                }

                Button searchBtn = createBorderButton(activity, "搜索", colors);
                searchBtn.setLayoutParams(new LinearLayout.LayoutParams(
                        dpToPx(activity, 70), dpToPx(activity, 44)));

                searchBtn.setOnLongClickListener(v -> {
                    showRemoteInputQRCode(activity);
                    return true;
                });

                searchBtn.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            v.setTag(System.currentTimeMillis());
                        } else if (event.getAction() == KeyEvent.ACTION_UP) {
                            Long start = (Long) v.getTag();
                            if (start != null && System.currentTimeMillis() - start > 500) {
                                showRemoteInputQRCode(activity);
                                return true;
                            }
                        }
                    }
                    return false;
                });

                searchLayout.addView(searchInput);
                searchLayout.addView(searchBtn);
                searchContainer.addView(searchLayout);
                mainLayout.addView(searchContainer);

                // 页签容器
                LinearLayout tabContainer = new LinearLayout(activity);
                tabContainer.setOrientation(LinearLayout.HORIZONTAL);
                tabContainer.setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 8));
                if (config.getTheme() == 1) {
                    tabContainer.setBackgroundColor(colors.bgSecondary);
                } else {
                    tabContainer.setBackgroundColor(0x1A000000);
                }
                LinearLayout.LayoutParams tabContainerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 48));
                tabContainerParams.setMargins(0, dpToPx(activity, 2), 0, dpToPx(activity, 8));
                tabContainer.setLayoutParams(tabContainerParams);
                tabContainer.setClipChildren(false);
                tabContainer.setClipToPadding(false);
                mainLayout.addView(tabContainer);

                // 结果容器
                LinearLayout resultContainer = new LinearLayout(activity);
                resultContainer.setOrientation(LinearLayout.VERTICAL);
                resultContainer.setPadding(dpToPx(activity, 8), dpToPx(activity, 8),
                        dpToPx(activity, 8), dpToPx(activity, 8));
                resultContainer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                if (config.getTheme() == 1) {
                    // 浅色主题：纯白背景
                    resultContainer.setBackgroundColor(colors.inputBg);
                } else {
                    resultContainer.setBackgroundColor(0x1A000000);
                }
                resultContainer.setClipChildren(false);
                resultContainer.setClipToPadding(false);
                LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                resultContainer.setLayoutParams(resultParams);

                mainLayout.addView(resultContainer);
                rootScroll.addView(mainLayout);
                builder.setView(rootScroll);

                final AlertDialog dialog = builder.create();
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

                // 搜索按钮点击逻辑
                searchBtn.setOnClickListener(v -> {
                    String keyword = searchInput.getText().toString().trim();
                    if (TextUtils.isEmpty(keyword)) {
                        Utils.safeShowToast(activity, "请输入关键词");
                        return;
                    }

                    if (!keyword.equals(initialKeyword)) {
                        SharedPreferencesService.saveSearchKeywordCache(activity, initialKeyword, keyword);
                        Leodanmu.log("已保存新的搜索缓存: " + initialKeyword + " -> " + keyword);
                    } else {
                        SharedPreferencesService.saveSearchKeywordCache(activity, initialKeyword, "");
                        Leodanmu.log("已清空搜索缓存: " + initialKeyword);
                    }

                    resultContainer.removeAllViews();
                    tabContainer.removeAllViews();
                    TextView loading = new TextView(activity);
                    loading.setText("正在搜索: " + keyword);
                    loading.setGravity(Gravity.CENTER);
                    loading.setPadding(0, 20, 0, 20);
                    loading.setTextColor(colors.textSecondary);
                    resultContainer.addView(loading);

                    new Thread(() -> {
                        List<DanmakuItem> results = LeoDanmakuService.manualSearch(keyword, activity);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            resultContainer.removeAllViews();
                            tabContainer.removeAllViews();

                            if (results.isEmpty()) {
                                TextView empty = new TextView(activity);
                                empty.setText("未找到结果");
                                empty.setGravity(Gravity.CENTER);
                                empty.setPadding(0, 50, 0, 50);
                                empty.setTextColor(colors.textSecondary);
                                resultContainer.addView(empty);
                                return;
                            }

                            Map<String, List<DanmakuItem>> groupedResults = new HashMap<>();
                            for (DanmakuItem item : results) {
                                String from = item.from != null ? item.from : "默认";
                                if (!groupedResults.containsKey(from)) {
                                    groupedResults.put(from, new ArrayList<>());
                                }
                                groupedResults.get(from).add(item);
                            }

                            List<String> tabs = new ArrayList<>(groupedResults.keySet());
                            Collections.sort(tabs);

                            for (int i = 0; i < tabs.size(); i++) {
                                String tabName = tabs.get(i);
                                Button tabBtn = createBorderButton(activity, tabName);
                                tabBtn.setTag(tabName);
                                LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                                tabParams.setMargins(dpToPx(activity, 4), 0, dpToPx(activity, 4), 0);
                                tabBtn.setLayoutParams(tabParams);

                                final int tabIndex = i;
                                tabBtn.setOnClickListener(v1 -> {
                                    showResultsForTab(resultContainer, groupedResults.get(tabName), activity, dialog);
                                });

                                tabContainer.addView(tabBtn);

                                boolean containsLastUrl = containsMatchingItem(groupedResults.get(tabName));

                                if ((DanmakuManager.lastDanmakuUrl == null || DanmakuManager.lastDanmakuUrl.isEmpty()) && i == 0) {
                                    showResultsForTab(resultContainer, groupedResults.get(tabName), activity, dialog);
                                } else if (containsLastUrl) {
                                    showResultsForTab(resultContainer, groupedResults.get(tabName), activity, dialog);
                                }
                            }
                        });
                    }).start();
                });

                safeShowDialog(activity, dialog);

                // 设置对话框大小
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                lp.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * config.getLpWidth());
                lp.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * config.getLpHeight());
                lp.alpha = config.getLpAlpha();
                dialog.getWindow().setAttributes(lp);

                RemoteInputBus.SearchCallback searchCb = keyword -> activity.runOnUiThread(() -> {
                    if (dialog != null && dialog.isShowing() && !activity.isFinishing()) {
                        searchInput.setText(keyword);
                        searchBtn.performClick();
                        Leodanmu.log("✅ 通过 RemoteInputBus 收到远程输入关键词: " + keyword);
                    }
                });
                RemoteInputBus.onSearchInput(searchCb);

                dialog.setOnDismissListener(dialogInterface -> {
                    RemoteInputBus.removeSearchInput();
                    unregisterActivity(activity);
                });

                String keywordToSearch = SharedPreferencesService.getSearchKeywordCache(activity, initialKeyword);
                if (!TextUtils.isEmpty(keywordToSearch)) {
                    searchBtn.performClick();
                }
            } catch (Exception e) {
                Leodanmu.log("显示搜索对话框异常: " + e.getMessage());
                unregisterActivity(activity);
            }
        });
    }

    private static String formatOffsetMs(int ms) {
        if (ms == 0) return "0ms";
        float sec = ms / 1000f;
        return (ms > 0 ? "+" : "") + String.format("%.1fs", sec);
    }

    private static boolean matchesCurrentDanmaku(DanmakuItem item) {
        if (item == null) return false;
        return !TextUtils.isEmpty(DanmakuManager.lastDanmakuUrl) && item.getDanmakuUrl() != null
                && DanmakuManager.lastDanmakuUrl.equals(item.getDanmakuUrl());
    }

    private static boolean containsMatchingItem(List<DanmakuItem> items) {
        if (items == null) return false;
        for (DanmakuItem item : items) {
            if (matchesCurrentDanmaku(item)) return true;
        }
        return false;
    }

    private static int dpToPx(Context context, int dp) {
        if (context == null) {
            return Math.round(dp * 3.0f);
        }
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // ========== 展示分组结果 ==========
    private static void showResultsForTab(LinearLayout resultContainer, List<DanmakuItem> items,
                                          Activity activity, AlertDialog dialog) {
        ThemeColors colors = getThemeColors(activity);
        resultContainer.removeAllViews();
        currentItems = items;

        if (items == null || items.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("该来源下无结果");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 20, 0, 20);
            empty.setTextColor(colors.textSecondary);
            resultContainer.addView(empty);
            return;
        }

        Map<String, List<DanmakuItem>> animeGroups = new HashMap<>();
        for (DanmakuItem item : items) {
            String animeTitle = item.animeTitle != null ? item.animeTitle : item.title;
            if (!animeGroups.containsKey(animeTitle)) {
                animeGroups.put(animeTitle, new ArrayList<>());
            }
            animeGroups.get(animeTitle).add(item);
        }

        Set<String> groupsWithLastUrl = new HashSet<>();
        if (DanmakuManager.lastDanmakuUrl != null || DanmakuManager.lastDanmakuId > 0) {
            for (Map.Entry<String, List<DanmakuItem>> entry : animeGroups.entrySet()) {
                if (containsMatchingItem(entry.getValue())) {
                    groupsWithLastUrl.add(entry.getKey());
                }
            }
        }

        final Map<String, Button> groupButtons = new HashMap<>();
        List<String> animeTitles = new ArrayList<>(animeGroups.keySet());
        Collections.sort(animeTitles);

        for (String animeTitle : animeTitles) {
            List<DanmakuItem> animeItems = animeGroups.get(animeTitle);
            Button groupBtn = createStaticBorderButton(activity, animeTitle + " (" + animeItems.size() + "集)");
            groupBtn.setPadding(dpToPx(activity, 16), dpToPx(activity, 10),
                    dpToPx(activity, 16), dpToPx(activity, 10));
            groupBtn.setTextSize(14);
            groupBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            groupButtons.put(animeTitle, groupBtn);

            Object[] stateInfo = new Object[]{0, 0, null};
            groupBtn.setTag(stateInfo);

            groupBtn.setOnClickListener(v -> {
                for (Map.Entry<String, Button> entry : groupButtons.entrySet()) {
                    Button otherBtn = entry.getValue();
                    if (otherBtn != v) {
                        Object[] otherState = (Object[]) otherBtn.getTag();
                        if ((Integer) otherState[0] == 1) {
                            int otherIndex = resultContainer.indexOfChild(otherBtn);
                            if (otherIndex + 1 < resultContainer.getChildCount()) {
                                resultContainer.removeViewAt(otherIndex + 1);
                            }
                            otherState[0] = 0;
                            otherState[1] = 0;
                            otherState[2] = null;
                            otherBtn.setText(entry.getKey() + " (" + animeGroups.get(entry.getKey()).size() + "集)");
                            otherBtn.setTag(otherState);
                        }
                    }
                }

                Object[] currentState = (Object[]) v.getTag();
                boolean isExpanded = (Integer) currentState[0] == 1;
                GridLayout gridContainer = (GridLayout) currentState[2];

                if (isExpanded) {
                    int groupIndex = resultContainer.indexOfChild(v);
                    if (groupIndex + 1 < resultContainer.getChildCount()) {
                        resultContainer.removeViewAt(groupIndex + 1);
                    }
                    currentState[0] = 0;
                    currentState[1] = 0;
                    currentState[2] = null;
                    groupBtn.setText(animeTitle + " (" + animeItems.size() + "集)");
                    forceLayout(resultContainer);
                } else {
                    int groupIndex = resultContainer.indexOfChild(v);
                    sortResults(animeItems, isReversed);

                    GridLayout gridLayout = new GridLayout(activity);
                    DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                    int screenWidthPx = dm.widthPixels;
                    int screenWidthDp = (int) (screenWidthPx / dm.density);
                    int columns = Math.max(3, screenWidthDp / 120);
                    gridLayout.setColumnCount(columns);
                    gridLayout.setRowCount(GridLayout.UNDEFINED);
                    gridLayout.setUseDefaultMargins(false);
                    gridLayout.setPadding(dpToPx(activity, 12), dpToPx(activity, 8),
                            dpToPx(activity, 12), dpToPx(activity, 8));
                    gridLayout.setBackgroundColor(Color.TRANSPARENT);
                    gridLayout.setClipChildren(false);
                    gridLayout.setClipToPadding(false);

                    for (DanmakuItem item : animeItems) {
                        Button gridItem = createGridResultButton(activity, item, dialog);
                        gridLayout.addView(gridItem);
                    }

                    resultContainer.addView(gridLayout, groupIndex + 1);
                    forceLayout(resultContainer);

                    currentState[0] = 1;
                    currentState[1] = animeItems.size();
                    currentState[2] = gridLayout;
                    groupBtn.setText(animeTitle + " (" + animeItems.size() + "集) [-]");
                }
                v.setTag(currentState);

                View parent = resultContainer;
                while (parent != null && !(parent instanceof ScrollView)) {
                    parent = (View) parent.getParent();
                }
                if (parent instanceof ScrollView) {
                    ScrollView scrollView = (ScrollView) parent;
                    scrollView.post(() -> {
                        int scrollY = resultContainer.getTop() + v.getTop();
                        scrollView.smoothScrollTo(0, scrollY);
                    });
                }
            });

            resultContainer.addView(groupBtn);

            if (groupsWithLastUrl.contains(animeTitle)) {
                groupBtn.post(() -> {
                    groupBtn.performClick();
                    resultContainer.postDelayed(() -> {
                        Object[] state = (Object[]) groupBtn.getTag();
                        GridLayout grid = (GridLayout) state[2];
                        if (grid != null) {
                            for (int i = 0; i < grid.getChildCount(); i++) {
                                View child = grid.getChildAt(i);
                                if (child instanceof Button && child.getTag() instanceof DanmakuItem) {
                                    DanmakuItem it = (DanmakuItem) child.getTag();
                                    if (matchesCurrentDanmaku(it)) {
                                        child.requestFocus();
                                        break;
                                    }
                                }
                            }
                        }
                    }, 100);
                });
            }
        }

        resultContainer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    // 强制布局刷新辅助方法
    private static void forceLayout(ViewGroup container) {
        container.requestLayout();
        container.invalidate();
        if (container.getParent() instanceof View) {
            ((View) container.getParent()).requestLayout();
            ((View) container.getParent()).invalidate();
        }
        container.post(() -> {
            container.requestLayout();
            container.invalidate();
            if (container.getParent() instanceof View) {
                ((View) container.getParent()).requestLayout();
                ((View) container.getParent()).invalidate();
            }
        });
        container.postDelayed(() -> {
            container.requestLayout();
            container.invalidate();
            if (container.getParent() instanceof View) {
                ((View) container.getParent()).requestLayout();
                ((View) container.getParent()).invalidate();
            }
        }, 50);
    }

    private static void sortResults(List<DanmakuItem> results, boolean reversed) {
        Collections.sort(results, (item1, item2) -> {
            if (item1.epId == null || item2.epId == null) return 0;
            int cmp = item1.epId.compareTo(item2.epId);
            return reversed ? -cmp : cmp;
        });
    }

    // ========== 远程输入二维码（独立对话框，自适应宽度） ==========
    public static void showRemoteInputQRCode(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            try {
                ThemeColors colors = getThemeColors(activity);
                DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                String localIp = NetworkUtils.getLocalIpAddress();
                String remoteInputUrl = "http://" + localIp + ":9888/input";

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setGravity(Gravity.CENTER);
                mainLayout.setBackgroundColor(Color.TRANSPARENT);
                mainLayout.setPadding(dpToPx(activity, 10), dpToPx(activity, 20),
                        dpToPx(activity, 10), dpToPx(activity, 20));

                if (config.getTheme() == 1) { // 浅色主题：浅色背景
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(colors.bgPrimary);
                    bg.setCornerRadius(dpToPx(activity, 16));
                    mainLayout.setBackground(bg);
                } // 深色主题保持透明

                TextView titleView = new TextView(activity);
                titleView.setText("📱 Leo远程输入");
                titleView.setTextSize(18);
                titleView.setTextColor(colors.textPrimary);
                titleView.setGravity(Gravity.CENTER);
                titleView.setTypeface(null, android.graphics.Typeface.BOLD);
                titleView.setPadding(0, 0, 0, dpToPx(activity, 10));
                mainLayout.addView(titleView);

                TextView instructionView = new TextView(activity);
                instructionView.setText("使用手机扫描下方二维码\n在手机上输入搜索关键词");
                instructionView.setTextSize(14);
                instructionView.setTextColor(colors.textSecondary);
                instructionView.setGravity(Gravity.CENTER);
                instructionView.setPadding(0, 0, 0, dpToPx(activity, 10));
                mainLayout.addView(instructionView);

                ImageView qrCodeView = new ImageView(activity);
                qrCodeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                        dpToPx(activity, 180), dpToPx(activity, 180));
                qrParams.gravity = Gravity.CENTER;
                qrParams.setMargins(0, 0, 0, dpToPx(activity, 10));
                qrCodeView.setLayoutParams(qrParams);
                mainLayout.addView(qrCodeView);

                TextView urlView = new TextView(activity);
                urlView.setText(remoteInputUrl);
                urlView.setTextSize(12);
                urlView.setTextColor(colors.textTertiary);
                urlView.setGravity(Gravity.CENTER);
                urlView.setPadding(dpToPx(activity, 10), dpToPx(activity, 8),
                        dpToPx(activity, 10), dpToPx(activity, 8));
                urlView.setBackgroundColor(Color.TRANSPARENT);
                urlView.setSingleLine(false);
                urlView.setMaxLines(2);
                mainLayout.addView(urlView);

                TextView hintView = new TextView(activity);
                hintView.setText("点击对话框外部关闭");
                hintView.setTextSize(12);
                hintView.setTextColor(colors.textTertiary);
                hintView.setGravity(Gravity.CENTER);
                hintView.setPadding(0, dpToPx(activity, 10), 0, 0);
                mainLayout.addView(hintView);

                builder.setView(mainLayout);
                final AlertDialog dialog = builder.create();
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(true);

                new Thread(() -> {
                    try {
                        String encodedUrl = URLEncoder.encode(remoteInputUrl, "UTF-8");
                        String qrCodeUrl = "https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=" + encodedUrl;
                        try (Response response = OkHttp.newCall(qrCodeUrl, "qrcode_remote_input")) {
                            if (response.body() != null) {
                                InputStream in = response.body().byteStream();
                                Bitmap bitmap = BitmapFactory.decodeStream(in);
                                activity.runOnUiThread(() -> {
                                    if (dialog.isShowing()) {
                                        qrCodeView.setImageBitmap(bitmap);
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        Leodanmu.log("生成远程输入二维码失败: " + e.getMessage());
                    }
                }).start();

                safeShowDialog(activity, dialog);

                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setAttributes(lp);

            } catch (Exception e) {
                Leodanmu.log("显示远程输入二维码失败: " + e.getMessage());
            }
        });
    }

    public static void showQRCodeDialog(Activity activity, String url) {
        showQRCodeDialog(activity, url, "Leo远程搜索");
    }

    public static void showQRCodeDialog(Activity activity, String url, String title) {
        activity.runOnUiThread(() -> {
            try {
                ThemeColors colors = getThemeColors(activity);
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                LinearLayout mainLayout = new LinearLayout(activity);
                mainLayout.setOrientation(LinearLayout.VERTICAL);
                mainLayout.setGravity(Gravity.CENTER);
                mainLayout.setBackgroundColor(Color.TRANSPARENT);
                mainLayout.setPadding(dpToPx(activity, 20), dpToPx(activity, 20),
                        dpToPx(activity, 20), dpToPx(activity, 20));

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(colors.bgPrimary);
                bg.setCornerRadius(dpToPx(activity, 16));
                mainLayout.setBackground(bg);

                TextView titleView = new TextView(activity);
                titleView.setText(title);
                titleView.setTextSize(18);
                titleView.setTextColor(colors.textPrimary);
                titleView.setGravity(Gravity.CENTER);
                titleView.setTypeface(null, android.graphics.Typeface.BOLD);
                titleView.setPadding(0, 0, 0, dpToPx(activity, 10));
                mainLayout.addView(titleView);

                ImageView qrCodeView = new ImageView(activity);
                qrCodeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                        dpToPx(activity, 180), dpToPx(activity, 180));
                qrParams.gravity = Gravity.CENTER;
                qrParams.setMargins(0, 0, 0, dpToPx(activity, 10));
                qrCodeView.setLayoutParams(qrParams);
                mainLayout.addView(qrCodeView);

                TextView urlView = new TextView(activity);
                urlView.setText(url);
                urlView.setTextSize(12);
                urlView.setTextColor(colors.textTertiary);
                urlView.setGravity(Gravity.CENTER);
                urlView.setPadding(dpToPx(activity, 10), dpToPx(activity, 8),
                        dpToPx(activity, 10), dpToPx(activity, 8));
                urlView.setBackgroundColor(Color.TRANSPARENT);
                urlView.setSingleLine(false);
                urlView.setMaxLines(2);
                mainLayout.addView(urlView);

                TextView hintView = new TextView(activity);
                hintView.setText("点击对话框外部关闭");
                hintView.setTextSize(12);
                hintView.setTextColor(colors.textTertiary);
                hintView.setGravity(Gravity.CENTER);
                hintView.setPadding(0, dpToPx(activity, 10), 0, 0);
                mainLayout.addView(hintView);

                builder.setView(mainLayout);
                AlertDialog dialog = builder.create();
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(true);

                new Thread(() -> {
                    try {
                        String qrCodeUrl = "https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=" + URLEncoder.encode(url, "UTF-8");
                        try (Response response = OkHttp.newCall(qrCodeUrl, "qrcode")) {
                            if (response.body() != null) {
                                InputStream in = response.body().byteStream();
                                Bitmap bitmap = BitmapFactory.decodeStream(in);
                                activity.runOnUiThread(() -> qrCodeView.setImageBitmap(bitmap));
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                safeShowDialog(activity, dialog);

                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                lp.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.75);
                lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setAttributes(lp);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void showFloatingQRCodeDialog(Activity activity, String url, String title) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            try {
                ThemeColors colors = getThemeColors(activity);
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);

                LinearLayout outer = new LinearLayout(activity);
                outer.setOrientation(LinearLayout.VERTICAL);
                outer.setGravity(Gravity.CENTER);
                outer.setBackgroundColor(Color.TRANSPARENT);
                outer.setPadding(dpToPx(activity, 2), dpToPx(activity, 2), dpToPx(activity, 2), dpToPx(activity, 2));

                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                card.setPadding(0, 0, 0, 0);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.TRANSPARENT);
                bg.setCornerRadius(dpToPx(activity, 4));
                card.setBackground(bg);

                ImageView qrCodeView = new ImageView(activity);
                qrCodeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                        dpToPx(activity, 164), dpToPx(activity, 164));
                qrParams.gravity = Gravity.CENTER;
                qrCodeView.setLayoutParams(qrParams);
                card.addView(qrCodeView);

                outer.addView(card);
                builder.setView(outer);

                final AlertDialog dialog = builder.create();
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(true);

                outer.setOnClickListener(v -> dialog.dismiss());
                card.setOnClickListener(v -> { });
                dialog.setOnKeyListener((d, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        dialog.dismiss();
                        return true;
                    }
                    return false;
                });

                new Thread(() -> {
                    try {
                        String qrCodeUrl = "https://api.qrserver.com/v1/create-qr-code/?size=160x160&data=" + URLEncoder.encode(url, "UTF-8");
                        try (Response response = OkHttp.newCall(qrCodeUrl, "qrcode_floating")) {
                            if (response.body() != null) {
                                InputStream in = response.body().byteStream();
                                Bitmap bitmap = BitmapFactory.decodeStream(in);
                                activity.runOnUiThread(() -> {
                                    if (dialog.isShowing()) {
                                        qrCodeView.setImageBitmap(bitmap);
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        Leodanmu.log("生成悬浮二维码失败: " + e.getMessage());
                    }
                }).start();

                safeShowDialog(activity, dialog);

                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                lp.width = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
                lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.CENTER;
                lp.dimAmount = 0.15f;
                dialog.getWindow().setAttributes(lp);

            } catch (Exception e) {
                Leodanmu.log("显示悬浮二维码失败: " + e.getMessage());
            }
        });
    }

    // 兼容旧方法
    private static Button createDarkSolidButton(Activity activity, String text, int backgroundColor) {
        return createBorderButton(activity, text);
    }

    private static Button createDarkBorderButton(Activity activity, String text, int borderColor) {
        return createBorderButton(activity, text);
    }

    private static android.graphics.drawable.Drawable createRoundedTransparentDrawable(int color) {
        return new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
    }

    private static int darkenColor(int color, float factor) {
        return color;
    }

    private static void safeShowDialog(Activity activity, AlertDialog dialog) {
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed() && !dialog.isShowing()) {
            try {
                dialog.show();
            } catch (Exception e) {
                Leodanmu.log("显示对话框失败: " + e.getMessage());
            }
        }
    }

    // ConfigCenter 使用的网盘代理配置对话框
    public static void showSourceProxyDialog(Context ctx, DanmakuConfig config, String sourceKey, String displayName) {
        if (!(ctx instanceof Activity)) return;
        Activity activity = (Activity) ctx;
        if (activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            try {
                DanmakuConfig.SourceProxyConfig spc = config.getProxySourceConfig().get(sourceKey);
                int[] curThread = {spc != null ? spc.thread : 8};
                int[] curChunk = {spc != null ? spc.chunkSize : 256};

                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(dpToPx(activity, 24), dpToPx(activity, 16),
                        dpToPx(activity, 24), dpToPx(activity, 16));

                // 线程行
                LinearLayout threadRow = new LinearLayout(activity);
                threadRow.setOrientation(LinearLayout.HORIZONTAL);
                threadRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView threadLabel = new TextView(activity);
                threadLabel.setText("线程数：");
                threadLabel.setTextSize(16);
                threadLabel.setTextColor(Color.BLACK);
                threadRow.addView(threadLabel);

                EditText threadInput = new EditText(activity);
                threadInput.setText(String.valueOf(curThread[0]));
                threadInput.setTextSize(16);
                threadInput.setGravity(Gravity.CENTER);
                threadInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                LinearLayout.LayoutParams tip = new LinearLayout.LayoutParams(
                        dpToPx(activity, 80), ViewGroup.LayoutParams.WRAP_CONTENT);
                threadInput.setLayoutParams(tip);
                threadInput.setHint("8");
                threadRow.addView(threadInput);
                layout.addView(threadRow);

                // 分块行
                LinearLayout chunkRow = new LinearLayout(activity);
                chunkRow.setOrientation(LinearLayout.HORIZONTAL);
                chunkRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams chunkRowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                chunkRowParams.topMargin = dpToPx(activity, 12);
                chunkRow.setLayoutParams(chunkRowParams);

                TextView chunkLabel = new TextView(activity);
                chunkLabel.setText("分块大小(KB)：");
                chunkLabel.setTextSize(16);
                chunkLabel.setTextColor(Color.BLACK);
                chunkRow.addView(chunkLabel);

                EditText chunkInput = new EditText(activity);
                chunkInput.setText(String.valueOf(curChunk[0]));
                chunkInput.setTextSize(16);
                chunkInput.setGravity(Gravity.CENTER);
                chunkInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                LinearLayout.LayoutParams cip = new LinearLayout.LayoutParams(
                        dpToPx(activity, 80), ViewGroup.LayoutParams.WRAP_CONTENT);
                chunkInput.setLayoutParams(cip);
                chunkInput.setHint("256");
                chunkRow.addView(chunkInput);
                layout.addView(chunkRow);

                new AlertDialog.Builder(activity)
                        .setTitle(displayName + " 代理配置")
                        .setView(layout)
                        .setPositiveButton("保存", (dialog, which) -> {
                            try {
                                int t = Integer.parseInt(threadInput.getText().toString().trim());
                                int c = Integer.parseInt(chunkInput.getText().toString().trim());
                                Map<String, DanmakuConfig.SourceProxyConfig> map = config.getProxySourceConfig();
                                map.put(sourceKey, new DanmakuConfig.SourceProxyConfig(t, c));
                                config.setProxySourceConfig(map);
                                DanmakuConfigManager.saveConfig(activity, config);
                                Utils.safeShowToast(activity, displayName + "配置已保存");
                            } catch (NumberFormatException e) {
                                Utils.safeShowToast(activity, "请输入有效数字");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } catch (Exception e) {
                Leodanmu.log("显示网盘代理对话框异常: " + e.getMessage());
            }
        });
    }
}
