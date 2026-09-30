package nagi.adskip.primevideo;

import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "PrimeVideoAdSkip";
    private static final long SKIP_DELAY_MS = 100;
    private static final long MAX_SKIP_DURATION_MS = 5000;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.amazon.avod.thirdpartyclient".equals(lpparam.packageName)) return;

        log("Prime Video loaded");

        hookAdClipState(lpparam.classLoader);
    }

    private void hookAdClipState(ClassLoader classLoader) {
        try {
            Class<?> adClipStateClass = XposedHelpers.findClass(
                "com.amazon.avod.media.ads.internal.state.AdClipState",
                classLoader
            );

            Class<?> triggerClass = XposedHelpers.findClass(
                "com.amazon.avod.fsm.Trigger",
                classLoader
            );

            XposedHelpers.findAndHookMethod(adClipStateClass, "enter", triggerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Object trigger = param.args[0];
                        if (trigger == null) return;

                        final Object triggerType = XposedHelpers.callMethod(trigger, "getType");
                        if (triggerType == null) return;

                        log("AdClipState.enter(): trigger=" + triggerType);

                        if ("NEXT_AD_CLIP_SERVER_INSERTED".equals(triggerType.toString())) {
                            final Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                            final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                            if (currentAdClip == null) {
                                log("currentAdClip == null");
                                return;
                            }

                            final String adId = getAdId(currentAdClip);
                            log("Ad clip detected: " + adId);
                            scheduleSkip(context, currentAdClip, System.currentTimeMillis());
                        }

                        if ("SEEK".equals(triggerType.toString())) {
                            log("AdClipState.enter(): SEEK");

                            final Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                            final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");
                            final Object primaryPlayer = XposedHelpers.callMethod(context, "getPrimaryPlayer");

                            log("SEEK enter: currentAdClip=" + (currentAdClip == null ? "null" : getAdId(currentAdClip)));
                            log("SEEK enter: primaryPlayer=" + (primaryPlayer == null ? "null" : primaryPlayer.getClass().getName()));

                            if (primaryPlayer != null) {
                                try {
                                    final Object position = XposedHelpers.callMethod(primaryPlayer, "getCurrentPosition");
                                    log("SEEK enter: primaryPlayer.getCurrentPosition()=" + position);
                                } catch (Throwable t) {
                                    logError("SEEK enter: getCurrentPosition failed", t);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        logError("AdClipState.enter hook failed", t);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(adClipStateClass, "exit", triggerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Object trigger = param.args[0];
                        if (trigger == null) return;

                        final Object triggerType = XposedHelpers.callMethod(trigger, "getType");
                        if (triggerType == null) return;

                        if ("SEEK".equals(triggerType.toString())) {
                            log("AdClipState.exit(): SEEK");

                            final Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                            final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");
                            final Object primaryPlayer = XposedHelpers.callMethod(context, "getPrimaryPlayer");

                            log("SEEK exit: currentAdClip=" + (currentAdClip == null ? "null" : getAdId(currentAdClip)));

                            if (primaryPlayer != null) {
                                try {
                                    final Object position = XposedHelpers.callMethod(primaryPlayer, "getCurrentPosition");
                                    log("SEEK exit: primaryPlayer.getCurrentPosition()=" + position);
                                } catch (Throwable t) {
                                    logError("SEEK exit: getCurrentPosition failed", t);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        logError("AdClipState.exit hook failed", t);
                    }
                }
            });

            log("AdClipState hooks installed");
        } catch (Throwable t) {
            logError("Failed to hook AdClipState", t);
        }
    }

    private void scheduleSkip(final Object context, final Object targetAdClip, final long startTime) {
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                    if (currentAdClip == null) {
                        log("Ad skip completed: currentAdClip == null");
                        return;
                    }

                    if (currentAdClip != targetAdClip) {
                        log("Ad skip completed: ad clip changed");
                        return;
                    }

                    if (currentAdClip != null) {
                        final long elapsed = System.currentTimeMillis() - startTime;

                        if (elapsed >= MAX_SKIP_DURATION_MS) {
                            log("Ad skip timeout: " + getAdId(targetAdClip));
                            return;
                        }

                        final Object stateMachine = XposedHelpers.callMethod(context, "getStateMachine");

                        if (stateMachine == null) {
                            log("stateMachine == null");
                            scheduleSkip(context, targetAdClip, startTime);
                            return;
                        }

                        log("Ad skip continuing: currentAdClip != null, elapsed=" + elapsed + "ms");
                        log("skipCurrentAdClip(): " + getAdId(targetAdClip));

                        XposedHelpers.callMethod(stateMachine, "skipCurrentAdClip");

                        scheduleSkip(context, targetAdClip, startTime);
                    }
                } catch (Throwable t) {
                    logError("Ad skip attempt failed", t);
                    scheduleSkip(context, targetAdClip, startTime);
                }
            }
        }, SKIP_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private String getAdId(Object adClip) {
        try {
            final Object adId = XposedHelpers.callMethod(adClip, "getAdId");
            return String.valueOf(adId);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private void logError(String message, Throwable t) {
        XposedBridge.log(TAG + ": " + message);
        XposedBridge.log(t);
    }
}
