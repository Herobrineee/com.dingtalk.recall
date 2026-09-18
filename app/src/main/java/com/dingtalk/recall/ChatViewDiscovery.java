package com.dingtalk.recall;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class ChatViewDiscovery {

    private static final String TAG = "[AntiRecall] ";
    private static final String BUILD_MARKER = "1.7-chatprobe";
    private static final String[] CHAT_HINTS = {
            "chat", "conversation", "message", "session", "im.",
    };
    private static final Set<String> dumped = Collections.synchronizedSet(new HashSet<String>());
    private static final int MAX_LINES = 400;
    private static final int MAX_DEPTH = 40;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Activity currentActivity;

    private ChatViewDiscovery() {
    }

    static void install(final ClassLoader classLoader) {
        XposedBridge.log(TAG + "ChatViewDiscovery ACTIVE (" + BUILD_MARKER + ")");

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Activity activity = (Activity) param.thisObject;
                        currentActivity = activity;
                        String name = activity.getClass().getName();
                        if (looksLikeChat(name) || looksLikeChat(String.valueOf(activity.getTitle()))) {
                            final String key = "resume:" + name;
                            if (dumped.add(key)) {
                                XposedBridge.log(TAG + "CHAT-PAGE " + name);
                                MAIN.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        dump(activity, "resume");
                                    }
                                }, 1500L);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "hooked Activity#onResume (chat probe)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook Activity.onResume failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (currentActivity == param.thisObject) {
                            currentActivity = null;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook Activity.onPause failed: " + t);
        }
    }

    static void onRecallDetected(long mid) {
        final Activity activity = currentActivity;
        XposedBridge.log(TAG + "CHAT-RECALL mid=" + mid + " activity="
                + (activity == null ? "null" : activity.getClass().getName()));
        if (activity == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                dump(activity, "recall-now mid=" + mid);
            }
        });
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                dump(activity, "recall-later mid=" + mid);
            }
        }, 900L);
    }

    private static void dump(Activity activity, String reason) {
        View root;
        try {
            root = activity.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (root == null) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        List<String> texts = new ArrayList<String>();
        collect(root, 0, lines, texts);
        XposedBridge.log(TAG + "CHAT-VIEWTREE " + activity.getClass().getName()
                + " [" + reason + "] (" + lines.size() + " nodes)");
        int limit = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < limit; i++) {
            XposedBridge.log(TAG + "  " + lines.get(i));
        }
        XposedBridge.log(TAG + "CHAT-TEXTS [" + reason + "] (" + texts.size() + ")");
        int textLimit = Math.min(texts.size(), 120);
        for (int i = 0; i < textLimit; i++) {
            XposedBridge.log(TAG + "  " + texts.get(i));
        }
    }

    private static void collect(View view, int depth, List<String> lines, List<String> texts) {
        if (view == null || depth > MAX_DEPTH || lines.size() > MAX_LINES) {
            return;
        }
        String simple = view.getClass().getSimpleName();
        String id = idName(view);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        builder.append(simple);
        if (!id.isEmpty()) {
            builder.append(" #").append(id);
        }
        if (view instanceof ViewGroup) {
            builder.append(" [").append(((ViewGroup) view).getChildCount()).append("]");
        }
        String adapter = adapterName(view);
        if (!adapter.isEmpty()) {
            builder.append(" adapter=").append(adapter);
        }
        String text = textOf(view);
        if (!text.isEmpty()) {
            builder.append(' ').append(text);
        }
        lines.add(builder.toString());

        if (view instanceof TextView && !text.isEmpty()) {
            texts.add(simple + (id.isEmpty() ? "" : " #" + id) + " " + text
                    + " parent=" + parentName(view));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                collect(group.getChildAt(i), depth + 1, lines, texts);
            }
        }
    }

    private static String adapterName(View view) {
        String name = view.getClass().getName();
        if (!name.contains("RecyclerView") && !name.contains("ListView")) {
            return "";
        }
        try {
            Object adapter = XposedHelpers.callMethod(view, "getAdapter");
            return adapter == null ? "" : adapter.getClass().getName();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String parentName(View view) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof View)) {
            return "?";
        }
        View parentView = (View) parent;
        String id = idName(parentView);
        return parentView.getClass().getSimpleName() + (id.isEmpty() ? "" : " #" + id);
    }

    private static boolean looksLikeChat(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        for (String hint : CHAT_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
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
}
