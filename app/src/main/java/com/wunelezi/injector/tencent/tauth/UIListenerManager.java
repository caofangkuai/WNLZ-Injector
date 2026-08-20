package com.wunelezi.injector.tencent.tauth;

/**
 * 腾讯 SDK UIListenerManager 的最小化补全实现（原 com.tencent.tauth.UIListenerManager）。
 * 本 PoC 中不存在真实回调监听，所有查询均返回 null。
 */
public class UIListenerManager {
    private static final UIListenerManager INSTANCE = new UIListenerManager();

    public static UIListenerManager getInstance() {
        return INSTANCE;
    }

    public IUiListener getListnerWithRequestCode(int code) {
        return null;
    }

    public IUiListener getListnerWithAction(String action) {
        return null;
    }
}
