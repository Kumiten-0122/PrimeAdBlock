package nagi.adskip.primevideo;

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

    private static final String TAG = "PrimeVideoAdSkip";
    private static final long SKIP_DELAY_MS = 100;
    private static final long MAX_SKIP_DURATION_MS = 5000;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private Class<?> hookedSkipClass;
    private Class<?> hookedPlayerClass;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.amazon.avod.thirdpartyclient".equals(lpparam.packageName)) return;

        log("Prime Video loaded");
        hookAdClipState(lpparam.classLoader);
    }

    private void hookAdClipState(ClassLoader classLoader) {
        try {
            final Class<?> adClipStateClass = XposedHelpers.findClass(
                "com.amazon.avod.media.ads.internal.state.AdClipState",
                classLoader
            );

            final Class<?> triggerClass = XposedHelpers.findClass(
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

                        if ("NEXT_AD_CLIP_SERVER_INSERTED".equals(triggerType.toString())) {
                            final Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                            final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                            if (currentAdClip == null) {
                                log("currentAdClip == null");
                                return;
                            }

                            final String adId = getAdId(currentAdClip);
                            log("Ad clip detected: " + adId);

                            hookPrimaryPlayer(context);

                            scheduleSkip(context, currentAdClip, System.currentTimeMillis());
                        }
                    } catch (Throwable t) {
                        logError("AdClipState.enter hook failed", t);
                    }
                }
            });

            log("AdClipState hook installed");
        } catch (Throwable t) {
            logError("Failed to hook AdClipState", t);
        }
    }

    private void hookPrimaryPlayer(final Object context) {
        try {
            final Object primaryPlayer = XposedHelpers.callMethod(context, "getPrimaryPlayer");
            if (primaryPlayer == null) {
                log("PrimaryPlayer == null");
                return;
            }

            final Class<?> playerClass = primaryPlayer.getClass();

            if (hookedPlayerClass == playerClass) return;

            for (final Method method : playerClass.getMethods()) {
                if (!"seekToManifestPosition".equals(method.getName())) continue;
                if (method.getParameterTypes().length != 2) continue;

                method.setAccessible(true);

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            log("PRIMARY PLAYER SEEK: seekToManifestPosition()");

                            if (param.args.length > 0) {
                                log("PRIMARY PLAYER SEEK: target=" + String.valueOf(param.args[0]));
                            }

                            if (param.args.length > 1) {
                                log("PRIMARY PLAYER SEEK: cause=" + String.valueOf(param.args[1]));
                            }

                            try {
                                final Object position = XposedHelpers.callMethod(param.thisObject, "getCurrentPosition");
                                log("PRIMARY PLAYER SEEK: currentPosition(before)=" + String.valueOf(position));
                            } catch (Throwable t) {
                                logError("PRIMARY PLAYER SEEK: getCurrentPosition failed", t);
                            }
                        } catch (Throwable t) {
                            logError("PrimaryPlayer seek hook failed", t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            log("PRIMARY PLAYER SEEK: seekToManifestPosition() returned");

                            try {
                                final Object position = XposedHelpers.callMethod(param.thisObject, "getCurrentPosition");
                                log("PRIMARY PLAYER SEEK: currentPosition(after)=" + String.valueOf(position));
                            } catch (Throwable t) {
                                logError("PRIMARY PLAYER SEEK: getCurrentPosition after failed", t);
                            }
                        } catch (Throwable t) {
                            logError("PrimaryPlayer seek after hook failed", t);
                        }
                    }
                });

                hookedPlayerClass = playerClass;
                log("PrimaryPlayer seek hook installed: " + playerClass.getName());
                return;
            }

            log("seekToManifestPosition() not found: " + playerClass.getName());
        } catch (Throwable t) {
            logError("Failed to hook PrimaryPlayer", t);
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

                        hookStateMachineSkip(stateMachine);

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

    private void hookStateMachineSkip(final Object stateMachine) {
        try {
            final Class<?> stateMachineClass = stateMachine.getClass();

            if (hookedSkipClass == stateMachineClass) return;

            Method targetMethod = null;

            for (final Method method : stateMachineClass.getMethods()) {
                if (!"skipCurrentAdClip".equals(method.getName())) continue;
                if (method.getParameterTypes().length != 0) continue;

                targetMethod = method;
                break;
            }

            if (targetMethod == null) {
                log("skipCurrentAdClip() not found: " + stateMachineClass.getName());
                return;
            }

            targetMethod.setAccessible(true);

            XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        log("STATE MACHINE: skipCurrentAdClip() entered");

                        try {
                            final Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                            final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                            log("STATE MACHINE: currentAdClip=" + (currentAdClip == null ? "null" : getAdId(currentAdClip)));

                            if (currentAdClip != null) {
                                final Object startTime = XposedHelpers.callMethod(currentAdClip, "getAdClipStartTime");
                                final Object duration = XposedHelpers.callMethod(currentAdClip, "getDuration");

                                log("STATE MACHINE: adClipStartTime=" + String.valueOf(startTime));
                                log("STATE MACHINE: duration=" + String.valueOf(duration));
                            }
                        } catch (Throwable t) {
                            logError("STATE MACHINE: context inspection failed", t);
                        }
                    } catch (Throwable t) {
                        logError("StateMachine skip before hook failed", t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    log("STATE MACHINE: skipCurrentAdClip() returned");
                }
            });

            hookedSkipClass = stateMachineClass;
            log("StateMachine skip hook installed: " + stateMachineClass.getName());
        } catch (Throwable t) {
            logError("Failed to hook skipCurrentAdClip()", t);
        }
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
