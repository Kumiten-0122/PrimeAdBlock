package nagi.adskip.primevideo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {

    private static final String PRIME_PACKAGE =
        "com.amazon.avod.thirdpartyclient";

    private static final String SERVER_INSERTED_AD_BREAK_STATE =
        "com.amazon.avod.media.ads.internal.state.ServerInsertedAdBreakState";

    private static final String NEXT_AD_CLIP_SERVER_INSERTED =
        "NEXT_AD_CLIP_SERVER_INSERTED";

    private static final String NO_MORE_ADS_SKIP_TRANSITION =
        "NO_MORE_ADS_SKIP_TRANSITION";

    private final ScheduledExecutorService mExecutor =
        Executors.newScheduledThreadPool(1);

    /*
     * シークで広告Breakへ入った場合だけ、
     * ServerInsertedAdBreakState.enter() の
     * getSeekTarget() を一時保存する。
     */
    private final ThreadLocal<Long> mPendingSeekTarget =
        new ThreadLocal<>();

    @Override
    public void handleLoadPackage(
        final XC_LoadPackage.LoadPackageParam lpparam
    ) throws Throwable {

        if (!PRIME_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        hookServerInsertedAdBreakState(lpparam.classLoader);
        hookBlockingStateMachine(lpparam.classLoader);
    }

    private void hookServerInsertedAdBreakState(
        final ClassLoader classLoader
    ) {
        try {
            final Class<?> triggerClass =
                XposedHelpers.findClass(
                    "com.amazon.avod.fsm.Trigger",
                    classLoader
                );

            XposedHelpers.findAndHookMethod(
                SERVER_INSERTED_AD_BREAK_STATE,
                classLoader,
                "enter",
                triggerClass,
                new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(
                        final MethodHookParam param
                    ) {
                        try {
                            final Object trigger = param.args[0];

                            if (trigger == null) {
                                return;
                            }

                            /*
                             * ここで取れるのは主に
                             * シーク経由のAdBreakTrigger。
                             *
                             * 通常再生ではseekTarget == nullなので
                             * 何もしない。
                             */
                            final Object seekTarget =
                                XposedHelpers.callMethod(
                                    trigger,
                                    "getSeekTarget"
                                );

                            if (seekTarget == null) {
                                return;
                            }

                            final long target =
                                getTimeSpanMilliseconds(
                                    seekTarget
                                );

                            if (target >= 0L) {
                                mPendingSeekTarget.set(target);
                            }

                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

        } catch (Throwable ignored) {
        }
    }

    private void hookBlockingStateMachine(
        final ClassLoader classLoader
    ) {
        try {
            final Class<?> triggerClass =
                XposedHelpers.findClass(
                    "com.amazon.avod.fsm.Trigger",
                    classLoader
                );

            XposedHelpers.findAndHookMethod(
                "com.amazon.avod.fsm.BlockingStateMachine",
                classLoader,
                "doTrigger",
                triggerClass,
                new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(
                        final MethodHookParam param
                    ) {
                        try {
                            interceptAdClipTransition(
                                param,
                                classLoader
                            );
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

        } catch (Throwable ignored) {
        }
    }

    private void interceptAdClipTransition(
        final XC_MethodHook.MethodHookParam param,
        final ClassLoader classLoader
    ) {
        final Object trigger = param.args[0];

        if (trigger == null) {
            return;
        }

        final Object triggerType;

        try {
            triggerType =
                XposedHelpers.callMethod(
                    trigger,
                    "getType"
                );
        } catch (Throwable e) {
            return;
        }

        if (triggerType == null ||
            !NEXT_AD_CLIP_SERVER_INSERTED.equals(
                triggerType.toString()
            )) {
            return;
        }

        final Object currentState;

        try {
            currentState =
                XposedHelpers.callMethod(
                    param.thisObject,
                    "getCurrentState"
                );
        } catch (Throwable e) {
            return;
        }

        if (currentState == null ||
            !SERVER_INSERTED_AD_BREAK_STATE.equals(
                currentState.getClass().getName()
            )) {
            return;
        }

        final Object context;

        try {
            context =
                XposedHelpers.callMethod(
                    currentState,
                    "getContext"
                );
        } catch (Throwable e) {
            return;
        }

        if (context == null) {
            return;
        }

        final Object primaryPlayer;

        try {
            primaryPlayer =
                XposedHelpers.callMethod(
                    context,
                    "getPrimaryPlayer"
                );
        } catch (Throwable e) {
            return;
        }

        if (primaryPlayer == null) {
            return;
        }

        /*
         * --------------------------------------------------
         * 1. シークで広告を跨いだケース
         * --------------------------------------------------
         *
         * ServerInsertedAdBreakState.enter() の
         * beforeHookで保存したseekTargetを使用する。
         */
        Long target = mPendingSeekTarget.get();

        if (target != null) {
            mPendingSeekTarget.remove();

            replaceTriggerAndScheduleSeek(
                param,
                trigger,
                primaryPlayer,
                target.longValue(),
                classLoader
            );

            return;
        }

        /*
         * --------------------------------------------------
         * 2. 通常再生中に広告Breakへ入ったケース
         * --------------------------------------------------
         *
         * この時点ではServerInsertedAdBreakState.enter()
         * の内部処理によってcurrentAdBreakが既にセットされている。
         */
        final Object adBreak;

        try {
            adBreak =
                XposedHelpers.callMethod(
                    context,
                    "getCurrentAdBreak"
                );
        } catch (Throwable e) {
            return;
        }

        if (adBreak == null) {
            return;
        }

        final long currentPosition =
            getCurrentPosition(primaryPlayer);

        if (currentPosition < 0L) {
            return;
        }

        final long adDuration =
            getAdDuration(adBreak);

        if (adDuration <= 0L) {
            return;
        }

        final long targetPosition =
            currentPosition + adDuration;

        replaceTriggerAndScheduleSeek(
            param,
            trigger,
            primaryPlayer,
            targetPosition,
            classLoader
        );
    }

    private void replaceTriggerAndScheduleSeek(
        final XC_MethodHook.MethodHookParam param,
        final Object originalTrigger,
        final Object primaryPlayer,
        final long targetPosition,
        final ClassLoader classLoader
    ) {
        final Object replacementTrigger =
            createNoMoreAdsSkipTrigger(
                originalTrigger,
                classLoader
            );

        if (replacementTrigger == null) {
            return;
        }

        /*
         * NEXT_AD_CLIP_SERVER_INSERTED
         *
         * ↓
         *
         * NO_MORE_ADS_SKIP_TRANSITION
         *
         * に置換。
         *
         * ここではdoTrigger()を呼び直さない。
         */
        param.args[0] = replacementTrigger;

        /*
         * FSM遷移後にPlayingになるのを待って
         * 広告終了位置へAD_SKIP seekする。
         */
        scheduleSeekAfterPlaying(
            primaryPlayer,
            targetPosition,
            classLoader
        );
    }

    private Object createNoMoreAdsSkipTrigger(
        final Object originalTrigger,
        final ClassLoader classLoader
    ) {
        try {
            final Object originalType =
                XposedHelpers.callMethod(
                    originalTrigger,
                    "getType"
                );

            if (originalType == null) {
                return null;
            }

            final Class<?> triggerTypeClass =
                originalType.getClass();

            @SuppressWarnings("unchecked")
            final Object replacementType =
                Enum.valueOf(
                    (Class<? extends Enum>) triggerTypeClass,
                    NO_MORE_ADS_SKIP_TRANSITION
                );

            Object source = null;

            try {
                source =
                    XposedHelpers.callMethod(
                        originalTrigger,
                        "getSource"
                    );
            } catch (Throwable ignored) {
            }

            final Class<?> simpleTriggerClass =
                XposedHelpers.findClass(
                    "com.amazon.avod.fsm.SimpleTrigger",
                    classLoader
                );

            if (source != null) {
                try {
                    return XposedHelpers.newInstance(
                        simpleTriggerClass,
                        replacementType,
                        source
                    );
                } catch (Throwable ignored) {
                }
            }

            return XposedHelpers.newInstance(
                simpleTriggerClass,
                replacementType
            );

        } catch (Throwable ignored) {
            return null;
        }
    }

    private void scheduleSeekAfterPlaying(
        final Object primaryPlayer,
        final long targetPosition,
        final ClassLoader classLoader
    ) {
        final long startTime =
            System.currentTimeMillis();

        mExecutor.schedule(
            () -> waitForPlayingAndSeek(
                primaryPlayer,
                targetPosition,
                startTime,
                classLoader
            ),
            20L,
            TimeUnit.MILLISECONDS
        );
    }

    private void waitForPlayingAndSeek(
        final Object primaryPlayer,
        final long targetPosition,
        final long startTime,
        final ClassLoader classLoader
    ) {
        try {
            final Object playbackState =
                getPlaybackState(primaryPlayer);

            if (playbackState != null &&
                "Playing".equals(
                    playbackState.toString()
                )) {

                performAdSkipSeek(
                    primaryPlayer,
                    targetPosition,
                    classLoader
                );

                return;
            }

            if (System.currentTimeMillis() - startTime >= 2000L) {
                return;
            }

            mExecutor.schedule(
                () -> waitForPlayingAndSeek(
                    primaryPlayer,
                    targetPosition,
                    startTime,
                    classLoader
                ),
                30L,
                TimeUnit.MILLISECONDS
            );

        } catch (Throwable ignored) {
        }
    }

    private Object getPlaybackState(
        final Object primaryPlayer
    ) {
        try {
            final Object playbackSession =
                XposedHelpers.getObjectField(
                    primaryPlayer,
                    "mPlaybackSession"
                );

            if (playbackSession == null) {
                return null;
            }

            final Object playbackEngine =
                XposedHelpers.callMethod(
                    playbackSession,
                    "getPlaybackEngine"
                );

            if (playbackEngine == null) {
                return null;
            }

            return XposedHelpers.callMethod(
                playbackEngine,
                "getPlaybackState"
            );

        } catch (Throwable ignored) {
            return null;
        }
    }

    private long getCurrentPosition(
        final Object primaryPlayer
    ) {
        try {
            final Object value =
                XposedHelpers.callMethod(
                    primaryPlayer,
                    "getCurrentPosition"
                );

            if (value instanceof Number) {
                return ((Number) value).longValue();
            }

        } catch (Throwable ignored) {
        }

        return -1L;
    }

    private long getAdDuration(
        final Object adBreak
    ) {
        try {
            final Object duration =
                XposedHelpers.callMethod(
                    adBreak,
                    "getDurationExcludingAux"
                );

            return getTimeSpanMilliseconds(duration);

        } catch (Throwable ignored) {
        }

        return -1L;
    }

    private long getTimeSpanMilliseconds(
        final Object timeSpan
    ) {
        if (timeSpan == null) {
            return -1L;
        }

        try {
            final Object value =
                XposedHelpers.callMethod(
                    timeSpan,
                    "getTotalMilliseconds"
                );

            if (value instanceof Number) {
                return ((Number) value).longValue();
            }

        } catch (Throwable ignored) {
        }

        return -1L;
    }

    private void performAdSkipSeek(
        final Object primaryPlayer,
        final long targetPosition,
        final ClassLoader classLoader
    ) {
        try {
            final Class<?> seekCauseClass =
                XposedHelpers.findClass(
                    "com.amazon.avod.playback.player.actions.SeekAction$SeekCause",
                    classLoader
                );

            @SuppressWarnings("unchecked")
            final Object adSkipCause =
                Enum.valueOf(
                    (Class<? extends Enum>) seekCauseClass,
                    "AD_SKIP"
                );

            XposedHelpers.callMethod(
                primaryPlayer,
                "seekToManifestPosition",
                targetPosition,
                adSkipCause
            );

            XposedHelpers.callMethod(
                primaryPlayer,
                "start"
            );

        } catch (Throwable ignored) {
        }
    }
}
