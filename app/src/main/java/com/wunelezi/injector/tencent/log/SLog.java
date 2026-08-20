package com.wunelezi.injector.tencent.log;

import android.util.Log;

/**
 * 腾讯 SDK 日志类的最小化补全实现（原 com.tencent.open.log.SLog）。
 * 仅保留 AssistActivity 用到的静态方法，转发到 android.util.Log。
 */
public final class SLog {
    private SLog() {
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
    }
}
