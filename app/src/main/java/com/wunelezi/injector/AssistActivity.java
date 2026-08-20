package com.wunelezi.injector;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.wunelezi.injector.tencent.log.SLog;
import com.wunelezi.injector.tencent.open.b.h;
import com.wunelezi.injector.tencent.connect.common.Constants;
import com.wunelezi.injector.tencent.tauth.IUiListener;
import com.wunelezi.injector.tencent.tauth.Tencent;
import com.wunelezi.injector.tencent.tauth.UiError;
import com.wunelezi.injector.tencent.tauth.UIListenerManager;
import com.wunelezi.injector.tencent.open.utils.m;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AssistActivity：
 *  - 窗口本体内有一个全屏滚动 TextView，作为「实时 logcat 输出窗口」+ 「本 Activity 内部日志」；
 *  - 历史上曾经用 AlertDialog 把每条日志 / 异常都弹一遍；现在这些日志 / 异常全部删掉弹窗，
 *    直接追加到背景 TextView；
 *  - 仅保留「Intent 详情对话框」一处 AlertDialog：doOnCreate 中转发 PendingIntent 前，
 *    会先弹一个 AlertDialog 把即将执行的 Intent / PendingIntent 关键信息呈现给用户确认。
 */
public class AssistActivity extends Activity {
    public static final String EXTRA_INTENT = "openSDK_LOG.AssistActivity.ExtraIntent";
    public static final String KEY_EXTRA_PENDING_INTENT = "key_extra_pending_intent";
    public static final String KEY_REQUEST_ORIENTATION = "key_request_orientation";

    private String d;
    private QQStayReceiver e;
    private boolean f;
    private boolean c = false;
    protected boolean a = false;
    protected Handler b = new Handler() {
        @Override
        public void handleMessage(Message message) {
            try {
                if (message.what == 0 && !AssistActivity.this.isFinishing()) {
                    logToBg("openSDK_LOG.AssistActivity", "-->finish by timeout");
                    AssistActivity.this.promptFinish("超时触发 finish（timeout）");
                }
            } catch (Throwable t) {
                appendBgError("Handler.handleMessage 异常", t);
            }
        }
    };

    /** 背景滚动 TextView + ScrollView，本 Activity 的"实时日志窗"主体 */
    private TextView tvBg;
    private ScrollView scrollBg;

    /** logcat 子进程控制 */
    private Process logcatProc;
    private Thread logcatReader;
    private final AtomicBoolean logcatRunning = new AtomicBoolean(false);

    public static Intent getAssistActivityIntent(Context context) {
        return new Intent(context, (Class<?>) AssistActivity.class);
    }

    // ==================== 背景 TextView 工具方法 ====================

    /**
     * 把一行情本追加到背景 TextView；新行靠底部，自动滚动到底部。
     * Activity 已销毁则直接丢弃。
     */
    private void appendBgLine(final String line) {
        if (isFinishing() || isDestroyed()) return;
        if (tvBg == null) return;
        final String text = (line == null ? "" : line) + "\n";
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendBgLineNow(text);
        } else {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (isFinishing() || isDestroyed() || tvBg == null) return;
                    appendBgLineNow(text);
                }
            });
        }
    }

    private void appendBgLineNow(String text) {
        try {
            // 全量 logcat 输出量巨大，超 MAX_BG_CHARS 时裁掉前半段（按行），保留最新
            CharSequence cur = tvBg.getText();
            if (cur != null && cur.length() + text.length() > MAX_BG_CHARS) {
                int oversize = (cur.length() + text.length()) - MAX_BG_CHARS;
                int targetLen = cur.length() - oversize;
                if (targetLen < 0) targetLen = 0;
                // 在 targetLen 之后找一个换行边界（避免把一行切成两半）
                int cutFrom = targetLen;
                int nextNl = -1;
                int maxScan = Math.min(cur.length(), targetLen + 4096);
                for (int i = targetLen; i < maxScan; i++) {
                    if (cur.charAt(i) == '\n') {
                        nextNl = i;
                        break;
                    }
                }
                if (nextNl > 0 && nextNl + 1 < cur.length()) {
                    cutFrom = nextNl + 1;
                }
                // 触发一次"截断提示"插入到内容前面
                String notice = "[已自动截断 " + (cutFrom) + " 字符旧日志]\n";
                tvBg.setText(notice + cur.subSequence(cutFrom, cur.length()));
            }
            tvBg.append(text);
            if (scrollBg != null) {
                scrollBg.post(new Runnable() {
                    @Override public void run() {
                        try {
                            scrollBg.fullScroll(View.FOCUS_DOWN);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 写一条带前缀的日志：[tag] msg
     */
    private void logToBg(String tag, String msg) {
        try { SLog.i(tag, msg); } catch (Throwable ignored) {}
        appendBgLine("[" + (tag == null ? "" : tag) + "] " + (msg == null ? "" : msg));
    }

    /**
     * 把异常追加到背景 TextView（不再弹窗）。
     */
    private void appendBgError(final String title, final Throwable t) {
        if (t == null) return;
        try { SLog.e(title, "AssistActivity 捕获异常", t); } catch (Throwable ignored) {}
        final StringBuilder sb = new StringBuilder();
        sb.append("[ERR ").append(title == null ? "" : title).append("] ");
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
        StackTraceElement[] stack = t.getStackTrace();
        int n = Math.min(20, stack == null ? 0 : stack.length);
        for (int i = 0; i < n; i++) {
            sb.append("  at ").append(stack[i]).append("\n");
        }
        appendBgLine(sb.toString());
    }

    /**
     * 替代原本的"提示用户 finish"——现在直接写一行日志就 finish。
     */
    private void promptFinish(final String reason) {
        if (isFinishing() || isDestroyed()) return;
        appendBgLine("[FINISH] " + (reason == null ? "" : reason));
        finish();
    }

    // ==================== logcat 子进程 ====================

    /**
     * 启动后台 logcat 实时抓取；输出全部追加到背景 TextView 上。
     * - 多次调用是幂等的：第一次成功后就忽略后续调用。
     * - onDestroy 时会自动 stop。
     *
     * [全量抓取] 不使用任何 tag 过滤：
     *   * logcat -b all  - 同时抓 main / system / events / crash / radio 五个 buffer
     *   * -v threadtime - 输出格式：time pid tid priority tag message（每条带时间/线程）
     *   * 不加 filter 等价于 "ALL:V"，相当于把 *:S 拿掉，让所有 tag 都放出来
     *
     * [防止 OOM] 配合 {@link #appendBgLineNow} 的"超长截断"策略，避免 TextView 撑爆：
     *   * TextView 总内容上限 MAX_BG_CHARS（300 KB），
     *   * 超过就裁掉前半段（保留最新），且裁剪点必须在换行边界
     */
    private static final int MAX_BG_CHARS = 300 * 1024;

    private void startBgLogcat() {
        if (logcatRunning.get()) return;
        try {
            // 不传 filter = 显示所有 tag；-b all 让 system/crash 等 buffer 也抓
            logcatProc = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-b", "all", "-v", "threadtime"});
        } catch (Throwable t) {
            appendBgError("logcat 启动失败", t);
            return;
        }
        logcatRunning.set(true);
        logcatReader = new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(logcatProc.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!logcatRunning.get()) break;
                        // logcat 单行可能很长（堆栈链路），再粗略截断避免异常巨大行阻塞主线程
                        if (line.length() > 8192) {
                            line = line.substring(0, 8192) + "...[截断]";
                        }
                        appendBgLine(line);
                    }
                } catch (Throwable t) {
                    appendBgError("logcat reader 异常", t);
                } finally {
                    if (br != null) try { br.close(); } catch (Throwable ignored) {}
                }
            }
        }, "assist-bg-logcat");
        logcatReader.setDaemon(true);
        logcatReader.start();
    }

    private void stopBgLogcat() {
        if (!logcatRunning.compareAndSet(true, false)) return;
        try {
            if (logcatProc != null) logcatProc.destroy();
        } catch (Throwable ignored) {}
        if (logcatReader != null) {
            try { logcatReader.interrupt(); } catch (Throwable ignored) {}
        }
    }

    // ==================== Activity 生命周期 ====================

    @Override
    protected void onCreate(Bundle bundle) {
        try {
            // [修复] requestWindowFeature() / addFlags() 都必须在 setContentView 之前调用，
            // 否则 AndroidRuntimeException: requestFeature() must be called before adding content。
            // 这里把所有"窗口特性"调用集中放在 setContentView 之前。
            getWindow().addFlags(0x04000000);

            super.onCreate(bundle);
            setContentView(R.layout.activity_assist);
            tvBg = (TextView) findViewById(R.id.tvAssistBg);
            scrollBg = (ScrollView) findViewById(R.id.scrollViewAssistBg);

            appendBgLine("=== AssistActivity 启动 ===");
            appendBgLine("pid=" + android.os.Process.myPid()
                    + " | taskId=" + getTaskId()
                    + " | time=" + new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(new java.util.Date()));

            // 后台 logcat 实时抓取（写到背景 TextView）
            startBgLogcat();

            doOnCreate(bundle);
        } catch (Throwable t) {
            appendBgError("onCreate 异常", t);
        }
    }

    private void doOnCreate(Bundle bundle) {
        // [修复] 主题已经是 Theme.WNLZInjector(NoActionBar)，不再调用 requestWindowFeature(1)。
        // 旧代码在 setContentView 之后调用 requestWindowFeature 会抛
        // "requestFeature() must be called before adding content"。
        this.f = getIntent().getBooleanExtra(Constants.KEY_RESTORE_LANDSCAPE, false);
        logToBg("openSDK_LOG.AssistActivity", "--onCreate-- mRestoreLandscape=" + this.f);
        if (getIntent() == null) {
            logToBg("openSDK_LOG.AssistActivity", "-->onCreate--getIntent() returns null");
            promptFinish("onCreate 中 getIntent() 为 null");
            return;
        }
        Intent intent = (Intent) getIntent().getParcelableExtra(EXTRA_INTENT);
        int intExtra = intent == null ? 0 : intent.getIntExtra("key_request_code", 0);
        this.d = intent == null ? "" : intent.getStringExtra("appid");
        Bundle bundleExtra = getIntent().getBundleExtra("h5_share_data");
        if (bundle != null) {
            this.c = bundle.getBoolean("RESTART_FLAG");
            this.a = bundle.getBoolean("RESUME_FLAG", false);
        }
        if (this.c) {
            logToBg("openSDK_LOG.AssistActivity", "is restart");
            return;
        }
        if (bundleExtra == null) {
            PendingIntent pendingIntent = (PendingIntent) getIntent().getParcelableExtra(KEY_EXTRA_PENDING_INTENT);
            if (intent != null && pendingIntent != null) {
                logToBg("openSDK_LOG.AssistActivity", "--onCreate--activityIntent not null, will start activity, reqcode = " + intExtra);
                try {
                    IntentFilter intentFilter = new IntentFilter("com.tencent.tauth.opensdk.SHARE_SUCCESS_AND_STAY_QQ_" + intent.getData().getQueryParameter("share_id"));
                    if (this.e == null) {
                        this.e = new QQStayReceiver();
                    }
                    registerReceiver(this.e, intentFilter, Context.RECEIVER_NOT_EXPORTED);
                } catch (Throwable t) {
                    appendBgError("onCreate.registerReceiver 异常", t);
                }
                try {
                    final IntentSender intentSender = pendingIntent.getIntentSender();
                    final Intent finalIntent = intent;
                    final int finalIntExtra = intExtra;
                    final PendingIntent finalPendingIntent = pendingIntent;
                    final String intentDescription = describeIntent(intent, pendingIntent);

                    // 同时把 Intent 详情落到背景 TextView，方便复盘
                    appendBgLine("[Intent 详情（即将弹出确认对话框）] " + intentDescription);

                    // 弹出 AlertDialog 让用户确认即将执行的 Intent / PendingIntent
                    if (isFinishing() || isDestroyed()) {
                        logToBg("openSDK_LOG.AssistActivity", "Activity 已销毁，跳过 Intent 详情对话框");
                        return;
                    }
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("即将跳转到 QQ 组件");
                    builder.setMessage(intentDescription);
                    builder.setCancelable(false);
                    builder.setPositiveButton("执行", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                dialog.dismiss();
                                if (isFinishing() || isDestroyed()) return;
                                try {
                                    if (finalIntent.getBooleanExtra("for_result", true)) {
                                        startIntentSenderForResult(intentSender, finalIntExtra, null, 0, 0, 0);
                                    } else {
                                        startIntentSender(intentSender, null, 0, 0, 0);
                                    }
                                    a(finalIntent, true);
                                } catch (Throwable t) {
                                    appendBgError("startIntentSender* 异常", t);
                                }
                            } catch (Throwable t) {
                                appendBgError("Intent 详情对话框.执行 回调异常", t);
                            }
                        }
                    });
                    builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                dialog.dismiss();
                                if (isFinishing() || isDestroyed()) return;
                                IUiListener listnerWithRequestCode = UIListenerManager.getInstance().getListnerWithRequestCode(finalIntExtra);
                                if (listnerWithRequestCode != null) {
                                    listnerWithRequestCode.onError(new UiError(901, "用户在 Intent 详情对话框中取消", ""));
                                }
                                promptFinish("用户在 Intent 详情对话框点击取消");
                            } catch (Throwable t) {
                                appendBgError("Intent 详情对话框.取消 回调异常", t);
                            }
                        }
                    });
                    builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            try {
                                if (isFinishing() || isDestroyed()) return;
                                promptFinish("Intent 详情对话框被外部取消（back 等）");
                            } catch (Throwable t) {
                                appendBgError("Intent 详情对话框.onCancel 异常", t);
                            }
                        }
                    });
                    builder.show();
                    return;
                } catch (ActivityNotFoundException e2) {
                    logToBg("openSDK_LOG.AssistActivity", "--onCreate--startActivity exception, ActivityNotFoundException : " + e2);
                    IUiListener listnerWithRequestCode = UIListenerManager.getInstance().getListnerWithRequestCode(intExtra);
                    if (listnerWithRequestCode != null) {
                        listnerWithRequestCode.onError(new UiError(-20, "手Q版本过低，请下载安装最新版手Q", ""));
                    }
                    a(intent, false);
                    return;
                } catch (Throwable e3) {
                    appendBgError("onCreate.startIntentSender 异常", e3);
                    promptFinish("onCreate 中启动目标 Activity 抛异常: " + e3.getMessage());
                    return;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("--onCreate--activityIntent or pendingIntent is null. activityIntent is null? ");
            sb.append(intent == null);
            sb.append(", pendingIntent is null? ");
            sb.append(pendingIntent == null);
            logToBg("openSDK_LOG.AssistActivity", sb.toString());
            promptFinish("onCreate 中 activityIntent 或 pendingIntent 为 null，无法继续");
            return;
        }
        logToBg("openSDK_LOG.AssistActivity", "--onCreate--h5 bundle not null, will open browser");
        a(bundleExtra);
    }

    private void a(Intent intent, boolean z) {
        try {
            doA(intent, z);
        } catch (Throwable t) {
            appendBgError("a(Intent, boolean) 异常", t);
        }
    }

    private void doA(Intent intent, boolean z) {
        if (intent == null) {
            logToBg("openSDK_LOG.AssistActivity", "reportStartActivitySuccess, but intent is null.");
            return;
        }
        Bundle bundleExtra = intent.getBundleExtra(Constants.KEY_PASS_REPORT_VIA_PARAM);
        if (bundleExtra != null) {
            m.a(bundleExtra, z ? "0" : "1");
            h.a().a(bundleExtra, this.d, intent.getBooleanExtra(Constants.KEY_PASS_REPORT_VIA_TIMELY, false));
        }
    }

    @Override
    protected void onStart() {
        try {
            logToBg("openSDK_LOG.AssistActivity", "-->onStart");
            super.onStart();
        } catch (Throwable t) {
            appendBgError("onStart 异常", t);
        }
    }

    @Override
    protected void onResume() {
        try {
            doOnResume();
        } catch (Throwable t) {
            appendBgError("onResume 异常", t);
        }
    }

    private void doOnResume() {
        logToBg("openSDK_LOG.AssistActivity", "-->onResume");
        super.onResume();
        Intent intent = getIntent();
        if (intent.getBooleanExtra("is_login", false)) {
            return;
        }
        if (!intent.getBooleanExtra("is_qq_mobile_share", false) && this.c && !isFinishing()) {
            promptFinish("onResume 中条件满足（非登录分享 + 重启标记），触发 finish");
        }
        if (this.a) {
            this.b.sendMessage(this.b.obtainMessage(0));
        } else {
            this.a = true;
        }
    }

    @Override
    protected void onPause() {
        try {
            logToBg("openSDK_LOG.AssistActivity", "-->onPause");
            this.b.removeMessages(0);
            super.onPause();
        } catch (Throwable t) {
            appendBgError("onPause 异常", t);
        }
    }

    @Override
    protected void onStop() {
        try {
            doOnStop();
        } catch (Throwable t) {
            appendBgError("onStop 异常", t);
        }
    }

    private void doOnStop() {
        logToBg("openSDK_LOG.AssistActivity", "-->onStop");
        super.onStop();
        if (Tencent.disableResetOrientation) {
            return;
        }
        try {
            int intExtra = getIntent().getIntExtra(KEY_REQUEST_ORIENTATION, -1);
            logToBg("openSDK_LOG.AssistActivity", "getRequestedOrientation= " + intExtra);
            if (intExtra != -1) {
                setRequestedOrientation(intExtra);
            }
        } catch (Throwable th) {
            try { SLog.e("openSDK_LOG.AssistActivity", "reset requestedOrientation catch exception", th); } catch (Throwable ignored) {}
            appendBgError("onStop.resetRequestedOrientation 异常", th);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            logToBg("openSDK_LOG.AssistActivity", "-->onDestroy");
            stopBgLogcat();
            super.onDestroy();
            QQStayReceiver qQStayReceiver = this.e;
            if (qQStayReceiver != null) {
                try { unregisterReceiver(qQStayReceiver); } catch (Throwable ignored) {}
            }
            appendBgLine("=== AssistActivity 已销毁 ===");
        } catch (Throwable t) {
            appendBgError("onDestroy 异常", t);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        try {
            doOnNewIntent(intent);
        } catch (Throwable t) {
            appendBgError("onNewIntent 异常", t);
        }
    }

    private void doOnNewIntent(Intent intent) {
        logToBg("openSDK_LOG.AssistActivity", "--onNewIntent");
        super.onNewIntent(intent);
        int intExtra = intent.getIntExtra("key_request_code", -1);
        logToBg("openSDK_LOG.AssistActivity", "--onNewIntent callbackRequestCode= " + intExtra);
        if (intExtra == 10108) {
            intent.putExtra("key_action", "action_request_avatar");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) return;
            promptFinish("onNewIntent key_request_code=10108 (action_request_avatar)");
            return;
        }
        if (intExtra == 10109) {
            intent.putExtra("key_action", "action_request_set_emotion");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) return;
            promptFinish("onNewIntent key_request_code=10109 (action_request_set_emotion)");
            return;
        }
        if (intExtra == 10110) {
            intent.putExtra("key_action", "action_request_dynamic_avatar");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) return;
            promptFinish("onNewIntent key_request_code=10110 (action_request_dynamic_avatar)");
            return;
        }
        if (intExtra == 10111) {
            intent.putExtra("key_action", "joinGroup");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) return;
            promptFinish("onNewIntent key_request_code=10111 (joinGroup)");
            return;
        }
        if (intExtra == 10112) {
            intent.putExtra("key_action", "bindGroup");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) return;
            promptFinish("onNewIntent key_request_code=10112 (bindGroup)");
            return;
        }
        if (intExtra == 10113) {
            intent.putExtra("key_action", intent.getStringExtra("action"));
            setResult(-1, intent);
            if (isFinishing()) return;
            promptFinish("onNewIntent key_request_code=10113");
            return;
        }
        if (intExtra == 10114) {
            intent.putExtra("key_action", intent.getStringExtra("action"));
            setResult(-1, intent);
            if (isFinishing()) return;
            promptFinish("onNewIntent key_request_code=10114");
            return;
        }
        intent.putExtra("key_action", "action_share");
        setResult(-1, intent);
        if (isFinishing()) return;
        logToBg("openSDK_LOG.AssistActivity", "--onNewIntent--activity not finished, finish now");
        promptFinish("onNewIntent 默认分支 (action_share)");
    }

    @Override
    protected void onSaveInstanceState(Bundle bundle) {
        try {
            logToBg("openSDK_LOG.AssistActivity", "--onSaveInstanceState--");
            bundle.putBoolean("RESTART_FLAG", true);
            bundle.putBoolean("RESUME_FLAG", this.a);
            super.onSaveInstanceState(bundle);
        } catch (Throwable t) {
            appendBgError("onSaveInstanceState 异常", t);
        }
    }

    @Override
    protected void onActivityResult(int i, int i2, Intent intent) {
        try {
            doOnActivityResult(i, i2, intent);
        } catch (Throwable t) {
            appendBgError("onActivityResult 异常", t);
        }
    }

    private void doOnActivityResult(int i, int i2, Intent intent) {
        StringBuilder sb = new StringBuilder();
        sb.append("--onActivityResult--requestCode: ");
        sb.append(i);
        sb.append(" | resultCode: ");
        sb.append(i2);
        sb.append("data = null ? ");
        sb.append(intent == null);
        logToBg("openSDK_LOG.AssistActivity", sb.toString());
        super.onActivityResult(i, i2, intent);
        if (i == 0) {
            return;
        }
        if (intent != null) {
            intent.putExtra("key_action", "action_login");
        }
        setResultData(i, intent);
        if (!this.f) {
            logToBg("openSDK_LOG.AssistActivity", "onActivityResult finish immediate");
            promptFinish("onActivityResult 立即 finish (requestCode=" + i + ")");
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        logToBg("openSDK_LOG.AssistActivity", "onActivityResult finish delay");
                        AssistActivity.this.promptFinish("onActivityResult 延时 finish");
                    } catch (Throwable t) {
                        appendBgError("onActivityResult.delayed 异常", t);
                    }
                }
            }, 200L);
        }
    }

    public void setResultData(int i, Intent intent) {
        try {
            doSetResultData(i, intent);
        } catch (Throwable t) {
            appendBgError("setResultData 异常", t);
        }
    }

    private void doSetResultData(int i, Intent intent) {
        if (intent == null) {
            logToBg("openSDK_LOG.AssistActivity", "--setResultData--intent is null, setResult ACTIVITY_CANCEL");
            setResult(0);
            if (i == 11101) {
                com.wunelezi.injector.tencent.open.b.e.a().a("", this.d, "2", "1", "7", "2");
                return;
            }
            return;
        }
        try {
            String stringExtra = intent.getStringExtra("key_response");
            logToBg("openSDK_LOG.AssistActivity", "--setResultDataForLogin-- ");
            if (!TextUtils.isEmpty(stringExtra)) {
                JSONObject jSONObject = new JSONObject(stringExtra);
                String optString = jSONObject.optString("openid");
                String optString2 = jSONObject.optString("access_token");
                String optString3 = jSONObject.optString("proxy_code");
                long optLong = jSONObject.optLong("proxy_expires_in");
                if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                    logToBg("openSDK_LOG.AssistActivity", "--setResultData--openid and token not empty, setResult ACTIVITY_OK");
                    setResult(-1, intent);
                    com.wunelezi.injector.tencent.open.b.e.a().a(optString, this.d, "2", "1", "7", "0");
                } else if (!TextUtils.isEmpty(optString3) && optLong != 0) {
                    logToBg("openSDK_LOG.AssistActivity", "--setResultData--proxy_code and proxy_expires_in are valid");
                    setResult(-1, intent);
                } else {
                    logToBg("openSDK_LOG.AssistActivity", "--setResultData--openid or token is empty, setResult ACTIVITY_CANCEL");
                    setResult(0, intent);
                    com.wunelezi.injector.tencent.open.b.e.a().a("", this.d, "2", "1", "7", "1");
                }
            } else {
                logToBg("openSDK_LOG.AssistActivity", "--setResultData--response is empty, setResult ACTIVITY_OK");
                setResult(-1, intent);
            }
        } catch (Throwable e) {
            try { SLog.e("openSDK_LOG.AssistActivity", "--setResultData--parse response failed"); } catch (Throwable ignored) {}
            appendBgError("setResultData.parseResponse 异常", e);
            try { e.printStackTrace(); } catch (Throwable ignored) {}
        }
    }

    private void a(Bundle bundle) {
        try {
            doABundle(bundle);
        } catch (Throwable t) {
            appendBgError("a(Bundle) 异常", t);
        }
    }

    private void doABundle(Bundle bundle) {
        String str;
        String str2;
        String str3;
        String string = bundle.getString("viaShareType");
        String string2 = bundle.getString("callbackAction");
        String string3 = bundle.getString("url");
        String string4 = bundle.getString("openId");
        String string5 = bundle.getString("appId");
        String str4 = "";
        if ("shareToQQ".equals(string2)) {
            str2 = "ANDROIDQQ.SHARETOQQ.XX";
            str3 = "10";
        } else {
            if (!"shareToQzone".equals(string2)) {
                str = "";
                if (m.a(this, string3)) {
                    IUiListener listnerWithAction = UIListenerManager.getInstance().getListnerWithAction(string2);
                    if (listnerWithAction != null) {
                        listnerWithAction.onError(new UiError(-6, "打开浏览器失败!", (String) null));
                    }
                    com.wunelezi.injector.tencent.open.b.e.a().a(string4, string5, str4, str, "3", "1", string, "0", "2", "0");
                    promptFinish("a(Bundle) 打开浏览器失败，触发 finish");
                } else {
                    com.wunelezi.injector.tencent.open.b.e.a().a(string4, string5, str4, str, "3", "0", string, "0", "2", "0");
                }
                getIntent().removeExtra("shareH5");
            }
            str2 = "ANDROIDQQ.SHARETOQZ.XX";
            str3 = "11";
        }
        str = str3;
        str4 = str2;
        if (m.a(this, string3)) {
        }
        getIntent().removeExtra("shareH5");
    }

    /**
     * 把 Intent / PendingIntent 关键字段文本化（直接返回字符串，便于日志写入）。
     */
    private String describeIntent(Intent intent, PendingIntent pendingIntent) {
        StringBuilder sb = new StringBuilder();
        if (intent == null) {
            sb.append("intent == null");
        } else {
            ComponentName cn = intent.getComponent();
            sb.append("Component: ").append(cn != null ? cn.flattenToString() : "(无)").append(" | ");
            if (intent.getAction() != null) {
                sb.append("Action: ").append(intent.getAction()).append(" | ");
            }
            Uri data = intent.getData();
            if (data != null) {
                sb.append("Data: ").append(data.toString()).append(" | ");
            }
            String type = intent.getType();
            if (type != null) {
                sb.append("Type: ").append(type).append(" | ");
            }
            if (intent.getCategories() != null && !intent.getCategories().isEmpty()) {
                sb.append("Categories: ").append(intent.getCategories()).append(" | ");
            }
            Bundle extras = intent.getExtras();
            if (extras != null && !extras.isEmpty()) {
                sb.append("Extras: {");
                for (String key : extras.keySet()) {
                    Object v;
                    try {
                        v = extras.get(key);
                    } catch (Throwable t) {
                        v = "(get 异常: " + t.getMessage() + ")";
                    }
                    sb.append(key).append("=");
                    if (v == null) {
                        sb.append("null,");
                    } else {
                        sb.append(v.toString()).append(",");
                    }
                }
                sb.append("} | ");
            }
            sb.append("Flags: 0x").append(Integer.toHexString(intent.getFlags()));
        }
        if (pendingIntent != null) {
            sb.append(" || PendingIntent:");
            try {
                sb.append(" creatorPackage=").append(pendingIntent.getCreatorPackage());
            } catch (Throwable ignored) {}
            try {
                sb.append(" creatorUid=").append(pendingIntent.getCreatorUid());
            } catch (Throwable ignored) {}
            sb.append(" isActivity=").append(pendingIntent.isActivity());
            sb.append(" isBroadcast=").append(pendingIntent.isBroadcast());
            sb.append(" isService=").append(pendingIntent.isService());
        }
        return sb.toString();
    }

    private class QQStayReceiver extends BroadcastReceiver {
        private QQStayReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                doOnReceive(context, intent);
            } catch (Throwable t) {
                appendBgError("QQStayReceiver.onReceive 异常", t);
            }
        }

        private void doOnReceive(Context context, Intent intent) {
            String str = "#";
            Intent intent2 = new Intent();
            intent2.putExtra("key_action", "action_share");
            try {
                Uri uri = (Uri) intent.getParcelableExtra("uriData");
                String uri2 = uri.toString();
                if (!uri2.contains("#")) {
                    str = "?";
                }
                for (String str2 : uri2.substring(uri2.indexOf(str) + 1).split("&")) {
                    String[] split = str2.split("=");
                    intent2.putExtra(split[0], split[1]);
                }
                intent2.setData(uri);
            } catch (Throwable e) {
                appendBgError("QQStayReceiver.parseUri 异常", e);
                intent2.putExtra("result", "error");
                intent2.putExtra("response", "parse error.");
            }
            AssistActivity.this.setResult(-1, intent2);
        }
    }
}
