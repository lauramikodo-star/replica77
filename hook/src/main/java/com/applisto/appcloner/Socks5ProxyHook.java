package com.applisto.appcloner;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.*;
import javax.net.SocketFactory;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.Pine.CallFrame;  // ← NEW LINE

public final class Socks5ProxyHook {
    private static final String TAG = "Socks5ProxyHook";

    private static String proxyHost = "";
    private static int    proxyPort = 0;
    private static String proxyUser = "";
    private static String proxyPass = "";

    private static volatile boolean installed = false;

    /* ---------- public entry ---------- */
    public static void initEarly(Context ctx) {
        if (installed) return;
        loadSettings(ctx);

        if (TextUtils.isEmpty(proxyHost) || proxyPort == 0) {
            Log.i(TAG, "No SOCKS settings – skipping installation");
            return;
        }

        Log.i(TAG, "Installing global SOCKS5 proxy → " +
                proxyHost + ':' + proxyPort +
                (TextUtils.isEmpty(proxyUser) ? "" : " (auth)"));

        applyJvmProxyProperties();
        hookSocketConstructors();
        hookSocketFactoryOverloads();

        installed = true;
        Log.i(TAG, "SOCKS5 proxy hook fully installed");
    }

    /* ---------------- settings ---------------- */
    private static void loadSettings(Context ctx) {
        try (InputStream in = ctx.getAssets().open("cloner.json")) {
            byte[] buf = new byte[in.available()];
            in.read(buf);
            JSONObject j = new JSONObject(new String(buf));

            proxyHost = j.optString("socks_proxy_host", "");
            proxyPort = j.optInt   ("socks_proxy_port", 0);
            proxyUser = j.optString("socks_proxy_user", "");
            proxyPass = j.optString("socks_proxy_pass", "");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read cloner.json", t);
        }
    }

    /* --------------- JVM props --------------- */
    private static void applyJvmProxyProperties() {
        System.setProperty("socksProxyHost", proxyHost);
        System.setProperty("socksProxyPort", String.valueOf(proxyPort));

        if (!TextUtils.isEmpty(proxyUser)) {
            java.net.Authenticator.setDefault(new java.net.Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            proxyUser,
                            proxyPass == null ? new char[0] : proxyPass.toCharArray());
                }
            });
        }
    }

    /* -------------- hook Socket() ------------ */
    private static void hookSocketConstructors() {
        try {
            for (Constructor<?> ctor : Socket.class.getDeclaredConstructors()) {
                Pine.hook(ctor, new MethodHook() {
                    @Override
                    public void afterCall(CallFrame cf) {
                        cf.setResult(newProxiedSocket());
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "Socket() constructor hooks failed", t);
        }
    }

    /* ------ hook SocketFactory.createSocket() ------ */
    private static void hookSocketFactoryOverloads() {
        try {
            /* 1) createSocket() */
            Method m0 = SocketFactory.class.getDeclaredMethod("createSocket");
            Pine.hook(m0, new MethodHook() {
                @Override public void afterCall(CallFrame cf) { cf.setResult(newProxiedSocket()); }
            });

            /* 2) createSocket(String,int) */
            Method m1 = SocketFactory.class.getDeclaredMethod(
                    "createSocket", String.class, int.class);
            Pine.hook(m1, new MethodHook() {
                @Override public void beforeCall(CallFrame cf) throws Throwable {
                    String host = (String) cf.args[0];
                    int    port = (int)    cf.args[1];
                    Socket s = newProxiedSocket();
                    s.connect(new InetSocketAddress(host, port));
                    cf.setResult(s);
                }
            });

            /* 3) createSocket(InetAddress,int) */
            Method m2 = SocketFactory.class.getDeclaredMethod(
                    "createSocket", InetAddress.class, int.class);
            Pine.hook(m2, new MethodHook() {
                @Override public void beforeCall(CallFrame cf) throws Throwable {
                    InetAddress addr = (InetAddress) cf.args[0];
                    int         port = (int)        cf.args[1];
                    Socket s = newProxiedSocket();
                    s.connect(new InetSocketAddress(addr, port));
                    cf.setResult(s);
                }
            });

        } catch (Throwable t) {
            Log.e(TAG, "SocketFactory hooks failed", t);
        }
    }

    /* -------------- helper ------------- */
    private static Socket newProxiedSocket() {
        return new Socket(new Proxy(
                Proxy.Type.SOCKS,
                new InetSocketAddress(proxyHost, proxyPort)));
    }
}
