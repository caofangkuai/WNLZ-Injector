package com.wunelezi.injector.tencent.tauth;

/**
 * 腾讯 SDK UiError 的最小化补全实现（原 com.tencent.tauth.UiError）。
 */
public class UiError {
    public int errorCode;
    public String errorMessage;
    public String errorDetail;

    public UiError(int errorCode, String errorMessage, String errorDetail) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.errorDetail = errorDetail;
    }
}
