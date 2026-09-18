package com.dingtalk.recall;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedModule implements IXposedHookLoadPackage {

    private static final String TAG = "[AntiRecall] ";
    private static final String DINGTALK_PACKAGE = "com.alibaba.android.rimet";

    private static final boolean TRACE_RECALL_EVENTS = true;
    private static final boolean DISCOVER_SETTINGS = false;
    private static final boolean DISCOVER_CHAT_VIEWS = false;
    private static final boolean INJECT_CHAT_HINTS = true;
    private static final boolean INJECT_SETTINGS = true;

    private static final String RECALL_MARKER = " [已撤回]";
    private static final String RECALL_LABEL = "已撤回";
    private static final String RECALL_PLACEHOLDER = "Msg has been recalled.";
    private static final int MAX_TRACKED_MIDS = 5000;
    private static final int MAX_CACHED_CONTENTS = 2000;

    private static final String[] MESSAGE_CLASS_CANDIDATES = {
            "com.alibaba.wukong.im.message.MessageImpl",
            "com.alibaba.wukong.im.message.Message",
    };

    private static final String[] RECALL_STATUS_METHODS = {
            "recallStatus", "getRecallStatus", "isRecallStatus", "getRecalledStatus",
    };

    private static final String[] CAN_RECALL_METHODS = {
            "canRecall", "isCanRecall",
    };

    private static final String[] CONTENT_GETTERS = {
            "messageContent", "getMessageContent", "content",
    };

    private static final String RECALL_STATUS_FIELD = "mRecallStatus";
    private static final String MESSAGE_ID_FIELD = "mMid";
    private static final String LOCAL_RECALL_SETTER = "updateLocalRecallStatus";
    private static final String CONV_RECALL_EVENT = "onConvThreadOrRootMsgRecalled";
    private static final String SEND_RECALL_METHOD = "recallMessage";

    private static final String PREFS_NAME = "anti_recall_state";
    private static final String PREFS_MIDS = "recalled_mids";

    private static final Set<Long> recalledMids = Collections.synchronizedSet(new HashSet<Long>());
    private static final Set<Long> markedMids = Collections.synchronizedSet(new HashSet<Long>());
    private static final Map<Long, CachedContent> originalContents = Collections.synchronizedMap(
            new LinkedHashMap<Long, CachedContent>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CachedContent> eldest) {
                    return size() > MAX_CACHED_CONTENTS;
                }
            });
    private static volatile boolean midsLoaded = false;
    private static volatile boolean discoveryInstalled = false;
    private static volatile boolean chatDiscoveryInstalled = false;
    private static volatile boolean chatHintInstalled = false;
    private static volatile boolean injectorInstalled = false;
    private static final ThreadLocal<Boolean> marking = new ThreadLocal<Boolean>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!DINGTALK_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + "loading " + lpparam.packageName + " (" + lpparam.processName + ")");
        if (DISCOVER_SETTINGS && !discoveryInstalled) {
            discoveryInstalled = true;
            try {
                SettingsDiscovery.install(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "SettingsDiscovery failed: " + t);
            }
        }
        if (DISCOVER_CHAT_VIEWS && !chatDiscoveryInstalled) {
            chatDiscoveryInstalled = true;
            try {
                ChatViewDiscovery.install(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "ChatViewDiscovery failed: " + t);
            }
        }
        if (INJECT_CHAT_HINTS && !chatHintInstalled) {
            chatHintInstalled = true;
            try {
                ChatHintInjector.install(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "ChatHintInjector failed: " + t);
            }
        }
        if (INJECT_SETTINGS && !injectorInstalled) {
            injectorInstalled = true;
            try {
                SettingsInjector.install(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "SettingsInjector failed: " + t);
            }
        }
        try {
            installHooks(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "installHooks failed: " + t);
        }
    }

    private static void installHooks(ClassLoader classLoader) {
        Class<?> messageClass = findMessageClass(classLoader);
        if (messageClass == null) {
            XposedBridge.log(TAG + "message class not found, nothing hooked");
            return;
        }
        XposedBridge.log(TAG + "message class = " + messageClass.getName());
        logRecallMembers(messageClass);

        Set<Method> hooked = new HashSet<Method>();

        for (String name : RECALL_STATUS_METHODS) {
            hookRecallStatusGetter(messageClass, name, hooked);
        }
        for (String name : CAN_RECALL_METHODS) {
            hookConstant(messageClass, name, Boolean.TRUE, hooked);
        }

        hookLocalRecallSetter(messageClass, hooked);
        hookConvRecallEvent(messageClass, hooked);
        if (TRACE_RECALL_EVENTS) {
            hookEventTracer(messageClass, SEND_RECALL_METHOD, hooked);
        }
        hookContentReplay(messageClass, hooked);

        hookByHeuristic(messageClass, hooked);
        blockRecallStatusSetters(messageClass, hooked);
    }

    private static Class<?> findMessageClass(ClassLoader classLoader) {
        Class<?> fallback = null;
        for (String name : MESSAGE_CLASS_CANDIDATES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(name, classLoader);
            if (clazz == null) {
                continue;
            }
            if (hasRecallStatusField(clazz)) {
                return clazz;
            }
            if (fallback == null) {
                fallback = clazz;
            }
        }
        return fallback;
    }

    private static boolean hasRecallStatusField(Class<?> clazz) {
        try {
            for (Field field : clazz.getDeclaredFields()) {
                if (RECALL_STATUS_FIELD.equals(field.getName())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "read fields of " + clazz.getName() + " failed: " + t);
        }
        return false;
    }

    private static void hookRecallStatusGetter(Class<?> clazz, String methodName, Set<Method> hooked) {
        for (Method method : declaredMethods(clazz)) {
            if (!method.getName().equals(methodName) || method.getParameterTypes().length != 0) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            final Object forcedResult;
            if (returnType == boolean.class || returnType == Boolean.class) {
                forcedResult = Boolean.FALSE;
            } else if (returnType == int.class || returnType == Integer.class) {
                forcedResult = 0;
            } else {
                continue;
            }
            if (!hooked.contains(method)) {
                hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Config.enabled()) {
                            return;
                        }
                        try {
                            int raw = readRecallStatusField(param.thisObject);
                            if (raw != 0) {
                                long mid = readMid(param.thisObject);
                                if (mid == 0L || !recalledMids.contains(mid)) {
                                    markRecalled(param.thisObject, "getter " + param.method.getName() + " raw=" + raw);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        param.setResult(forcedResult);
                    }
                }, "-> " + forcedResult + " + detect", hooked);
            }
        }
    }

    private static void hookConstant(Class<?> clazz, String methodName, final Object forcedResult, Set<Method> hooked) {
        boolean matched = false;
        for (Method method : declaredMethods(clazz)) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            matched = true;
            if (!hooked.contains(method)) {
                hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Config.enabled()) {
                            return;
                        }
                        param.setResult(forcedResult);
                    }
                }, "-> " + forcedResult, hooked);
            }
        }
        if (!matched) {
            XposedBridge.log(TAG + methodName + " not present on " + clazz.getName());
        }
    }

    private static void hookLocalRecallSetter(Class<?> clazz, Set<Method> hooked) {
        boolean matched = false;
        for (Method method : declaredMethods(clazz)) {
            if (!method.getName().equals(LOCAL_RECALL_SETTER) || method.getParameterTypes().length != 1) {
                continue;
            }
            if (method.getParameterTypes()[0] != int.class || method.getReturnType() != void.class) {
                continue;
            }
            matched = true;
            if (!hooked.contains(method)) {
                hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Config.enabled()) {
                            return;
                        }
                        int requested = param.args[0] instanceof Integer ? (Integer) param.args[0] : 0;
                        if (requested != 0) {
                            logEvent(LOCAL_RECALL_SETTER + "(" + requested + ")");
                            markRecalled(param.thisObject, "setter arg=" + requested);
                            param.args[0] = 0;
                        }
                    }
                }, "force arg 0 + mark", hooked);
            }
        }
        if (!matched) {
            XposedBridge.log(TAG + LOCAL_RECALL_SETTER + "(int) not present on " + clazz.getName());
        }
    }

    private static void hookConvRecallEvent(Class<?> clazz, Set<Method> hooked) {
        boolean matched = false;
        for (Method method : declaredMethods(clazz)) {
            if (!method.getName().equals(CONV_RECALL_EVENT) || method.getParameterTypes().length != 0) {
                continue;
            }
            matched = true;
            if (!hooked.contains(method)) {
                hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Config.enabled()) {
                            return;
                        }
                        logEvent(CONV_RECALL_EVENT);
                        markRecalled(param.thisObject, "conv event");
                    }
                }, "mark recalled", hooked);
            }
        }
        if (!matched) {
            XposedBridge.log(TAG + CONV_RECALL_EVENT + " not present on " + clazz.getName());
        }
    }

    private static void hookEventTracer(Class<?> clazz, String methodName, Set<Method> hooked) {
        for (Method method : declaredMethods(clazz)) {
            if (!method.getName().equals(methodName) || method.getParameterTypes().length > 1) {
                continue;
            }
            if (hooked.contains(method)) {
                continue;
            }
            hook(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logEvent(describeMethod(param.method));
                }
            }, "trace", hooked);
        }
    }

    private static void hookContentReplay(Class<?> clazz, Set<Method> hooked) {
        for (Method method : declaredMethods(clazz)) {
            if (method.getParameterTypes().length != 0 || !isContentGetter(method.getName())) {
                continue;
            }
            if (method.getReturnType() == void.class || hooked.contains(method)) {
                continue;
            }
            hook(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!Config.enabled()) {
                            return;
                        }
                        Object message = param.thisObject;
                        long mid = readMid(message);
                        int raw = readRecallStatusField(message);
                        boolean recalled = raw != 0;
                        if (recalled) {
                            if (mid != 0L) {
                                rememberMid(mid);
                            }
                        } else if (mid != 0L) {
                            ensureMidsLoaded();
                            recalled = recalledMids.contains(mid);
                        }

                        Object content = param.getResult();
                        if (!recalled) {
                            cacheOriginalContent(mid, content);
                            return;
                        }

                        Object restored = applyRecallMarker(message, mid, content);
                        if (restored != null && restored != content) {
                            param.setResult(restored);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "cache original + replay marker", hooked);
        }
    }

    private static void cacheOriginalContent(long mid, Object content) {
        if (mid == 0L || content == null || isRecallPlaceholder(content)) {
            return;
        }
        synchronized (originalContents) {
            if (originalContents.containsKey(mid)) {
                return;
            }
            originalContents.put(mid, new CachedContent(content, readContentType(content), readContentText(content)));
        }
    }

    private static boolean isRecallPlaceholder(Object content) {
        try {
            Object value = XposedHelpers.callMethod(content, "text");
            return value instanceof String && RECALL_PLACEHOLDER.equals(((String) value).trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object applyRecallMarker(Object message, long mid, Object current) {
        CachedContent cached = getCachedContent(mid);
        if (cached == null) {
            if (mid != 0L && current != null && markedMids.add(mid)) {
                applyMarker(current);
            }
            return null;
        }
        synchronized (cached) {
            boolean mutated = restoreRecallMutation(cached);
            if (mutated || !cached.marked) {
                applyMarker(cached.content);
                cached.marked = true;
            }
        }
        setContentField(message, cached.content);
        return cached.content;
    }

    private static CachedContent getCachedContent(long mid) {
        if (mid == 0L) {
            return null;
        }
        synchronized (originalContents) {
            return originalContents.get(mid);
        }
    }

    private static void setContentField(Object message, Object content) {
        try {
            XposedHelpers.setObjectField(message, "mMessageContent", content);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isTextContent(Object content) {
        try {
            Object type = XposedHelpers.callMethod(content, "type");
            if (type instanceof Integer) {
                return (Integer) type == 1;
            }
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getIntField(content, "mType") == 1;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int readContentType(Object content) {
        try {
            Object type = XposedHelpers.callMethod(content, "type");
            if (type instanceof Integer) {
                return (Integer) type;
            }
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getIntField(content, "mType");
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String readContentText(Object content) {
        try {
            Object value = XposedHelpers.callMethod(content, "text");
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean restoreRecallMutation(CachedContent cached) {
        try {
            Object value = XposedHelpers.callMethod(cached.content, "text");
            if (!(value instanceof String) || !RECALL_PLACEHOLDER.equals(((String) value).trim())) {
                return false;
            }
            String original = cached.originalText == null ? "" : cached.originalText;
            XposedHelpers.callMethod(cached.content, "setText", original);
            if (cached.originalType != 0) {
                try {
                    XposedHelpers.setIntField(cached.content, "mType", cached.originalType);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class CachedContent {
        final Object content;
        final int originalType;
        final String originalText;
        volatile boolean marked;

        CachedContent(Object content, int originalType, String originalText) {
            this.content = content;
            this.originalType = originalType;
            this.originalText = originalText;
        }
    }

    private static boolean isContentGetter(String name) {
        for (String candidate : CONTENT_GETTERS) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void markRecalled(Object message, String reason) {
        if (message == null || !Config.enabled() || Boolean.TRUE.equals(marking.get())) {
            return;
        }
        marking.set(Boolean.TRUE);
        try {
            long mid = readMid(message);
            XposedBridge.log(TAG + "recall detected (" + reason + ") mid=" + mid);
            if (DISCOVER_CHAT_VIEWS) {
                try {
                    ChatViewDiscovery.onRecallDetected(mid);
                } catch (Throwable ignored) {
                }
            }
            if (INJECT_CHAT_HINTS) {
                try {
                    ChatHintInjector.onRecallDetected(mid);
                } catch (Throwable ignored) {
                }
            }
            if (mid != 0L) {
                rememberMid(mid);
            }
            applyRecallMarker(message, mid, getContent(message));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "markRecalled failed: " + t);
        } finally {
            marking.set(Boolean.FALSE);
        }
    }

    static boolean isRecalledMessage(Object message) {
        if (message == null) {
            return false;
        }
        long mid = readMid(message);
        if (mid != 0L) {
            ensureMidsLoaded();
            if (recalledMids.contains(mid)) {
                return true;
            }
        }
        return readRecallStatusField(message) != 0;
    }

    static boolean isTextMessage(Object message) {
        return isTextContent(getContent(message));
    }

    static long messageMid(Object message) {
        return readMid(message);
    }

    private static int readRecallStatusField(Object message) {
        try {
            return XposedHelpers.getIntField(message, RECALL_STATUS_FIELD);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long readMid(Object message) {
        try {
            return XposedHelpers.getLongField(message, MESSAGE_ID_FIELD);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Object getContent(Object message) {
        for (String name : CONTENT_GETTERS) {
            try {
                Object content = XposedHelpers.callMethod(message, name);
                if (content != null) {
                    return content;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void applyMarker(Object content) {
        if (content == null || !Config.marker()) {
            return;
        }
        try {
            Object value = XposedHelpers.callMethod(content, "text");
            String text = value instanceof String ? (String) value : null;
            if (text != null && (text.endsWith(RECALL_LABEL) || text.endsWith(RECALL_MARKER))) {
                return;
            }
            if (text == null || text.isEmpty()) {
                XposedHelpers.callMethod(content, "setText", RECALL_LABEL);
                XposedBridge.log(TAG + "marker applied (label only)");
                return;
            }
            if (isTextContent(content)) {
                XposedHelpers.callMethod(content, "setText", text + RECALL_MARKER);
            } else {
                XposedHelpers.callMethod(content, "setText", text + " " + RECALL_LABEL);
            }
            XposedBridge.log(TAG + "marker applied");
        } catch (Throwable ignored) {
        }
    }

    private static void rememberMid(long mid) {
        ensureMidsLoaded();
        synchronized (recalledMids) {
            if (recalledMids.size() >= MAX_TRACKED_MIDS) {
                recalledMids.clear();
            }
            if (recalledMids.add(mid)) {
                saveMids();
            }
        }
    }

    private static SharedPreferences prefs() {
        try {
            Application app = currentApplication();
            if (app == null) {
                return null;
            }
            return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureMidsLoaded() {
        if (midsLoaded) {
            return;
        }
        synchronized (recalledMids) {
            if (midsLoaded) {
                return;
            }
            SharedPreferences store = prefs();
            if (store == null) {
                return;
            }
            midsLoaded = true;
            String raw = store.getString(PREFS_MIDS, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            for (String part : raw.split(",")) {
                try {
                    recalledMids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static void saveMids() {
        SharedPreferences store = prefs();
        if (store == null) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        synchronized (recalledMids) {
            for (Long id : recalledMids) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(id);
            }
        }
        store.edit().putString(PREFS_MIDS, builder.toString()).apply();
    }

    private static void hookByHeuristic(Class<?> clazz, Set<Method> hooked) {
        for (Method method : declaredMethods(clazz)) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0) {
                continue;
            }
            String lower = method.getName().toLowerCase();
            if (!lower.contains("recall") || hooked.contains(method)) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == int.class || returnType == Integer.class) {
                hookConstantMethod(method, 0, hooked);
            } else if (returnType == boolean.class || returnType == Boolean.class) {
                hookConstantMethod(method, lower.contains("can") ? Boolean.TRUE : Boolean.FALSE, hooked);
            }
        }
    }

    private static void hookConstantMethod(final Method method, final Object forcedResult, Set<Method> hooked) {
        hook(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Config.enabled()) {
                    return;
                }
                param.setResult(forcedResult);
            }
        }, "-> " + forcedResult + " (heuristic)", hooked);
    }

    private static void blockRecallStatusSetters(Class<?> clazz, Set<Method> hooked) {
        for (Method method : declaredMethods(clazz)) {
            if (hooked.contains(method) || method.getParameterTypes().length != 1) {
                continue;
            }
            if (method.getReturnType() != void.class && method.getReturnType() != int.class) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (param != int.class && param != Integer.class) {
                continue;
            }
            String lower = method.getName().toLowerCase();
            if (!lower.contains("recall") || !lower.contains("status")) {
                continue;
            }
            final Object forcedResult = method.getReturnType() == void.class ? null : 0;
            hook(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Config.enabled()) {
                        return;
                    }
                    param.setResult(forcedResult);
                }
            }, "blocked setter", hooked);
        }
    }

    private static Method[] declaredMethods(Class<?> clazz) {
        try {
            return clazz.getDeclaredMethods();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "read methods of " + clazz.getName() + " failed: " + t);
            return new Method[0];
        }
    }

    private static void hook(Method method, XC_MethodHook callback, String note, Set<Method> hooked) {
        try {
            XposedBridge.hookMethod(method, callback);
            hooked.add(method);
            XposedBridge.log(TAG + "hooked " + describe(method) + " " + note);
        } catch (Throwable t) {
            hooked.add(method);
            XposedBridge.log(TAG + "hook failed " + describe(method) + ": " + t);
        }
    }

    private static void logEvent(String event) {
        XposedBridge.log(TAG + "EVENT " + event);
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("com.alibaba") || name.startsWith("com.dingtalk")) {
                XposedBridge.log(TAG + "  at " + frame);
            }
        }
    }

    private static boolean interestingMember(String name) {
        String lower = name.toLowerCase();
        return lower.contains("recall") || lower.contains("revoke") || lower.contains("withdraw");
    }

    private static void logRecallMembers(Class<?> clazz) {
        try {
            for (Field field : clazz.getDeclaredFields()) {
                String lower = field.getName().toLowerCase();
                if (interestingMember(field.getName()) || lower.contains("status")) {
                    XposedBridge.log(TAG + "field " + field.getType().getSimpleName() + " " + field.getName());
                }
            }
            for (Method method : declaredMethods(clazz)) {
                if (interestingMember(method.getName()) && method.getParameterTypes().length <= 2) {
                    XposedBridge.log(TAG + "method " + describe(method));
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "logRecallMembers failed: " + t);
        }
    }

    private static String describeMethod(java.lang.reflect.Member member) {
        return member.getName();
    }

    private static String describe(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getReturnType().getSimpleName())
                .append(' ')
                .append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(params[i].getSimpleName());
        }
        builder.append(')');
        return builder.toString();
    }
}
