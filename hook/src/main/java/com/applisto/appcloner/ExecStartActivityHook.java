// Updated ExecStartActivityHook.java
// Changes: No major changes needed (already robust with both overloads). Added logging in chainAndForward for debug.

package com.applisto.appcloner;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityResult;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Chain-of-responsibility hook around Instrumentation#execStartActivity.
 */
public abstract class ExecStartActivityHook {
    private static final String TAG = "ExecStartActivityHook";

    private static final List<ExecStartActivityHook> HOOKS = new ArrayList<>();
    private static volatile boolean INSTALLED = false;

    /* package */ static boolean installOnce(Context ctx) {
        if (INSTALLED) return true;
        synchronized (ExecStartActivityHook.class) {
            if (INSTALLED) return true;
            try {
                InstrumentationWrapper.wrap(ctx);
                INSTALLED = true;
                Log.i(TAG, "Instrumentation wrapper installed");
            } catch (Throwable t) {
                Log.e(TAG, "Unable to install instrumentation wrapper", t);
                return false;
            }
        }
        return true;
    }

    public static String getActivityName(Intent intent) {
        if (intent == null) return "";
        StringBuilder sb = new StringBuilder();
        String action = intent.getAction();
        if (!TextUtils.isEmpty(action)) sb.append(action);
        ComponentName cn = intent.getComponent();
        if (cn != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(cn.flattenToString());
        }
        return sb.toString();
    }

    /** Register this hook instance. Triggers wrapper installation on first call. */
    public final void install(Context ctx) {
        if (ctx == null) throw new IllegalArgumentException("context == null");
        if (!installOnce(ctx)) {
            Log.w(TAG, "Hook installation failed – " + getClass().getSimpleName() + " will not be invoked");
            return;
        }
        synchronized (HOOKS) {
            HOOKS.add(this);
        }
        Log.i(TAG, "Registered hook: " + getClass().getName());
    }

    /** Return true to allow the original execStartActivity; false to veto (return null to caller). */
    protected abstract boolean onExecStartActivity(ExecStartActivityArgs args)
            throws ActivityNotFoundException;

    /** Mutable args matching execStartActivity params. */
    public static final class ExecStartActivityArgs {
        public Context who;
        public IBinder contextThread;
        public IBinder token;
        public Activity target;
        public Intent intent;
        public int requestCode;
        public Bundle options;
    }

    /** Instrumentation wrapper installed into ActivityThread.mInstrumentation. */
    private static final class InstrumentationWrapper extends Instrumentation {
        private static Instrumentation original;

        static void wrap(Context ctx) throws Exception {
            if (original != null) return;
            Class<?> atClz = Class.forName("android.app.ActivityThread");
            Object at = atClz.getDeclaredMethod("currentActivityThread").invoke(null);

            // Read current mInstrumentation and replace it
            Field f = atClz.getDeclaredField("mInstrumentation");
            f.setAccessible(true);
            original = (Instrumentation) f.get(at);
            f.set(at, new InstrumentationWrapper());
        }

        // Do not annotate with @Override because these are hidden in SDK stubs.
        public ActivityResult execStartActivity(
                Context who, IBinder contextThread, IBinder token, Activity target,
                Intent intent, int requestCode, Bundle options) {
            Log.d(TAG, "execStartActivity called (no UserHandle): " + getActivityName(intent));
            return chainAndForward(null, who, contextThread, token, target, intent, requestCode, options);
        }

        public ActivityResult execStartActivity(
                Context who, IBinder contextThread, IBinder token, Activity target,
                Intent intent, int requestCode, Bundle options, UserHandle user) {
            Log.d(TAG, "execStartActivity called (with UserHandle): " + getActivityName(intent));
            return chainAndForward(user, who, contextThread, token, target, intent, requestCode, options);
        }

        private ActivityResult chainAndForward(UserHandle userHandleOrNull,
                                               Context who, IBinder contextThread, IBinder token, Activity target,
                                               Intent intent, int requestCode, Bundle options) {
            ExecStartActivityArgs args = new ExecStartActivityArgs();
            args.who = who;
            args.contextThread = contextThread;
            args.token = token;
            args.target = target;
            args.intent = intent;
            args.requestCode = requestCode;
            args.options = options;

            // Run hooks
            synchronized (HOOKS) {
                for (ExecStartActivityHook h : HOOKS) {
                    try {
                        if (!h.onExecStartActivity(args)) {
                            Log.i(TAG, "Hook vetoed: " + h.getClass().getSimpleName());
                            return null; // veto: do not call original
                        }
                    } catch (ActivityNotFoundException e) {
                        throw e;
                    } catch (Throwable t) {
                        Log.e(TAG, "Hook crashed: " + h.getClass().getName(), t);
                    }
                }
            }

            // Forward to original using possibly mutated args
            if (original == null) throw new IllegalStateException("Original Instrumentation is null");
            try {
                if (userHandleOrNull == null) {
                    Method m = original.getClass().getDeclaredMethod(
                            "execStartActivity",
                            Context.class, IBinder.class, IBinder.class, Activity.class,
                            Intent.class, int.class, Bundle.class
                    );
                    m.setAccessible(true);
                    return (ActivityResult) m.invoke(original,
                            args.who, args.contextThread, args.token, args.target,
                            args.intent, args.requestCode, args.options);
                } else {
                    Method m = original.getClass().getDeclaredMethod(
                            "execStartActivity",
                            Context.class, IBinder.class, IBinder.class, Activity.class,
                            Intent.class, int.class, Bundle.class, UserHandle.class
                    );
                    m.setAccessible(true);
                    return (ActivityResult) m.invoke(original,
                            args.who, args.contextThread, args.token, args.target,
                            args.intent, args.requestCode, args.options, userHandleOrNull);
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }
}