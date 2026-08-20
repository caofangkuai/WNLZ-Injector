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
import com.wunelezi.injector.tencent.log.SLog;
import com.wunelezi.injector.tencent.open.b.h;
import com.wunelezi.injector.tencent.connect.common.Constants;
import com.wunelezi.injector.tencent.tauth.IUiListener;
import com.wunelezi.injector.tencent.tauth.Tencent;
import com.wunelezi.injector.tencent.tauth.UiError;
import com.wunelezi.injector.tencent.tauth.UIListenerManager;
import com.wunelezi.injector.tencent.open.utils.m;
import org.json.JSONObject;

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
                    logDialog("openSDK_LOG.AssistActivity", "-->finish by timeout");
                    AssistActivity.this.promptFinish("超时触发 finish（timeout）");
                }
            } catch (Throwable t) {
                showErrorDialog("Handler.handleMessage 异常", t);
            }
        }
    };

    public static Intent getAssistActivityIntent(Context context) {
        return new Intent(context, (Class<?>) AssistActivity.class);
    }

    /**
     * 把原本的日志输出改成弹窗展示；同时保留 logcat 输出，方便调试。
     */
    private void logDialog(final String tag, final String msg) {
        SLog.i(tag, msg);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                new AlertDialog.Builder(AssistActivity.this)
                        .setTitle(tag)
                        .setMessage(msg)
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    /**
     * 通用异常弹窗：无论哪个方法里抛了 Throwable，都尽量用 dialog 反馈，
     * 避免崩溃导致 StartAnyWhere 流程中断后用户毫无线索。
     */
    private void showErrorDialog(final String title, final Throwable t) {
        if (t == null) return;
        SLog.e(title, "AssistActivity 捕获异常", t);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("异常类型: ").append(t.getClass().getSimpleName()).append("\n");
                sb.append("错误信息: ").append(t.getMessage()).append("\n\n");
                sb.append("堆栈:\n");
                StackTraceElement[] stack = t.getStackTrace();
                int n = Math.min(15, stack == null ? 0 : stack.length);
                for (int i = 0; i < n; i++) {
                    sb.append("  at ").append(stack[i]).append("\n");
                }
                new AlertDialog.Builder(AssistActivity.this)
                        .setTitle(title)
                        .setMessage(sb.toString())
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    /** Throwable 异常的弹窗便捷方法，message 为可选说明 */
    private void showErrorDialog(final String title, final String message, final Throwable t) {
        if (t == null) return;
        SLog.e(title, message, t);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                StringBuilder sb = new StringBuilder();
                if (message != null && !message.isEmpty()) {
                    sb.append(message).append("\n\n");
                }
                sb.append("异常类型: ").append(t.getClass().getSimpleName()).append("\n");
                sb.append("错误信息: ").append(t.getMessage()).append("\n\n");
                sb.append("堆栈:\n");
                StackTraceElement[] stack = t.getStackTrace();
                int n = Math.min(15, stack == null ? 0 : stack.length);
                for (int i = 0; i < n; i++) {
                    sb.append("  at ").append(stack[i]).append("\n");
                }
                new AlertDialog.Builder(AssistActivity.this)
                        .setTitle(title)
                        .setMessage(sb.toString())
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    /**
     * 不再主动 finish()，改为弹出提示，由用户点"确认关闭"才真正结束 Activity。
     */
    private void promptFinish(final String reason) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                new AlertDialog.Builder(AssistActivity.this)
                        .setTitle("finish() 被调用")
                        .setMessage(reason)
                        .setCancelable(false)
                        .setPositiveButton("确认关闭", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                AssistActivity.this.finish();
                            }
                        })
                        .show();
            }
        });
    }

    /**
     * 在 startIntentSender / startIntentSenderForResult 执行前弹 dialog，
     * 展示即将发起的 Intent / PendingIntent 详情，由用户点"执行"或"取消"。
     */
    private void showIntentDetailsDialog(final Intent intent, final PendingIntent pendingIntent,
                                         final Runnable onConfirm, final Runnable onCancel) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                final String details = describeIntent(intent, pendingIntent);
                new AlertDialog.Builder(AssistActivity.this)
                        .setTitle("Intent 详情（即将执行）")
                        .setMessage(details)
                        .setCancelable(false)
                        .setPositiveButton("执行", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (onConfirm != null) onConfirm.run();
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (onCancel != null) onCancel.run();
                            }
                        })
                        .show();
            }
        });
    }

    /**
     * 把 Intent 的关键字段（Component / Action / Data / Type / Categories / Extras / Flags）
     * 以及 PendingIntent 的 creatorPackage、flags 格式化成可读文本。
     */
    private String describeIntent(Intent intent, PendingIntent pendingIntent) {
        StringBuilder sb = new StringBuilder();
        if (intent == null) {
            sb.append("intent == null\n");
        } else {
            ComponentName cn = intent.getComponent();
            sb.append("Component: ").append(cn != null ? cn.flattenToString() : "(无)").append("\n");
            if (intent.getAction() != null) {
                sb.append("Action: ").append(intent.getAction()).append("\n");
            }
            Uri data = intent.getData();
            if (data != null) {
                sb.append("Data: ").append(data.toString()).append("\n");
            }
            String type = intent.getType();
            if (type != null) {
                sb.append("Type: ").append(type).append("\n");
            }
            if (intent.getCategories() != null && !intent.getCategories().isEmpty()) {
                sb.append("Categories: ").append(intent.getCategories()).append("\n");
            }
            Bundle extras = intent.getExtras();
            if (extras != null && !extras.isEmpty()) {
                sb.append("\nExtras:\n");
                for (String key : extras.keySet()) {
                    Object v;
                    try {
                        v = extras.get(key);
                    } catch (Throwable t) {
                        v = "(get 异常: " + t.getMessage() + ")";
                    }
                    sb.append("  ").append(key).append(" = ");
                    if (v == null) {
                        sb.append("null\n");
                    } else if (v instanceof Intent) {
                        sb.append("(Intent) ").append(((Intent) v).toString()).append("\n");
                    } else if (v instanceof Bundle) {
                        sb.append("(Bundle) ").append(((Bundle) v).toString()).append("\n");
                    } else if (v instanceof Uri) {
                        sb.append(v.toString()).append("\n");
                    } else if (v instanceof String[]) {
                        sb.append(java.util.Arrays.toString((String[]) v)).append("\n");
                    } else if (v instanceof boolean[]) {
                        sb.append(java.util.Arrays.toString((boolean[]) v)).append("\n");
                    } else if (v instanceof int[]) {
                        sb.append(java.util.Arrays.toString((int[]) v)).append("\n");
                    } else if (v instanceof long[]) {
                        sb.append(java.util.Arrays.toString((long[]) v)).append("\n");
                    } else if (v instanceof double[]) {
                        sb.append(java.util.Arrays.toString((double[]) v)).append("\n");
                    } else {
                        sb.append(v.toString()).append("\n");
                    }
                }
            }
            sb.append("\nFlags: 0x").append(Integer.toHexString(intent.getFlags()));
        }
        if (pendingIntent != null) {
            sb.append("\n\nPendingIntent:\n");
            try {
                sb.append("  creatorPackage: ").append(pendingIntent.getCreatorPackage()).append("\n");
            } catch (Throwable ignored) {
                sb.append("  creatorPackage: (获取失败)\n");
            }
            try {
                sb.append("  creatorUid: ").append(pendingIntent.getCreatorUid()).append("\n");
            } catch (Throwable ignored) {
                sb.append("  creatorUid: (获取失败)\n");
            }
            sb.append("  isActivity: ").append(pendingIntent.isActivity()).append("\n");
            sb.append("  isBroadcast: ").append(pendingIntent.isBroadcast()).append("\n");
            sb.append("  isService: ").append(pendingIntent.isService()).append("\n");
        }
        return sb.toString();
    }

    @Override
    protected void onCreate(Bundle bundle) {
        try {
            doOnCreate(bundle);
        } catch (Throwable t) {
            showErrorDialog("onCreate 异常", t);
        }
    }

    private void doOnCreate(Bundle bundle) {
        getWindow().addFlags(0x04000000);
        requestWindowFeature(1);
        super.onCreate(bundle);
        this.f = getIntent().getBooleanExtra(Constants.KEY_RESTORE_LANDSCAPE, false);
        logDialog("openSDK_LOG.AssistActivity", "--onCreate-- mRestoreLandscape=" + this.f);
        if (getIntent() == null) {
            logDialog("openSDK_LOG.AssistActivity", "-->onCreate--getIntent() returns null");
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
            logDialog("openSDK_LOG.AssistActivity", "is restart");
            return;
        }
        if (bundleExtra == null) {
            PendingIntent pendingIntent = (PendingIntent) getIntent().getParcelableExtra(KEY_EXTRA_PENDING_INTENT);
            if (intent != null && pendingIntent != null) {
                logDialog("openSDK_LOG.AssistActivity", "--onCreate--activityIntent not null, will start activity, reqcode = " + intExtra);
                try {
                    IntentFilter intentFilter = new IntentFilter("com.tencent.tauth.opensdk.SHARE_SUCCESS_AND_STAY_QQ_" + intent.getData().getQueryParameter("share_id"));
                    if (this.e == null) {
                        this.e = new QQStayReceiver();
                    }
                    registerReceiver(this.e, intentFilter, Context.RECEIVER_NOT_EXPORTED);
                } catch (Throwable t) {
                    showErrorDialog("onCreate.registerReceiver 异常", t);
                }
                try {
                    final IntentSender intentSender = pendingIntent.getIntentSender();
                    final Intent finalIntent = intent;
                    final int finalIntExtra = intExtra;
                    final PendingIntent finalPendingIntent = pendingIntent;
                    // 执行 startIntentSender* 前先弹 dialog 展示 intent 详情，
                    // 用户确认后才真正发起 IntentSender / PendingIntent 授权流
                    showIntentDetailsDialog(intent, pendingIntent, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (finalIntent.getBooleanExtra("for_result", true)) {
                                    startIntentSenderForResult(intentSender, finalIntExtra, null, 0, 0, 0);
                                } else {
                                    startIntentSender(intentSender, null, 0, 0, 0);
                                }
                                a(finalIntent, true);
                            } catch (Throwable t) {
                                showErrorDialog("startIntentSender* 异常", t);
                            }
                        }
                    }, new Runnable() {
                        @Override
                        public void run() {
                            logDialog("openSDK_LOG.AssistActivity", "用户取消执行 IntentSender");
                        }
                    });
                    return;
                } catch (ActivityNotFoundException e2) {
                    logDialog("openSDK_LOG.AssistActivity", "--onCreate--startActivity exception, ActivityNotFoundException : " + e2);
                    IUiListener listnerWithRequestCode = UIListenerManager.getInstance().getListnerWithRequestCode(intExtra);
                    if (listnerWithRequestCode != null) {
                        listnerWithRequestCode.onError(new UiError(-20, "手Q版本过低，请下载安装最新版手Q", ""));
                    }
                    a(intent, false);
                    return;
                } catch (Throwable e3) {
                    showErrorDialog("onCreate.startIntentSender 异常", e3);
                    promptFinish("onCreate 中启动目标 Activity 抛异常: " + e3.getMessage());
                    return;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("--onCreate--activityIntent or pendingIntent is null. activityIntent is null? ");
            sb.append(intent == null);
            sb.append(", pendingIntent is null? ");
            sb.append(pendingIntent == null);
            logDialog("openSDK_LOG.AssistActivity", sb.toString());
            promptFinish("onCreate 中 activityIntent 或 pendingIntent 为 null，无法继续");
            return;
        }
        logDialog("openSDK_LOG.AssistActivity", "--onCreate--h5 bundle not null, will open browser");
        a(bundleExtra);
    }

    private void a(Intent intent, boolean z) {
        try {
            doA(intent, z);
        } catch (Throwable t) {
            showErrorDialog("a(Intent, boolean) 异常", t);
        }
    }

    private void doA(Intent intent, boolean z) {
        if (intent == null) {
            logDialog("openSDK_LOG.AssistActivity", "reportStartActivitySuccess, but intent is null.");
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
            logDialog("openSDK_LOG.AssistActivity", "-->onStart");
            super.onStart();
        } catch (Throwable t) {
            showErrorDialog("onStart 异常", t);
        }
    }

    @Override
    protected void onResume() {
        try {
            doOnResume();
        } catch (Throwable t) {
            showErrorDialog("onResume 异常", t);
        }
    }

    private void doOnResume() {
        logDialog("openSDK_LOG.AssistActivity", "-->onResume");
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
            logDialog("openSDK_LOG.AssistActivity", "-->onPause");
            this.b.removeMessages(0);
            super.onPause();
        } catch (Throwable t) {
            showErrorDialog("onPause 异常", t);
        }
    }

    @Override
    protected void onStop() {
        try {
            doOnStop();
        } catch (Throwable t) {
            showErrorDialog("onStop 异常", t);
        }
    }

    private void doOnStop() {
        logDialog("openSDK_LOG.AssistActivity", "-->onStop");
        super.onStop();
        if (Tencent.disableResetOrientation) {
            return;
        }
        try {
            int intExtra = getIntent().getIntExtra(KEY_REQUEST_ORIENTATION, -1);
            logDialog("openSDK_LOG.AssistActivity", "getRequestedOrientation= " + intExtra);
            if (intExtra != -1) {
                setRequestedOrientation(intExtra);
            }
        } catch (Throwable th) {
            SLog.e("openSDK_LOG.AssistActivity", "reset requestedOrientation catch exception", th);
            showErrorDialog("onStop.resetRequestedOrientation 异常", th);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            logDialog("openSDK_LOG.AssistActivity", "-->onDestroy");
            super.onDestroy();
            QQStayReceiver qQStayReceiver = this.e;
            if (qQStayReceiver != null) {
                unregisterReceiver(qQStayReceiver);
            }
        } catch (Throwable t) {
            showErrorDialog("onDestroy 异常", t);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        try {
            doOnNewIntent(intent);
        } catch (Throwable t) {
            showErrorDialog("onNewIntent 异常", t);
        }
    }

    private void doOnNewIntent(Intent intent) {
        logDialog("openSDK_LOG.AssistActivity", "--onNewIntent");
        super.onNewIntent(intent);
        int intExtra = intent.getIntExtra("key_request_code", -1);
        logDialog("openSDK_LOG.AssistActivity", "--onNewIntent callbackRequestCode= " + intExtra);
        if (intExtra == 10108) {
            intent.putExtra("key_action", "action_request_avatar");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) {
                return;
            }
            promptFinish("onNewIntent key_request_code=10108 (action_request_avatar)");
            return;
        }
        if (intExtra == 10109) {
            intent.putExtra("key_action", "action_request_set_emotion");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) {
                return;
            }
            promptFinish("onNewIntent key_request_code=10109 (action_request_set_emotion)");
            return;
        }
        if (intExtra == 10110) {
            intent.putExtra("key_action", "action_request_dynamic_avatar");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) {
                return;
            }
            promptFinish("onNewIntent key_request_code=10110 (action_request_dynamic_avatar)");
            return;
        }
        if (intExtra == 10111) {
            intent.putExtra("key_action", "joinGroup");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) {
                return;
            }
            promptFinish("onNewIntent key_request_code=10111 (joinGroup)");
            return;
        }
        if (intExtra == 10112) {
            intent.putExtra("key_action", "bindGroup");
            if (intent.getBooleanExtra("stay_back_stack", false)) {
                moveTaskToBack(true);
            }
            setResult(-1, intent);
            if (isFinishing()) {
                return;
            }
            promptFinish("onNewIntent key_request_code=10112 (bindGroup)");
            return;
        }
        if (intExtra == 10113) {
            intent.putExtra("key_action", intent.getStringExtra("action"));
            setResult(-1, intent);
            if (isFinishing()) {
                return;
            }
            promptFinish("onNewIntent key_request_code=10113");
            return;
        }
        if (intExtra == 10114) {
            intent.putExtra("key_action", intent.getStringExtra("action"));
            setResult(-1, intent);
            if (isFinishing()) {
                return;
            }
            promptFinish("onNewIntent key_request_code=10114");
            return;
        }
        intent.putExtra("key_action", "action_share");
        setResult(-1, intent);
        if (isFinishing()) {
            return;
        }
        logDialog("openSDK_LOG.AssistActivity", "--onNewIntent--activity not finished, finish now");
        promptFinish("onNewIntent 默认分支 (action_share)");
    }

    @Override
    protected void onSaveInstanceState(Bundle bundle) {
        try {
            logDialog("openSDK_LOG.AssistActivity", "--onSaveInstanceState--");
            bundle.putBoolean("RESTART_FLAG", true);
            bundle.putBoolean("RESUME_FLAG", this.a);
            super.onSaveInstanceState(bundle);
        } catch (Throwable t) {
            showErrorDialog("onSaveInstanceState 异常", t);
        }
    }

    @Override
    protected void onActivityResult(int i, int i2, Intent intent) {
        try {
            doOnActivityResult(i, i2, intent);
        } catch (Throwable t) {
            showErrorDialog("onActivityResult 异常", t);
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
        logDialog("openSDK_LOG.AssistActivity", sb.toString());
        super.onActivityResult(i, i2, intent);
        if (i == 0) {
            return;
        }
        if (intent != null) {
            intent.putExtra("key_action", "action_login");
        }
        setResultData(i, intent);
        if (!this.f) {
            logDialog("openSDK_LOG.AssistActivity", "onActivityResult finish immediate");
            promptFinish("onActivityResult 立即 finish (requestCode=" + i + ")");
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        logDialog("openSDK_LOG.AssistActivity", "onActivityResult finish delay");
                        AssistActivity.this.promptFinish("onActivityResult 延时 finish");
                    } catch (Throwable t) {
                        showErrorDialog("onActivityResult.delayed 异常", t);
                    }
                }
            }, 200L);
        }
    }

    public void setResultData(int i, Intent intent) {
        try {
            doSetResultData(i, intent);
        } catch (Throwable t) {
            showErrorDialog("setResultData 异常", t);
        }
    }

    private void doSetResultData(int i, Intent intent) {
        if (intent == null) {
            logDialog("openSDK_LOG.AssistActivity", "--setResultData--intent is null, setResult ACTIVITY_CANCEL");
            setResult(0);
            if (i == 11101) {
                com.wunelezi.injector.tencent.open.b.e.a().a("", this.d, "2", "1", "7", "2");
                return;
            }
            return;
        }
        try {
            String stringExtra = intent.getStringExtra("key_response");
            logDialog("openSDK_LOG.AssistActivity", "--setResultDataForLogin-- ");
            if (!TextUtils.isEmpty(stringExtra)) {
                JSONObject jSONObject = new JSONObject(stringExtra);
                String optString = jSONObject.optString("openid");
                String optString2 = jSONObject.optString("access_token");
                String optString3 = jSONObject.optString("proxy_code");
                long optLong = jSONObject.optLong("proxy_expires_in");
                if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                    logDialog("openSDK_LOG.AssistActivity", "--setResultData--openid and token not empty, setResult ACTIVITY_OK");
                    setResult(-1, intent);
                    com.wunelezi.injector.tencent.open.b.e.a().a(optString, this.d, "2", "1", "7", "0");
                } else if (!TextUtils.isEmpty(optString3) && optLong != 0) {
                    logDialog("openSDK_LOG.AssistActivity", "--setResultData--proxy_code and proxy_expires_in are valid");
                    setResult(-1, intent);
                } else {
                    logDialog("openSDK_LOG.AssistActivity", "--setResultData--openid or token is empty, setResult ACTIVITY_CANCEL");
                    setResult(0, intent);
                    com.wunelezi.injector.tencent.open.b.e.a().a("", this.d, "2", "1", "7", "1");
                }
            } else {
                logDialog("openSDK_LOG.AssistActivity", "--setResultData--response is empty, setResult ACTIVITY_OK");
                setResult(-1, intent);
            }
        } catch (Throwable e) {
            SLog.e("openSDK_LOG.AssistActivity", "--setResultData--parse response failed");
            showErrorDialog("setResultData.parseResponse 异常", e);
            e.printStackTrace();
        }
    }

    private void a(Bundle bundle) {
        try {
            doABundle(bundle);
        } catch (Throwable t) {
            showErrorDialog("a(Bundle) 异常", t);
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

    private class QQStayReceiver extends BroadcastReceiver {
        private QQStayReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                doOnReceive(context, intent);
            } catch (Throwable t) {
                showErrorDialog("QQStayReceiver.onReceive 异常", t);
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
                showErrorDialog("QQStayReceiver.parseUri 异常", e);
                intent2.putExtra("result", "error");
                intent2.putExtra("response", "parse error.");
            }
            AssistActivity.this.setResult(-1, intent2);
        }
    }
}