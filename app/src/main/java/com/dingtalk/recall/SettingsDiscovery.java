package com.dingtalk.recall;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SettingsDiscovery {

    private static final String TAG = "[AntiRecall] ";
    private static final String BUILD_MARKER = "1.6-probe";
    private static final String[] FRAGMENT_CLASSES = {
            "androidx.fragment.app.Fragment",
            "android.app.Fragment",
    };
    private static final String[] TITLE_HINTS = {"通用", "设置", "消息通知", "隐私", "通知"};
    private static final Set<String> logged = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> dumped = Collections.synchronizedSet(new HashSet<String>());
    private static final int MAX_LINES = 250;
    private static final int MAX_DEPTH = 30;

    private SettingsDiscovery() {
    }

    static void install(final ClassLoader classLoader) {
        XposedBridge.log(TAG + "SettingsDiscovery ACTIVE (" + BUILD_MARKER + ")");

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        XposedBridge.log(TAG + "ACT " + activity.getClass().getName());
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "hooked Activity#onCreate");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook Activity.onCreate failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        String name = activity.getClass().getName();
                        String title = titleOf(activity);
                        String key = name + " | " + title;
                        if (logged.add(key)) {
                            XposedBridge.log(TAG + "PAGE " + name + " | " + title);
                        }
                        if (dumped.add(key)) {
                            inspect(activity, title);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "hooked Activity#onResume");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook Activity.onResume failed: " + t);
        }

        for (String className : FRAGMENT_CLASSES) {
            Class<?> fragmentClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (fragmentClass == null) {
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(fragmentClass, "onCreate", Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            XposedBridge.log(TAG + "FRAG " + param.thisObject.getClass().getName());
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.log(TAG + "hooked " + className + "#onCreate");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "hook " + className + " failed: " + t);
            }
        }
    }

    private static void inspect(Activity activity, String title) {
        View root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        boolean[] interesting = new boolean[1];
        collect(root, 0, lines, interesting);
        String name = activity.getClass().getName();
        if (interesting[0] || looksLikeSettings(name) || titleMatches(title)) {
            XposedBridge.log(TAG + "VIEWTREE " + name + " | " + title + " (" + lines.size() + " nodes)");
            int limit = Math.min(lines.size(), MAX_LINES);
            for (int i = 0; i < limit; i++) {
                XposedBridge.log(TAG + "  " + lines.get(i));
            }
        }
    }

    private static boolean titleMatches(String title) {
        if (title == null) {
            return false;
        }
        for (String hint : TITLE_HINTS) {
            if (title.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeSettings(String name) {
        String lower = name.toLowerCase();
        return lower.contains("setting") || lower.contains("general") || lower.contains("preference");
    }

    private static void collect(View view, int depth, List<String> lines, boolean[] interesting) {
        if (view == null || depth > MAX_DEPTH || lines.size() > MAX_LINES) {
            return;
        }
        String simple = view.getClass().getSimpleName();
        String id = idName(view);
        String text = textOf(view);
        if (isInteresting(simple, text)) {
            interesting[0] = true;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        builder.append(simple);
        if (!id.isEmpty()) {
            builder.append(" #").append(id);
        }
        if (!text.isEmpty()) {
            builder.append(' ').append(text);
        }
        lines.add(builder.toString());

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), depth + 1, lines, interesting);
            }
        }
    }

    private static boolean isInteresting(String simple, String text) {
        String lowerSimple = simple.toLowerCase();
        if (lowerSimple.contains("switch") || lowerSimple.contains("checkbox")) {
            return true;
        }
        return titleMatches(text);
    }

    private static String idName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return "";
        }
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String textOf(View view) {
        if (!(view instanceof TextView)) {
            return "";
        }
        CharSequence value = ((TextView) view).getText();
        if (value == null || value.length() == 0) {
            return "";
        }
        return "\"" + value + "\"";
    }

    private static String titleOf(Activity activity) {
        try {
            CharSequence title = activity.getTitle();
            return title == null ? "" : title.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
