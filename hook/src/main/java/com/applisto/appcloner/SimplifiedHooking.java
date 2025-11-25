package com.applisto.appcloner;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class SimplifiedHooking {
    private static final String TAG = "SimplifiedHooking";

    public abstract static class HookCallback extends MethodHook {
        @Override
        public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
            Object thisObj = getCallFrameField(callFrame, "thisObj");
            Object[] args = (Object[]) getCallFrameField(callFrame, "args");
            before(thisObj, args, callFrame);
        }

        @Override
        public void afterCall(Pine.CallFrame callFrame) throws Throwable {
            Object thisObj = getCallFrameField(callFrame, "thisObj");
            Object[] args = (Object[]) getCallFrameField(callFrame, "args");
            after(thisObj, args, callFrame);
        }

        public abstract void before(Object thisObject, Object[] args, Pine.CallFrame callFrame) throws Throwable;

        public abstract void after(Object thisObject, Object[] args, Pine.CallFrame callFrame) throws Throwable;

        private Object getCallFrameField(Pine.CallFrame callFrame, String fieldName) throws Throwable {
            try {
                Field field = Pine.CallFrame.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(callFrame);
            } catch (Exception e) {
                throw new RuntimeException("Failed to access CallFrame field: " + fieldName, e);
            }
        }
    }

    public static void hookMethod(Class<?> clazz, String methodName, HookCallback callback, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            Pine.hook(method, callback);
            Log.d(TAG, "Hooked method: " + clazz.getName() + "." + methodName);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Method not found: " + clazz.getName() + "." + methodName, e);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook: " + clazz.getName() + "." + methodName, t);
        }
    }

    public static Object getCallFrameResult(Pine.CallFrame callFrame) throws Throwable {
        try {
            Field resultField = Pine.CallFrame.class.getDeclaredField("result");
            resultField.setAccessible(true);
            return resultField.get(callFrame);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get call frame result", e);
        }
    }

    public static void setCallFrameResult(Pine.CallFrame callFrame, Object result) throws Throwable {
        try {
            Field resultField = Pine.CallFrame.class.getDeclaredField("result");
            resultField.setAccessible(true);
            resultField.set(callFrame, result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set call frame result", e);
        }
    }
}
