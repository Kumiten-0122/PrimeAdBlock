package nagi.adskip.primevideo;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {

    private static final String PRIME_VIDEO_PACKAGE = "com.amazon.avod.thirdpartyclient";

    private static final long SKIP_DELAY_MS = 100;
    private static final long RETRY_DELAY_MS = 100;
    private static final long MAX_SKIP_DURATION_MS = 5000;
    private static final int MAX_SEEK_RETRIES = 20;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PRIME_VIDEO_PACKAGE.equals(lpparam.packageName)) return;

        log("PrimeVideoAdSkip: loaded");

        hookAdClipState(lpparam.classLoader);
    }

    private void hookAdClipState(final ClassLoader classLoader) {
        try {
            final Class<?> adClipStateClass = XposedHelpers.findClass(
                "com.amazon.avod.media.ads.internal.state.AdClipState",
                classLoader
            );

            final Class<?> triggerClass = XposedHelpers.findClass(
                "com.amazon.avod.fsm.Trigger",
                classLoader
            );

            XposedHelpers.findAndHookMethod(
                adClipStateClass,
                "enter",
                triggerClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        try {
                            handleAdClipEnter(param.thisObject, param.args[0]);
                        } catch (Throwable t) {
                            logError("AdClipState.enter hook failed", t);
                        }
                    }
                }
            );

            log("PrimeVideoAdSkip: AdClipState.enter hooked");

        } catch (Throwable t) {
            logError("Failed to hook AdClipState", t);
        }
    }

    private void handleAdClipEnter(final Object adClipState, final Object trigger) {
        if (trigger == null) return;

        final Object triggerType = XposedHelpers.callMethod(trigger, "getType");
        if (triggerType == null) return;

        if (!"NEXT_AD_CLIP_SERVER_INSERTED".equals(triggerType.toString())) return;

        final Object context = XposedHelpers.callMethod(adClipState, "getContext");
        if (context == null) return;

        final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

        if (currentAdClip == null) {
            log("currentAdClip == null");
            return;
        }

        final String adId = getAdId(currentAdClip);

        log("Ad clip detected: " + adId);

        scheduleSkip(context, currentAdClip, System.currentTimeMillis(), 0);
    }

    private void scheduleSkip(final Object context, final Object targetAdClip, final long startTime, final int attempt) {
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    if (System.currentTimeMillis() - startTime >= MAX_SKIP_DURATION_MS) {
                        log("Ad skip timeout: " + getAdId(targetAdClip));
                        return;
                    }

                    final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                    if (currentAdClip != null) {
                        if (currentAdClip != targetAdClip) {
                            log("Ad skip completed: ad clip changed");
                            return;
                        }

                        final Object stateMachine = XposedHelpers.callMethod(context, "getStateMachine");

                        if (stateMachine == null) {
                            log("stateMachine == null");
                            scheduleSkip(context, targetAdClip, startTime, attempt);
                            return;
                        }

                        log("Ad skip continuing: currentAdClip != null, elapsed=" + (System.currentTimeMillis() - startTime) + "ms");
                        log("skipCurrentAdClip(): " + getAdId(targetAdClip));

                        XposedHelpers.callMethod(stateMachine, "skipCurrentAdClip");

                        scheduleSeekVerification(context, targetAdClip, startTime, attempt);

                        return;
                    }

                    log("Ad skip completed: currentAdClip == null");

                    scheduleSeekVerification(context, targetAdClip, startTime, attempt);

                } catch (Throwable t) {
                    logError("Ad skip attempt failed", t);
                    scheduleSkip(context, targetAdClip, startTime, attempt + 1);
                }
            }
        }, SKIP_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void scheduleSeekVerification(final Object context, final Object targetAdClip, final long startTime, final int attempt) {
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    if (System.currentTimeMillis() - startTime >= MAX_SKIP_DURATION_MS) {
                        log("Seek verification timeout: " + getAdId(targetAdClip));
                        return;
                    }

                    final Object primaryPlayer = XposedHelpers.callMethod(context, "getPrimaryPlayer");

                    if (primaryPlayer == null) {
                        log("primaryPlayer == null");
                        scheduleSeekVerification(context, targetAdClip, startTime, attempt + 1);
                        return;
                    }

                    final long targetPosition = getAdEndPosition(targetAdClip);

                    final long currentPosition = getCurrentPosition(primaryPlayer);

                    log("Seek verification: current=" + currentPosition + ", target=" + targetPosition + ", attempt=" + attempt);

                    if (isAtOrPastTarget(currentPosition, targetPosition)) {
                        log("Ad skip verified: " + getAdId(targetAdClip));
                        return;
                    }

                    if (attempt >= MAX_SEEK_RETRIES) {
                        log("Seek retry limit reached: " + getAdId(targetAdClip));
                        return;
                    }

                    log("Ad skip seek retry: " + getAdId(targetAdClip));

                    seekToAdEnd(primaryPlayer, targetPosition);

                    scheduleSeekVerification(context, targetAdClip, startTime, attempt + 1);

                } catch (Throwable t) {
                    logError("Seek verification failed", t);
                    scheduleSeekVerification(context, targetAdClip, startTime, attempt + 1);
                }
            }
        }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private long getAdEndPosition(final Object adClip) {
        final Object startTime = XposedHelpers.callMethod(adClip, "getAdClipStartTime");
        final Object duration = XposedHelpers.callMethod(adClip, "getDuration");

        final long startMillis = ((Number) XposedHelpers.callMethod(startTime, "getTotalMilliseconds")).longValue();
        final long durationMillis = ((Number) XposedHelpers.callMethod(duration, "getTotalMilliseconds")).longValue();

        return startMillis + durationMillis;
    }

    private long getCurrentPosition(final Object primaryPlayer) {
        final Object position = XposedHelpers.callMethod(primaryPlayer, "getCurrentPosition");
        return ((Number) position).longValue();
    }

    private void seekToAdEnd(final Object primaryPlayer, final long targetPosition) throws Throwable {
        final Class<?> seekCauseClass = findSeekCauseClass(primaryPlayer.getClass());

        if (seekCauseClass == null) {
            throw new ClassNotFoundException("SeekAction.SeekCause not found");
        }

        final Object adSkipCause = Enum.valueOf(
            (Class<? extends Enum>) seekCauseClass,
            "AD_SKIP"
        );

        XposedHelpers.callMethod(
            primaryPlayer,
            "seekToManifestPosition",
            targetPosition,
            adSkipCause
        );
    }

    private Class<?> findSeekCauseClass(final Class<?> playerClass) {
        Class<?> current = playerClass;

        while (current != null) {
            for (Class<?> nested : current.getDeclaredClasses()) {
                if ("SeekCause".equals(nested.getSimpleName())) return nested;

                if ("SeekAction".equals(nested.getSimpleName())) {
                    for (Class<?> child : nested.getDeclaredClasses()) {
                        if ("SeekCause".equals(child.getSimpleName())) return child;
                    }
                }
            }

            current = current.getSuperclass();
        }

        try {
            return XposedHelpers.findClass(
                "com.amazon.avod.media.player.SeekAction$SeekCause",
                playerClass.getClassLoader()
            );
        } catch (Throwable ignored) {
        }

        return null;
    }

    private boolean isAtOrPastTarget(final long currentPosition, final long targetPosition) {
        return currentPosition >= targetPosition - 1000;
    }

    private String getAdId(final Object adClip) {
        if (adClip == null) return "null";

        try {
            final Object adId = XposedHelpers.callMethod(adClip, "getAdId");
            return String.valueOf(adId);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private void log(final String message) {
        XposedBridge.log("PrimeVideoAdSkip: " + message);
    }

    private void logError(final String message, final Throwable t) {
        XposedBridge.log("PrimeVideoAdSkip: " + message + " " + Log.getStackTraceString(t));
    }
}
