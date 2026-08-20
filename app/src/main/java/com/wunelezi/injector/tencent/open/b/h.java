package com.wunelezi.injector.tencent.open.b;

import android.os.Bundle;

/**
 * 腾讯 SDK 统计上报类 h 的最小化补全实现（原 com.tencent.open.b.h）。
 * 原逻辑为埋点上报，在本 PoC 中为 no-op。
 */
public class h {
    private static final h INSTANCE = new h();

    public static h a() {
        return INSTANCE;
    }

    public void a(Bundle b, String appId, boolean timelyReport) {
        // no-op: 原实现为统计上报
    }
}
