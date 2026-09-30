package nagi.adskip.primevideo;

import android.util.Log;

import java.lang.reflect.Field;
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
    private static final long VERIFY_INTERVAL_MS = 100;
    private static final long VERIFY_TIMEOUT_MS = 1000;
    private static final long POSITION_TOLERANCE_MS = 1000;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private boolean playerFieldsLogged = false;

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

        log("Ad clip detected: " + getAdId(currentAdClip));

        scheduleSkip(context, currentAdClip, System.currentTimeMillis());
    }

    private void scheduleSkip(final Object context, final Object targetAdClip, final long startTime) {
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                    if (currentAdClip == null) {
                        log("Ad skip request: currentAdClip == null");
                        startSeekVerification(context, targetAdClip, startTime);
                        return;
                    }

                    if (currentAdClip != targetAdClip) {
                        log("Ad skip completed: ad clip changed");
                        return;
                    }

                    final Object stateMachine = XposedHelpers.callMethod(context, "getStateMachine");

                    if (stateMachine == null) {
                        log("stateMachine == null");
                        return;
                    }

                    log("Ad skip continuing: currentAdClip != null");
                    log("skipCurrentAdClip(): " + getAdId(targetAdClip));

                    XposedHelpers.callMethod(stateMachine, "skipCurrentAdClip");

                    startSeekVerification(context, targetAdClip, startTime);

                } catch (Throwable t) {
                    logError("Ad skip attempt failed", t);
                }
            }
        }, SKIP_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void startSeekVerification(final Object context, final Object targetAdClip, final long startTime) {
        try {
            final long targetPosition = getAdEndPosition(targetAdClip);

            log("Seek verification started: target=" + targetPosition + ", adId=" + getAdId(targetAdClip));

            verifySeek(context, targetAdClip, targetPosition, startTime);

        } catch (Throwable t) {
            logError("Failed to start seek verification", t);
        }
    }

    private void verifySeek(final Object context, final Object targetAdClip, final long targetPosition, final long startTime) {
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    final long elapsed = System.currentTimeMillis() - startTime;

                    final Object primaryPlayer = XposedHelpers.callMethod(context, "getPrimaryPlayer");

                    if (primaryPlayer == null) {
                        log("Seek verification: primaryPlayer == null");
                        return;
                    }

                    final long logicalPosition = getLogicalPosition(primaryPlayer);

                    long rawPosition = -1;

                    try {
                        rawPosition = getRawPlayerPosition(primaryPlayer);
                    } catch (Throwable t) {
                        logError("Raw player position failed", t);
                    }

                    final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                    log(
                        "Seek verification: elapsed=" + elapsed +
                        "ms, logical=" + logicalPosition +
                        ", raw=" + rawPosition +
                        ", target=" + targetPosition +
                        ", currentAdClip=" + getAdId(currentAdClip)
                    );

                    if (logicalPosition >= targetPosition - POSITION_TOLERANCE_MS) {
                        log(
                            "Ad skip verified: " + getAdId(targetAdClip) +
                            ", logical=" + logicalPosition +
                            ", raw=" + rawPosition
                        );
                        return;
                    }

                    if (elapsed >= VERIFY_TIMEOUT_MS) {
                        log(
                            "Ad skip FAILED: " + getAdId(targetAdClip) +
                            ", logical=" + logicalPosition +
                            ", raw=" + rawPosition +
                            ", target=" + targetPosition +
                            ", currentAdClip=" + getAdId(currentAdClip)
                        );
                        return;
                    }

                    verifySeek(context, targetAdClip, targetPosition, startTime);

                } catch (Throwable t) {
                    logError("Seek verification failed", t);
                }
            }
        }, VERIFY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private long getLogicalPosition(final Object primaryPlayer) {
        final Object position = XposedHelpers.callMethod(primaryPlayer, "getCurrentPosition");
        return ((Number) position).longValue();
    }

    private long getRawPlayerPosition(final Object primaryPlayer) throws Throwable {
        if (!playerFieldsLogged) {
            logPlayerFields(primaryPlayer);
            playerFieldsLogged = true;
        }

        final Object rawPlayer = findPlayerObject(primaryPlayer);

        if (rawPlayer == null) {
            log("Raw player: underlying player not found");
            return -1;
        }

        log("Raw player object=" + rawPlayer.getClass().getName());

        final Object position = XposedHelpers.callMethod(rawPlayer, "getCurrentPosition");
        return ((Number) position).longValue();
    }

    private Object findPlayerObject(final Object primaryPlayer) throws Throwable {
        Class<?> clazz = primaryPlayer.getClass();

        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);

                final String name = field.getName();
                final String type = field.getType().getName();

                if ("mPlayer".equals(name)) {
                    final Object value = field.get(primaryPlayer);

                    if (value != null) {
                        log("Found player field: " + clazz.getName() + "." + name + " -> " + value.getClass().getName());
                        return value;
                    }
                }

                if (name.toLowerCase().contains("player") || type.toLowerCase().contains("player")) {
                    try {
                        final Object value = field.get(primaryPlayer);

                        if (value != null && hasGetCurrentPosition(value)) {
                            log("Found candidate player field: " + clazz.getName() + "." + name + " -> " + value.getClass().getName());
                            return value;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            clazz = clazz.getSuperclass();
        }

        return null;
    }

    private boolean hasGetCurrentPosition(final Object object) {
        try {
            object.getClass().getMethod("getCurrentPosition");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void logPlayerFields(final Object primaryPlayer) {
        try {
            Class<?> clazz = primaryPlayer.getClass();

            log("PrimaryPlayer class=" + clazz.getName());

            while (clazz != null) {
                log("PrimaryPlayer fields in " + clazz.getName() + ":");

                for (Field field : clazz.getDeclaredFields()) {
                    log(
                        "  field=" + field.getName() +
                        ", type=" + field.getType().getName()
                    );
                }

                clazz = clazz.getSuperclass();
            }

        } catch (Throwable t) {
            logError("Failed to inspect PrimaryPlayer fields", t);
        }
    }

    private long getAdEndPosition(final Object adClip) {
        final Object startTime = XposedHelpers.callMethod(adClip, "getAdClipStartTime");
        final Object duration = XposedHelpers.callMethod(adClip, "getDuration");

        final long startMillis = ((Number) XposedHelpers.callMethod(startTime, "getTotalMilliseconds")).longValue();
        final long durationMillis = ((Number) XposedHelpers.callMethod(duration, "getTotalMilliseconds")).longValue();

        return startMillis + durationMillis;
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
