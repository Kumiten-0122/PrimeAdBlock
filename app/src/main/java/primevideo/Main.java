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

    private static final String PRIME_PACKAGE = "com.amazon.avod.thirdpartyclient";

    private final ScheduledExecutorService mExecutor =
        Executors.newScheduledThreadPool(1);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PRIME_PACKAGE.equals(lpparam.packageName)) return;

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
                            final Object state = param.thisObject;
                            final Object trigger = param.args[0];

                            final Object triggerType = XposedHelpers.callMethod(
                                trigger,
                                "getType"
                            );

                            if (triggerType == null) return;

                            if (!"NEXT_AD_CLIP_SERVER_INSERTED".equals(
                                triggerType.toString()
                            )) {
                                return;
                            }

                            final Object context = XposedHelpers.callMethod(
                                state,
                                "getContext"
                            );

                            if (context == null) return;

                            final Object adClip = XposedHelpers.callMethod(
                                context,
                                "getCurrentAdClip"
                            );

                            if (adClip == null) return;

                            final Object primaryPlayer = XposedHelpers.callMethod(
                                context,
                                "getPrimaryPlayer"
                            );

                            if (primaryPlayer == null) return;

                            waitForPlayingAndSkip(
                                context,
                                primaryPlayer
                            );

                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

        } catch (Throwable ignored) {
        }
    }

    private void waitForPlayingAndSkip(
        final Object context,
        final Object primaryPlayer
    ) {
        final long startTime = System.currentTimeMillis();

        mExecutor.schedule(
            () -> checkPlayingAndSkip(
                context,
                primaryPlayer,
                startTime
            ),
            100L,
            TimeUnit.MILLISECONDS
        );
    }

    private void checkPlayingAndSkip(
        final Object context,
        final Object primaryPlayer,
        final long startTime
    ) {
        try {
            final Object currentAdClip = XposedHelpers.callMethod(
                context,
                "getCurrentAdClip"
            );

            if (currentAdClip == null) return;

            final Object playbackState = getPlaybackState(primaryPlayer);

            if (playbackState != null &&
                "Playing".equals(playbackState.toString())) {

                final Object stateMachine = XposedHelpers.callMethod(
                    context,
                    "getStateMachine"
                );

                if (stateMachine == null) return;

                XposedHelpers.callMethod(
                    stateMachine,
                    "skipCurrentAdClip"
                );

                return;
            }

            if (System.currentTimeMillis() - startTime >= 2000L) {
                return;
            }

            mExecutor.schedule(
                () -> checkPlayingAndSkip(
                    context,
                    primaryPlayer,
                    startTime
                ),
                50L,
                TimeUnit.MILLISECONDS
            );

        } catch (Throwable ignored) {
        }
    }

    private Object getPlaybackState(final Object primaryPlayer) {
        try {
            final Object playbackSession = XposedHelpers.getObjectField(
                primaryPlayer,
                "mPlaybackSession"
            );

            if (playbackSession == null) return null;

            final Object playbackEngine = XposedHelpers.callMethod(
                playbackSession,
                "getPlaybackEngine"
            );

            if (playbackEngine == null) return null;

            return XposedHelpers.callMethod(
                playbackEngine,
                "getPlaybackState"
            );

        } catch (Throwable ignored) {
            return null;
        }
    }
}
