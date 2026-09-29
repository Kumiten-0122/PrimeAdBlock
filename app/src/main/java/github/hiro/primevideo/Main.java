package nagi.adskip.primevideo;

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
    private static final String PRIME_VIDEO_PACKAGE = "com.amazon.avod.thirdpartyclient";
    private static final String AD_CLIP_STATE = "com.amazon.avod.media.ads.internal.state.AdClipState";
    private static final String TRIGGER = "com.amazon.avod.fsm.Trigger";

    private static final int MAX_SKIP_ATTEMPTS = 46;
    private static final long SKIP_RETRY_DELAY_MS = 1;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PRIME_VIDEO_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Prime Video loaded");
        hookAdClipState(lpparam.classLoader);
    }

    private void hookAdClipState(final ClassLoader classLoader) {
        try {
            final Class<?> adClipStateClass = XposedHelpers.findClass(AD_CLIP_STATE, classLoader);
            final Class<?> triggerClass = XposedHelpers.findClass(TRIGGER, classLoader);

            XposedHelpers.findAndHookMethod(
                    adClipStateClass,
                    "enter",
                    triggerClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            try {
                                final Object trigger = param.args[0];
                                if (trigger == null) return;

                                final Object triggerType = XposedHelpers.callMethod(trigger, "getType");
                                if (triggerType == null) return;

                                if (!"NEXT_AD_CLIP_SERVER_INSERTED".equals(triggerType.toString())) {
                                    return;
                                }

                                final Object adClipState = param.thisObject;
                                final Object context = XposedHelpers.callMethod(adClipState, "getContext");

                                if (context == null) {
                                    log("getContext() == null");
                                    return;
                                }

                                final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                                if (currentAdClip == null) {
                                    log("currentAdClip == null");
                                    return;
                                }

                                final Object stateMachine = XposedHelpers.callMethod(context, "getStateMachine");

                                if (stateMachine == null) {
                                    log("stateMachine == null");
                                    return;
                                }

                                log("Ad clip detected: " + getAdId(currentAdClip));

                                scheduleSkip(context, stateMachine, currentAdClip, 1);

                            } catch (Throwable t) {
                                logError("AdClipState.enter hook failed", t);
                            }
                        }
                    }
            );

            log("AdClipState.enter hooked");

        } catch (Throwable t) {
            logError("Failed to hook AdClipState.enter", t);
        }
    }

    private void scheduleSkip(
            final Object context,
            final Object stateMachine,
            final Object targetAdClip,
            final int attempt) {

        executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    Object currentAdClip =
                            XposedHelpers.callMethod(context, "getCurrentAdClip");

                    if (currentAdClip == null) {
                        log("Ad skip completed: currentAdClip == null");
                        return;
                    }

                    if (currentAdClip != targetAdClip) {
                        log("Ad skip completed: ad clip changed");
                        return;
                    }

                    log("skipCurrentAdClip() attempt "
                            + attempt + "/" + MAX_SKIP_ATTEMPTS
                            + ": " + getAdId(targetAdClip));

                    XposedHelpers.callMethod(
                            stateMachine,
                            "skipCurrentAdClip");

                    if (attempt >= MAX_SKIP_ATTEMPTS) {
                        log("Ad skip retry limit reached: "
                                + getAdId(targetAdClip));
                        return;
                    }

                    executor.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Object latestAdClip =
                                        XposedHelpers.callMethod(
                                                context,
                                                "getCurrentAdClip");

                                if (latestAdClip == null) {
                                    log("Ad skip completed: currentAdClip == null");
                                    return;
                                }

                                if (latestAdClip != targetAdClip) {
                                    log("Ad skip completed: ad clip changed");
                                    return;
                                }

                                scheduleSkip(
                                        context,
                                        stateMachine,
                                        targetAdClip,
                                        attempt + 1);

                            } catch (Throwable t) {
                                logError("Ad skip retry check failed", t);
                            }
                        }
                    }, SKIP_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);

                } catch (Throwable t) {
                    logError("skipCurrentAdClip() failed", t);
                }
            }
        }, SKIP_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private String getAdId(final Object adClip) {
        if (adClip == null) return "null";

        try {
            Object adId = XposedHelpers.callMethod(adClip, "getAdId");
            return String.valueOf(adId);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static void log(final String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void logError(final String message, final Throwable throwable) {
        XposedBridge.log(TAG + ": " + message + "\n" +
                android.util.Log.getStackTraceString(throwable));
    }
}
