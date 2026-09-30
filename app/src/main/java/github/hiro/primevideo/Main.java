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

    private static final String TAG = "PrimeAdSkip";
    private static final String PRIME_PACKAGE = "com.amazon.avod.thirdpartyclient";

    private final ScheduledExecutorService mExecutor = Executors.newScheduledThreadPool(1);

    private volatile Object mStateMachineContext;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PRIME_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Prime Video loaded");

        hookAdClipState(lpparam.classLoader);
        hookSeekEnd(lpparam.classLoader);
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
                            final Object state = param.thisObject;
                            final Object trigger = param.args[0];

                            final Object triggerType = XposedHelpers.callMethod(trigger, "getType");
                            if (triggerType == null) return;

                            if (!"NEXT_AD_CLIP_SERVER_INSERTED".equals(triggerType.toString())) return;

                            final Object context = XposedHelpers.callMethod(state, "getContext");
                            if (context == null) {
                                logError("Ad clip context is null");
                                return;
                            }

                            mStateMachineContext = context;

                            final Object adClip = XposedHelpers.callMethod(context, "getCurrentAdClip");
                            if (adClip == null) {
                                logError("Ad clip detected but currentAdClip is null");
                                return;
                            }

                            final String adId = String.valueOf(XposedHelpers.callMethod(adClip, "getAdId"));

                            XposedBridge.log(TAG + ": Ad clip detected: " + adId);

                            final Object primaryPlayer = XposedHelpers.callMethod(
                                context,
                                "getPrimaryPlayer"
                            );

                            if (primaryPlayer == null) {
                                logError("Primary player is null");
                                return;
                            }

                            final long adClipStartTime = getTimeSpanMilliseconds(
                                XposedHelpers.callMethod(adClip, "getAdClipStartTime")
                            );

                            final long duration = getTimeSpanMilliseconds(
                                XposedHelpers.callMethod(adClip, "getDuration")
                            );

                            final long targetPosition = adClipStartTime + duration;
                            final long startTime = System.currentTimeMillis();

                            scheduleSkip(
                                context,
                                adClip,
                                primaryPlayer,
                                adId,
                                targetPosition,
                                startTime
                            );

                        } catch (Throwable t) {
                            logError("AdClipState.enter hook failed: " + Log.getStackTraceString(t));
                        }
                    }
                }
            );

            XposedBridge.log(TAG + ": AdClipState.enter hooked");

        } catch (Throwable t) {
            logError("AdClipState hook failed: " + Log.getStackTraceString(t));
        }
    }

    private void scheduleSkip(final Object context, final Object targetAdClip, final Object primaryPlayer, final String adId, final long targetPosition, final long startTime) {
        mExecutor.schedule(() -> {
            try {
                final Object currentAdClip = XposedHelpers.callMethod(context, "getCurrentAdClip");

                if (currentAdClip == null) {
                    XposedBridge.log(TAG + ": Ad skip canceled: currentAdClip == null");
                    return;
                }

                final long elapsed = System.currentTimeMillis() - startTime;

                XposedBridge.log(
                    TAG + ": Ad skip continuing: currentAdClip != null, elapsed=" +
                    elapsed + "ms"
                );

                XposedBridge.log(TAG + ": skipCurrentAdClip(): " + adId);

                final Object stateMachine = XposedHelpers.callMethod(
                    context,
                    "getStateMachine"
                );

                if (stateMachine == null) {
                    logError("State machine is null");
                    return;
                }

                XposedBridge.log(TAG + ": STATE MACHINE: skipCurrentAdClip() entered");

                final Object stateMachineClip = XposedHelpers.callMethod(
                    context,
                    "getCurrentAdClip"
                );

                XposedBridge.log(
                    TAG + ": STATE MACHINE: currentAdClip=" +
                    String.valueOf(stateMachineClip)
                );

                if (stateMachineClip != null) {
                    XposedBridge.log(
                        TAG + ": STATE MACHINE: adClipStartTime=" +
                        String.valueOf(
                            XposedHelpers.callMethod(
                                stateMachineClip,
                                "getAdClipStartTime"
                            )
                        )
                    );

                    XposedBridge.log(
                        TAG + ": STATE MACHINE: duration=" +
                        String.valueOf(
                            XposedHelpers.callMethod(
                                stateMachineClip,
                                "getDuration"
                            )
                        )
                    );
                }

                XposedHelpers.callMethod(stateMachine, "skipCurrentAdClip");

                XposedBridge.log(TAG + ": STATE MACHINE: skipCurrentAdClip() returned");

                XposedBridge.log(
                    TAG + ": Seek verification started: target=" +
                    targetPosition + ", adId=" + adId
                );

                mExecutor.schedule(
                    () -> verifySeek(
                        context,
                        primaryPlayer,
                        adId,
                        targetPosition,
                        startTime
                    ),
                    100L,
                    TimeUnit.MILLISECONDS
                );

            } catch (Throwable t) {
                logError("Ad skip failed: " + Log.getStackTraceString(t));
            }
        }, 100L, TimeUnit.MILLISECONDS);
    }

    private void verifySeek(final Object context, final Object primaryPlayer, final String adId, final long targetPosition, final long startTime) {
        try {
            final long elapsed = System.currentTimeMillis() - startTime;

            final long logicalPosition = getLogicalPosition(primaryPlayer);
            final String cachedPosition = getCachedPosition(primaryPlayer);
            final String isSeeking = getIsSeeking(primaryPlayer);
            final String enginePosition = getEnginePosition(primaryPlayer);
            final String engineState = getEngineState(primaryPlayer);

            final Object currentAdClip = XposedHelpers.callMethod(
                context,
                "getCurrentAdClip"
            );

            XposedBridge.log(
                TAG + ": Seek verification: elapsed=" +
                elapsed +
                "ms, logical=" +
                logicalPosition +
                ", cached=" +
                cachedPosition +
                ", seeking=" +
                isSeeking +
                ", engine=" +
                enginePosition +
                ", engineState=" +
                engineState +
                ", currentAdClip=" +
                String.valueOf(currentAdClip)
            );

            if (isTargetReached(enginePosition, targetPosition)) {
                XposedBridge.log(
                    TAG + ": Ad skip verified: " +
                    adId +
                    ", logical=" +
                    logicalPosition +
                    ", engine=" +
                    enginePosition +
                    ", target=" +
                    targetPosition
                );
                return;
            }

            if (elapsed >= 2000L) {
                XposedBridge.log(
                    TAG + ": Ad skip verification timeout: " +
                    adId +
                    ", logical=" +
                    logicalPosition +
                    ", cached=" +
                    cachedPosition +
                    ", seeking=" +
                    isSeeking +
                    ", engine=" +
                    enginePosition +
                    ", engineState=" +
                    engineState +
                    ", currentAdClip=" +
                    String.valueOf(currentAdClip)
                );
                return;
            }

            mExecutor.schedule(
                () -> verifySeek(
                    context,
                    primaryPlayer,
                    adId,
                    targetPosition,
                    startTime
                ),
                100L,
                TimeUnit.MILLISECONDS
            );

        } catch (Throwable t) {
            logError("Seek verification failed: " + Log.getStackTraceString(t));
        }
    }

    private void hookSeekEnd(final ClassLoader classLoader) {
        try {
            final Class<?> playerClass = XposedHelpers.findClass(
                "com.amazon.avod.playback.session.AmazonVideoPlayer",
                classLoader
            );

            XposedHelpers.findAndHookMethod(
                playerClass,
                "onSeekEnd",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        try {
                            final Object player = param.thisObject;

                            XposedBridge.log(
                                TAG +
                                ": onSeekEnd: logical=" +
                                getLogicalPosition(player) +
                                ", cached=" +
                                getCachedPosition(player) +
                                ", engine=" +
                                getEnginePosition(player) +
                                ", seeking=" +
                                getIsSeeking(player) +
                                ", engineState=" +
                                getEngineState(player)
                            );

                        } catch (Throwable t) {
                            logError(
                                "onSeekEnd logging failed: " +
                                Log.getStackTraceString(t)
                            );
                        }
                    }
                }
            );

            XposedBridge.log(TAG + ": AmazonVideoPlayer.onSeekEnd hooked");

        } catch (Throwable t) {
            logError("onSeekEnd hook failed: " + Log.getStackTraceString(t));
        }
    }

    private long getLogicalPosition(final Object primaryPlayer) {
        try {
            final Object value = XposedHelpers.callMethod(
                primaryPlayer,
                "getCurrentPosition"
            );

            if (value instanceof Number) {
                return ((Number) value).longValue();
            }

            return -1L;

        } catch (Throwable t) {
            return -1L;
        }
    }

    private String getCachedPosition(final Object primaryPlayer) {
        try {
            final Object ref = XposedHelpers.getObjectField(
                primaryPlayer,
                "mCachedPosition"
            );

            if (ref == null) return "null";

            final Object value = XposedHelpers.callMethod(ref, "get");

            if (value == null) return "null";

            final Object millis = XposedHelpers.callMethod(
                value,
                "getTotalMilliseconds"
            );

            if (millis instanceof Number) {
                return String.valueOf(((Number) millis).longValue());
            }

            return String.valueOf(millis);

        } catch (Throwable t) {
            return "ERROR:" + t.getClass().getSimpleName();
        }
    }

    private String getIsSeeking(final Object primaryPlayer) {
        try {
            return String.valueOf(
                XposedHelpers.getBooleanField(
                    primaryPlayer,
                    "mIsSeeking"
                )
            );

        } catch (Throwable t) {
            return "ERROR:" + t.getClass().getSimpleName();
        }
    }

    private String getEnginePosition(final Object primaryPlayer) {
        try {
            final Object playbackSession = XposedHelpers.getObjectField(
                primaryPlayer,
                "mPlaybackSession"
            );

            if (playbackSession == null) return "null";

            final Object playbackEngine = XposedHelpers.callMethod(
                playbackSession,
                "getPlaybackEngine"
            );

            if (playbackEngine == null) return "null";

            final Object positionNanos = XposedHelpers.callMethod(
                playbackEngine,
                "getCurrentPositionInNanos"
            );

            if (!(positionNanos instanceof Number)) {
                return "unknown";
            }

            return String.valueOf(
                ((Number) positionNanos).longValue() / 1000000L
            );

        } catch (Throwable t) {
            return "ERROR:" + t.getClass().getSimpleName();
        }
    }

    private String getEngineState(final Object primaryPlayer) {
        try {
            final Object playbackSession = XposedHelpers.getObjectField(
                primaryPlayer,
                "mPlaybackSession"
            );

            if (playbackSession == null) return "null";

            final Object playbackEngine = XposedHelpers.callMethod(
                playbackSession,
                "getPlaybackEngine"
            );

            if (playbackEngine == null) return "null";

            final Object state = XposedHelpers.callMethod(
                playbackEngine,
                "getPlaybackState"
            );

            return String.valueOf(state);

        } catch (Throwable t) {
            return "ERROR:" + t.getClass().getSimpleName();
        }
    }

    private boolean isTargetReached(final String enginePosition, final long targetPosition) {
        try {
            if ("null".equals(enginePosition)) return false;
            if ("unknown".equals(enginePosition)) return false;
            if (enginePosition.startsWith("ERROR:")) return false;

            final long position = Long.parseLong(enginePosition);

            return position >= targetPosition - 1000L;

        } catch (Throwable t) {
            return false;
        }
    }

    private long getTimeSpanMilliseconds(final Object timeSpan) {
        if (timeSpan == null) return 0L;

        try {
            final Object value = XposedHelpers.callMethod(
                timeSpan,
                "getTotalMilliseconds"
            );

            if (value instanceof Number) {
                return ((Number) value).longValue();
            }

        } catch (Throwable ignored) {
        }

        return 0L;
    }

    private void logError(final String message) {
        XposedBridge.log(TAG + ": ERROR: " + message);
    }
}
