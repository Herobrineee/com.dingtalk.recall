package com.dingtalk.recall;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class ChatHintInjector {

    private static final String TAG = "[AntiRecall] ";
    private static final String BUILD_MARKER = "1.8-hint";
    private static final String RECALL_LABEL = "已撤回";

    private static final String LIST_ID = "list_view";
    private static final String CONTENT_STUB_ID = "chatting_content_view_stub";
    private static final String CONTENT_CONTAINER_ID = "chatting_content_view_container";

    private static final Set<Method> HOOKED_METHODS = Collections.synchronizedSet(new HashSet<Method>());
    private static final Set<Class<?>> HOOKED_ADAPTERS = Collections.synchronizedSet(new HashSet<Class<?>>());
    private static final Set<String> LOGGED_ITEM_CLASSES = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<Long> LOGGED_RECALLED = Collections.synchronizedSet(new HashSet<Long>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int HINT_ID = View.generateViewId();

    private static volatile Activity currentChatActivity;

    private ChatHintInjector() {
    }

    static void install(final ClassLoader classLoader) {
        XposedBridge.log(TAG + "ChatHintInjector ACTIVE (" + BUILD_MARKER + ")");
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (!looksLikeChat(activity)) {
                            return;
                        }
                        currentChatActivity = activity;
                        XposedBridge.log(TAG + "HINT chat page " + activity.getClass().getName());
                        attach(activity);
                        MAIN.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                refresh(activity);
                            }
                        }, 800L);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (currentChatActivity == param.thisObject) {
                        currentChatActivity = null;
                    }
                }
            });
            XposedBridge.log(TAG + "hooked Activity#onResume (hint injector)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ChatHintInjector hook failed: " + t);
        }
    }

    static void onRecallDetected(final long mid) {
        XposedBridge.log(TAG + "HINT recall mid=" + mid);
        final Activity activity = currentChatActivity;
        if (activity == null) {
            return;
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh(activity);
            }
        }, 200L);
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh(activity);
            }
        }, 1200L);
    }

    private static void attach(Activity activity) {
        View listView = findListView(activity);
        if (listView == null) {
            XposedBridge.log(TAG + "HINT list view not found");
            return;
        }
        Object adapter;
        try {
            adapter = XposedHelpers.callMethod(listView, "getAdapter");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "HINT getAdapter failed: " + t);
            return;
        }
        Object inner = unwrapAdapter(adapter);
        if (inner == null) {
            XposedBridge.log(TAG + "HINT inner adapter not found");
            return;
        }
        hookAdapter(inner);
        refresh(listView, adapter);
    }

    private static void hookAdapter(final Object inner) {
        Class<?> adapterClass = inner.getClass();
        if (!HOOKED_ADAPTERS.add(adapterClass)) {
            return;
        }
        Method getView;
        try {
            getView = adapterClass.getMethod("getView", int.class, View.class, ViewGroup.class);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "HINT getView not found on " + adapterClass.getName());
            return;
        }
        if (!HOOKED_METHODS.add(getView)) {
            return;
        }
        try {
            XposedBridge.hookMethod(getView, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!Config.enabled() || !Config.marker()) {
                            return;
                        }
                        int position = ((Integer) param.args[0]).intValue();
                        Object item = XposedHelpers.callMethod(param.thisObject, "getItem", position);
                        Object result = param.getResult();
                        if (item == null || !(result instanceof View)) {
                            return;
                        }
                        applyToItemView((View) result, item);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "HINT hooked " + adapterClass.getName() + "#getView");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "HINT hook getView failed: " + t);
        }
    }

    private static void refresh(Activity activity) {
        View listView = findListView(activity);
        if (listView == null) {
            return;
        }
        Object adapter;
        try {
            adapter = XposedHelpers.callMethod(listView, "getAdapter");
        } catch (Throwable t) {
            return;
        }
        refresh(listView, adapter);
    }

    private static void refresh(View listView, Object adapter) {
        if (!(listView instanceof ListView) || adapter == null) {
            return;
        }
        ViewGroup group = (ViewGroup) listView;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            int position;
            try {
                position = ((ListView) listView).getPositionForView(child);
            } catch (Throwable t) {
                continue;
            }
            if (position < 0) {
                continue;
            }
            Object item;
            try {
                item = XposedHelpers.callMethod(adapter, "getItem", position);
            } catch (Throwable t) {
                continue;
            }
            if (item == null) {
                continue;
            }
            applyToItemView(child, item);
        }
    }

    private static void applyToItemView(View itemView, Object item) {
        if (!Config.enabled() || !Config.marker()) {
            return;
        }
        String itemClass = item.getClass().getName();
        if (LOGGED_ITEM_CLASSES.add(itemClass)) {
            XposedBridge.log(TAG + "HINT item class " + itemClass);
        }
        boolean recalled = XposedModule.isRecalledMessage(item);
        boolean text = XposedModule.isTextMessage(item);
        if (recalled) {
            long mid = XposedModule.messageMid(item);
            if (LOGGED_RECALLED.add(mid)) {
                XposedBridge.log(TAG + "HINT recalled item mid=" + mid
                        + " text=" + text + " class=" + itemClass);
            }
        }
        if (recalled && !text) {
            injectHint(itemView);
        } else {
            removeHint(itemView);
        }
    }

    private static void injectHint(View itemView) {
        if (itemView.findViewById(HINT_ID) != null) {
            return;
        }
        ViewGroup target = findGroup(itemView, CONTENT_STUB_ID);
        if (target == null) {
            target = findGroup(itemView, CONTENT_CONTAINER_ID);
        }
        if (target == null) {
            return;
        }
        Context context = itemView.getContext();
        TextView hint = new TextView(context);
        hint.setId(HINT_ID);
        hint.setText(RECALL_LABEL);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setTextColor(Color.parseColor("#999999"));
        hint.setPadding(0, dp(context, 2), 0, 0);
        try {
            if (target instanceof LinearLayout) {
                target.addView(hint, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                target.addView(hint, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            XposedBridge.log(TAG + "HINT injected into #" + idName(target));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "HINT inject failed: " + t);
        }
    }

    private static void removeHint(View itemView) {
        View existing = itemView.findViewById(HINT_ID);
        if (existing == null) {
            return;
        }
        if (existing.getParent() instanceof ViewGroup) {
            ((ViewGroup) existing.getParent()).removeView(existing);
        }
    }

    private static ViewGroup findGroup(View root, String idName) {
        int id = id(root.getContext(), idName);
        if (id == 0) {
            return null;
        }
        View view = root.findViewById(id);
        return view instanceof ViewGroup ? (ViewGroup) view : null;
    }

    private static View findListView(Activity activity) {
        int id = id(activity, LIST_ID);
        if (id != 0) {
            View view = activity.findViewById(id);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private static Object unwrapAdapter(Object adapter) {
        Object current = adapter;
        for (int i = 0; i < 4 && current != null; i++) {
            String name = current.getClass().getName();
            if (name.contains("HeaderViewListAdapter") || name.contains("WrapperListAdapter")) {
                try {
                    current = XposedHelpers.callMethod(current, "getWrappedAdapter");
                    continue;
                } catch (Throwable t) {
                    return current;
                }
            }
            return current;
        }
        return current;
    }

    private static boolean looksLikeChat(Activity activity) {
        try {
            String name = activity.getClass().getName();
            return name.contains("ChatMsgActivity") || name.contains("ChattingActivity");
        } catch (Throwable t) {
            return false;
        }
    }

    private static int id(Context context, String name) {
        try {
            Resources resources = context.getResources();
            return resources.getIdentifier(name, "id", context.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String idName(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(view.getId());
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
