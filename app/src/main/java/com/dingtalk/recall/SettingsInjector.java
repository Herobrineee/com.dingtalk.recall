package com.dingtalk.recall;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SettingsInjector {

    private static final String TAG = "[AntiRecall] ";
    private static final String TARGET = "com.alibaba.android.user.settings.activity.CommonSettingActivity";
    private static final String HOST_PACKAGE = "com.alibaba.android.rimet";
    private static final String ROW_TAG = "anti_recall_row";
    private static final String MASTER_TITLE = "防止消息撤回";
    private static final String MARKER_TITLE = "显示「已撤回」标记";
    private static final String[] LIST_NAMES = {"ListView", "RecyclerView", "DtGroupList"};
    private static final Set<Activity> injected = Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static volatile boolean treeDumped = false;
    private static volatile boolean skipLogged = false;

    private SettingsInjector() {
    }

    static void install(ClassLoader classLoader) {
        String process = processName();
        if (process != null && !HOST_PACKAGE.equals(process)) {
            if (!skipLogged) {
                skipLogged = true;
                XposedBridge.log(TAG + "settings injector idle in process " + process);
            }
            return;
        }

        Class<?> target = XposedHelpers.findClassIfExists(TARGET, classLoader);
        if (target == null) {
            XposedBridge.log(TAG + "settings target class not found: " + TARGET);
        } else {
            XposedBridge.log(TAG + "settings target class = " + target.getName());
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (!isTarget(activity.getClass().getName())) {
                            return;
                        }
                        schedule(activity);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "settings onResume handler failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "settings injector hooked on Activity#onResume");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "settings injector hook failed: " + t);
        }
    }

    private static String processName() {
        try {
            return Application.getProcessName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTarget(String name) {
        return TARGET.equals(name) || name.endsWith("CommonSettingActivity");
    }

    private static void schedule(final Activity activity) {
        synchronized (injected) {
            if (injected.contains(activity)) {
                return;
            }
            injected.add(activity);
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            doInject(activity);
            return;
        }
        decor.post(new Runnable() {
            @Override
            public void run() {
                try {
                    doInject(activity);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "doInject failed: " + t);
                }
            }
        });
    }

    private static void doInject(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            XposedBridge.log(TAG + "content root is not a ViewGroup");
            return;
        }
        ViewGroup root = (ViewGroup) content;
        if (!treeDumped) {
            treeDumped = true;
            dumpTree(root);
        }

        ViewGroup list = findListGroup(root);
        if (list == null) {
            XposedBridge.log(TAG + "no list container found");
            return;
        }
        if (list.findViewWithTag(ROW_TAG) != null) {
            XposedBridge.log(TAG + "switch rows already present");
            return;
        }

        View template = findSwitchRow(list);
        View templateSwitch = template == null ? null : findSwitch(template);
        XposedBridge.log(TAG + "list = " + list.getClass().getName() + ", template row = "
                + (template == null ? "none" : template.getClass().getName()) + ", switch = "
                + (templateSwitch == null ? "none" : templateSwitch.getClass().getName()));

        View enabledRow = buildRow(activity, template, templateSwitch, MASTER_TITLE, Config.enabled(),
                new OnToggle() {
                    @Override
                    public void onToggle(boolean checked) {
                        Config.save(checked, Config.marker());
                        XposedBridge.log(TAG + "master switch = " + checked);
                    }
                });
        View markerRow = buildRow(activity, template, templateSwitch, MARKER_TITLE, Config.marker(),
                new OnToggle() {
                    @Override
                    public void onToggle(boolean checked) {
                        Config.save(Config.enabled(), checked);
                        XposedBridge.log(TAG + "marker switch = " + checked);
                    }
                });
        if (enabledRow == null || markerRow == null) {
            XposedBridge.log(TAG + "row build failed");
            return;
        }
        enabledRow.setTag(ROW_TAG);
        markerRow.setTag(ROW_TAG);

        int index = 0;
        if (template != null && template.getParent() == list) {
            index = list.indexOfChild(template) + 1;
        }
        list.addView(enabledRow, index);
        list.addView(markerRow, index + 1);
        applyCardMargins(enabledRow);
        applyCardMargins(markerRow);
        XposedBridge.log(TAG + "switch rows injected into " + list.getClass().getName()
                + " at " + index + " (master=" + Config.enabled() + ", marker=" + Config.marker() + ")");
    }

    private interface OnToggle {
        void onToggle(boolean checked);
    }

    private static View buildRow(Activity activity, View template, View templateSwitch, String title,
                                 boolean checked, final OnToggle listener) {
        boolean night = isNight(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int height = template != null ? template.getHeight() : 0;
        row.setMinimumHeight(height > 0 ? height : dp(activity, 48));

        int paddingLeft = dp(activity, 16);
        int paddingRight = dp(activity, 16);
        View content = template == null ? null : findBySimpleId(template, "content_container");
        if (content != null) {
            if (content.getPaddingLeft() > 0) {
                paddingLeft = content.getPaddingLeft();
            }
            if (content.getPaddingRight() > 0) {
                paddingRight = content.getPaddingRight();
            }
        } else if (template != null) {
            if (template.getPaddingLeft() > 0) {
                paddingLeft = template.getPaddingLeft();
            }
            if (template.getPaddingRight() > 0) {
                paddingRight = template.getPaddingRight();
            }
        }

        Drawable nativeBackground = findRowBackground(activity, template);
        Drawable background = nativeBackground != null
                ? nativeBackground : fallbackRoundedBackground(activity, night);
        row.setBackground(background);
        row.setPadding(paddingLeft, 0, paddingRight, 0);

        final float cornerRadius = dp(activity, 12);
        row.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        row.setClipToOutline(true);

        TextView text = buildTitle(activity, template, title, night);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View toggle = buildSwitch(activity, templateSwitch);
        setChecked(toggle, checked);
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bindRow(row, toggle, listener);
        float textSp = text.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity;
        XposedBridge.log(TAG + "row built: title=" + title + ", night=" + night
                + ", textSize=" + textSp + "sp, background=" + background.getClass().getSimpleName()
                + " (rounded outline " + dp(activity, 12) + "px clip)"
                + ", nativeBackground=" + (nativeBackground == null
                        ? "none" : nativeBackground.getClass().getName())
                + ", toggle=" + toggle.getClass().getName());
        return row;
    }

    private static TextView buildTitle(Activity activity, View template, String title, boolean night) {
        TextView source = null;
        if (template != null) {
            View found = findBySimpleId(template, "text_title_main");
            if (found instanceof TextView) {
                source = (TextView) found;
            }
        }
        if (source == null && template != null) {
            source = findFirstText(template);
        }
        TextView text = null;
        if (source != null) {
            try {
                Method clone = View.class.getMethod("clone");
                clone.setAccessible(true);
                Object copy = clone.invoke(source);
                if (copy instanceof TextView) {
                    text = (TextView) copy;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "title clone failed: " + t);
            }
        }
        if (text == null) {
            text = new TextView(activity);
        }
        clearListeners(text);
        text.setText(title);
        text.setSingleLine(true);

        float sp = 15f;
        if (source != null && source.getTextSize() > 0f) {
            sp = source.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity;
        }
        if (sp > 16f) {
            sp = 16f;
        }
        if (sp < 12f) {
            sp = 12f;
        }
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(resolveColor(activity, android.R.attr.textColorPrimary,
                night ? 0xE6FFFFFF : 0xFF191919));
        return text;
    }

    private static void applyCardMargins(View row) {
        try {
            ViewGroup.LayoutParams params = row.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                int horizontal = dp(row.getContext(), 12);
                int vertical = dp(row.getContext(), 4);
                margins.leftMargin = horizontal;
                margins.rightMargin = horizontal;
                margins.topMargin = vertical;
                margins.bottomMargin = vertical;
                row.setLayoutParams(margins);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNight(Activity activity) {
        try {
            return (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int resolveColor(Activity activity, int attribute, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (activity.getTheme().resolveAttribute(attribute, value, true)) {
                if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
                if (value.resourceId != 0) {
                    try {
                        return activity.getResources().getColor(value.resourceId);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static Drawable findRowBackground(Activity activity, View template) {
        if (template == null) {
            return null;
        }
        View content = findBySimpleId(template, "content_container");
        if (content != null) {
            Drawable fromContent = copyDrawable(content.getBackground(), activity.getResources());
            if (fromContent != null) {
                return fromContent;
            }
        }
        return copyDrawable(template.getBackground(), activity.getResources());
    }

    private static Drawable copyDrawable(Drawable source, Resources resources) {
        if (source == null) {
            return null;
        }
        try {
            Drawable.ConstantState state = source.getConstantState();
            if (state == null) {
                return null;
            }
            Drawable copy = state.newDrawable(resources);
            return copy == null ? null : copy.mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Drawable fallbackRoundedBackground(Activity activity, boolean night) {
        int color = resolveColor(activity, android.R.attr.colorBackground,
                night ? 0xFF2C2C2E : 0xFFFFFFFF);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(activity, 12));
        shape.setColor(color);
        return shape;
    }

    private static View findBySimpleId(View view, String entryName) {
        try {
            int id = view.getId();
            if (id != View.NO_ID && entryName.equals(view.getResources().getResourceEntryName(id))) {
                return view;
            }
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findBySimpleId(group.getChildAt(i), entryName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static View buildSwitch(Activity activity, View templateSwitch) {
        View toggle = null;
        if (templateSwitch != null) {
            try {
                Method clone = View.class.getMethod("clone");
                clone.setAccessible(true);
                Object copy = clone.invoke(templateSwitch);
                if (copy instanceof View) {
                    toggle = (View) copy;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "switch clone failed: " + t);
            }
            if (toggle == null) {
                try {
                    toggle = (View) templateSwitch.getClass()
                            .getConstructor(Context.class).newInstance(activity);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "switch ctor failed: " + t);
                }
            }
        }
        if (toggle == null) {
            toggle = new Switch(activity);
        }
        ViewGroup.LayoutParams params = toggle.getLayoutParams();
        int width = params != null && params.width > 0
                ? params.width : ViewGroup.LayoutParams.WRAP_CONTENT;
        int height = params != null && params.height > 0
                ? params.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        toggle.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        clearListeners(toggle);
        return toggle;
    }

    private static void bindRow(View row, final View toggle, final OnToggle listener) {
        boolean bound = false;
        if (toggle instanceof CompoundButton) {
            ((CompoundButton) toggle).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    listener.onToggle(isChecked);
                }
            });
            bound = true;
        } else {
            try {
                Method setter = toggle.getClass().getMethod("setOnCheckedChangeListener",
                        CompoundButton.OnCheckedChangeListener.class);
                setter.invoke(toggle, new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        listener.onToggle(isChecked);
                    }
                });
                bound = true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "no change listener on " + toggle.getClass().getName());
            }
        }
        final boolean compound = bound;
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (compound && toggle instanceof CompoundButton) {
                    toggle.performClick();
                } else {
                    boolean next = !isChecked(toggle);
                    setChecked(toggle, next);
                    listener.onToggle(next);
                }
            }
        });
    }

    private static void setChecked(View view, boolean checked) {
        try {
            Method setter = view.getClass().getMethod("setChecked", boolean.class);
            setter.invoke(view, checked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "setChecked failed on " + view.getClass().getName() + ": " + t);
        }
    }

    private static boolean isChecked(View view) {
        try {
            Method getter = view.getClass().getMethod("isChecked");
            Object value = getter.invoke(view);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void clearListeners(View view) {
        try {
            Field field = View.class.getDeclaredField("mListenerInfo");
            field.setAccessible(true);
            field.set(view, null);
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearListeners(group.getChildAt(i));
            }
        }
    }

    private static ViewGroup findListGroup(View root) {
        if (root instanceof ViewGroup && matchesList(root)) {
            return (ViewGroup) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup found = findListGroup(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean matchesList(View view) {
        String name = view.getClass().getName();
        for (String keyword : LIST_NAMES) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static View findSwitchRow(ViewGroup list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof ViewGroup && findSwitch(child) != null) {
                return child;
            }
        }
        return null;
    }

    private static View findSwitch(View view) {
        if (isSwitch(view)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSwitch(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isSwitch(View view) {
        return view instanceof CompoundButton
                || view.getClass().getSimpleName().contains("Switch");
    }

    private static TextView findFirstText(View view) {
        if (view instanceof TextView && !isSwitch(view)) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && value.length() > 0) {
                return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findFirstText(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static void dumpTree(ViewGroup root) {
        List<String> lines = new ArrayList<String>();
        collectLines(root, 0, lines);
        XposedBridge.log(TAG + "SETTINGS TREE (" + lines.size() + " nodes)");
        int limit = Math.min(lines.size(), 200);
        for (int i = 0; i < limit; i++) {
            XposedBridge.log(TAG + "  " + lines.get(i));
        }
    }

    private static void collectLines(View view, int depth, List<String> lines) {
        if (view == null || depth > 25 || lines.size() > 200) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        builder.append(view.getClass().getSimpleName());
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                builder.append(" #").append(view.getResources().getResourceEntryName(id));
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && value.length() > 0) {
                builder.append(" \"").append(value).append('"');
            }
        }
        lines.add(builder.toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLines(group.getChildAt(i), depth + 1, lines);
            }
        }
    }
}
