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

    @Override
    public void handleLoadPackage(
        final XC_LoadPackage.LoadPackageParam lpparam
    ) throws Throwable {

        if (!PRIME_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        hookBlockingStateMachine(lpparam.classLoader);
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
                            interceptAdBreakTransition(
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

    private void interceptAdBreakTransition(
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

        if (triggerType == null) {
            return;
        }

        if (!NEXT_AD_CLIP_SERVER_INSERTED.equals(
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

        if (currentState == null) {
            return;
        }

        if (!SERVER_INSERTED_AD_BREAK_STATE.equals(
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

        /*
         * Chapter/seek-into-ad-break の特殊経路は、
         * 通常の広告再生開始とは処理が異なる。
         *
         * ここではまず通常再生中のSSAI広告を確実に
         * 広告クリップ状態へ入れずに処理する。
         */
        if (isSeekingIntoAdBreak(context)) {
            return;
        }

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

        /*
         * 重要:
         *
         * ここで doTrigger() を自分で呼び直さない。
         *
         * 元の doTrigger() に渡される trigger 自体を
         * NO_MORE_ADS_SKIP_TRANSITION に置き換える。
         *
         * これによって BlockingStateMachine が通常通り
         * ServerInsertedAdBreakState -> コンテンツ側
         * の遷移を実行する。
         */
        final Object replacementTrigger =
            createNoMoreAdsSkipTrigger(
                trigger,
                classLoader
            );

        if (replacementTrigger == null) {
            return;
        }

        param.args[0] = replacementTrigger;

        /*
         * FSM遷移そのものはここで同期的に進む。
         *
         * ただし、この時点では playback engine が
         * Launching の可能性がある。
         *
         * したがって seek はここでは実行せず、
         * Playing 到達後に非同期で行う。
         */
        scheduleSeekAfterPlaying(
            context,
            primaryPlayer,
            targetPosition,
            classLoader
        );
    }

    private boolean isSeekingIntoAdBreak(
        final Object context
    ) {
        try {
            final Object value =
                XposedHelpers.callMethod(
                    context,
                    "getSeekingIntoAdBreakTime"
                );

            if (value instanceof Number) {
                return ((Number) value).longValue() >= 0L;
            }

        } catch (Throwable ignored) {
        }

        return false;
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

            if (duration == null) {
                return -1L;
            }

            final Object milliseconds =
                XposedHelpers.callMethod(
                    duration,
                    "getTotalMilliseconds"
                );

            if (milliseconds instanceof Number) {
                return ((Number) milliseconds).longValue();
            }

        } catch (Throwable ignored) {
        }

        return -1L;
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
        final Object context,
        final Object primaryPlayer,
        final long targetPosition,
        final ClassLoader classLoader
    ) {
        final long startTime =
            System.currentTimeMillis();

        mExecutor.schedule(
            () -> waitForPlayingAndSeek(
                context,
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
        final Object context,
        final Object primaryPlayer,
        final long targetPosition,
        final long startTime,
        final ClassLoader classLoader
    ) {
        try {
            /*
             * すでに別の広告遷移に移っていた場合などは、
             * currentAdBreak の状態を確認する。
             *
             * ただしここでは「広告を飛ばした直後」の
             * breakが残っていても正常なので、
             * currentAdBreak の存在だけでは中止しない。
             */

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

            /*
             * 最大2秒待つ。
             *
             * Launching のまま seek すると、
             * PlaybackStateTransitionTable に
             * Launching + Seek が存在しないため、
             * SeekAction が期待通り処理されない。
             */
            if (System.currentTimeMillis() - startTime >= 2000L) {
                return;
            }

            mExecutor.schedule(
                () -> waitForPlayingAndSeek(
                    context,
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

            /*
             * AmazonVideoPlayer の本来の広告スキップ経路と
             * 同じ seekToManifestPosition(..., AD_SKIP) を使う。
             *
             * MANUAL ではなく AD_SKIP を使うことで、
             * Amazon側の広告スキップ用処理として扱わせる。
             */
            XposedHelpers.callMethod(
                primaryPlayer,
                "seekToManifestPosition",
                targetPosition,
                adSkipCause
            );

            /*
             * AmazonVideoPlayer.start() 相当。
             *
             * AD_SKIP seek 後に再生を継続させる。
             */
            XposedHelpers.callMethod(
                primaryPlayer,
                "start"
            );

        } catch (Throwable ignored) {
        }
    }
}
