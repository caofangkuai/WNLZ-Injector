package com.wunelezi.injector.tencent.open.utils;

import android.content.Context;
import android.os.Bundle;

/**
 * 腾讯 SDK 工具类 m 的最小化补全实现（原 com.tencent.open.utils.m）。
 * 仅保留 AssistActivity 直接引用的两个重载方法。
 */
public class m {
    /** 埋点参数上报，no-op。 */
    public static void a(Bundle b, String s) {
        // no-op
    }

    /** 原实现为「能否用浏览器打开该 url」判断；本 PoC 恒返回 false（走 else 分支，不触发浏览器逻辑）。 */
    public static boolean a(Context c, String url) {
        return false;
    }
}
