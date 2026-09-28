package github.adskip.primevideo;

import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {
    private static final String TAG = "PrimeVideoAdSkip";
    private static final String AD_CLIP_STATE = "com.amazon.avod.media.ads.internal.state.AdClipState";
    private static final String TRIGGER = "com.amazon.avod.fsm.Trigger";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        hookAdClipState(lpparam.classLoader);
    }

    private void hookAdClipState(final ClassLoader classLoader) {
        try {
            final Class<?> adClipStateClass = XposedHelpers.findClassIfExists(AD_CLIP_STATE, classLoader);
            final Class<?> triggerClass = XposedHelpers.findClassIfExists(TRIGGER, classLoader);

            if (adClipStateClass == null || triggerClass == null) return;

            XposedHelpers.findAndHookMethod(adClipStateClass, "enter", triggerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    try {
                        final Object trigger = param.args[0];
                        if (trigger == null) return;

                        final Object triggerType = XposedHelpers.callMethod(trigger, "getType");
                        if (triggerType == null || !"NEXT_AD_CLIP_SERVER_INSERTED".equals(triggerType.toString())) return;

                        final Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                        if (context == null) return;

                        final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");
                        if (currentAdClip == null) return;

                        final Object stateMachine = XposedHelpers.callMethod(context, "getStateMachine");
                        if (stateMachine == null) return;

                        XposedBridge.log(TAG + ": Ad clip detected: " + getAdId(currentAdClip));

                        mainHandler.post(() -> {
                            try {
                                Object latestAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");
                                if (latestAdClip != currentAdClip) {
                                    XposedBridge.log(TAG + ": Ad clip changed before skip");
                                    return;
                                }

                                XposedHelpers.callMethod(stateMachine, "skipCurrentAdClip");
                                XposedBridge.log(TAG + ": skipCurrentAdClip(): " + getAdId(currentAdClip));
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": skipCurrentAdClip failed: " + android.util.Log.getStackTraceString(t));
                            }
                        });
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": enter hook failed: " + android.util.Log.getStackTraceString(t));
                    }
                }
            });

            XposedBridge.log(TAG + ": AdClipState.enter hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook initialization failed: " + android.util.Log.getStackTraceString(t));
        }
    }

    private String getAdId(Object adClip) {
        try {
            return String.valueOf(XposedHelpers.callMethod(adClip, "getAdId"));
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
