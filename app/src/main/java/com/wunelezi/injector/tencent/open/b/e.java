package com.wunelezi.injector.tencent.open.b;

/**
 * 腾讯 SDK 统计上报类 e 的最小化补全实现（原 com.tencent.open.b.e）。
 * 原逻辑为埋点上报，在本 PoC 中不产生业务副作用，所有方法均为 no-op。
 */
public class e {
    private static final e INSTANCE = new e();

    public static e a() {
        return INSTANCE;
    }

    public void a(String... args) {
        // no-op: 原实现为统计上报
    }
}
