package com.wunelezi.injector.tencent.connect.common;

/**
 * 腾讯 SDK Constants 的最小化补全实现（原 com.tencent.connect.common.Constants）。
 * 仅声明 AssistActivity 直接引用的 key 常量；取值对当前 PoC 流程无影响
 * （相关 extra 在运行时取不到时走默认值）。
 */
public final class Constants {
    private Constants() {
    }

    public static final String KEY_RESTORE_LANDSCAPE = "key_restore_landscape";
    public static final String KEY_PASS_REPORT_VIA_PARAM = "key_pass_report_via_param";
    public static final String KEY_PASS_REPORT_VIA_TIMELY = "key_pass_report_via_timely";
}
