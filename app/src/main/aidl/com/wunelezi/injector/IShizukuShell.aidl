package com.wunelezi.injector;

import android.os.Bundle;

interface IShizukuShell {
    // 保留方法：Shizuku 服务端用于销毁用户服务（固定事务码，勿改）
    void destroy() = 16777114;

    // 以 Shizuku 身份（root / adb shell）执行 shell 命令，返回退出码与合并输出
    Bundle exec(in String command, in String[] env) = 1;
}
