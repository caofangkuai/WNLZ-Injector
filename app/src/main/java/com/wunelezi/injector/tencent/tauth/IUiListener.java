package com.wunelezi.injector.tencent.tauth;

/**
 * 腾讯 SDK IUiListener 的最小化补全实现（原 com.tencent.tauth.IUiListener）。
 * 仅声明 AssistActivity 用到的 onError 回调。
 */
public interface IUiListener {
    void onComplete(Object response);

    void onError(UiError e);

    void onCancel();
}
