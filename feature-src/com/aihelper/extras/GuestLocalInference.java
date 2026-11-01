package com.aihelper.extras;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.arm.aichat.AiChat;
import com.arm.aichat.InferenceEngine;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowCollector;
import kotlinx.coroutines.sync.Mutex;
import kotlinx.coroutines.sync.MutexKt;

/**
 * Text-only isolated generation for guest-reply planning. This class never uses
 * LocalLlmEngine.ask, AssistantCommandRouter, owner memories or a tool planner.
 * Every request reloads the model, installs the supplied guest-only system prompt,
 * collects bounded raw text, then cleans up before releasing the UI's mutex.
 *
 * LocalLlmEngine's constructor must assign sharedMutex() to its model mutex.
 * Native suspend calls are awaited to actual completion. A caller timeout marks
 * the request canceled and returns null; its background worker retains the mutex
 * until the suspended call completes and cleanup finishes. It never interrupts a
 * JNI call or releases the engine while work may still be active. If cleanup cannot
 * restore Initialized, the engine is quarantined with the mutex retained until
 * process restart. Callers must turn null into a draft/policy fallback, not a send.
 */
public final class GuestLocalInference {
    private static final String TAG = "EvaGuestInference";
    private static final int PREDICT_LENGTH = 160;
    private static final int MAX_OUTPUT_CHARS = 6000;
    private static final Mutex MODEL_MUTEX = MutexKt.Mutex(false);
    private static volatile boolean quarantined;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "EvaGuestInference");
                    thread.setDaemon(true);
                    return thread;
                }
            }, new ThreadPoolExecutor.AbortPolicy());

    private GuestLocalInference() { }

    /** Shared by every UI LocalLlmEngine and the raw guest transaction. */
    public static Mutex sharedMutex() { return MODEL_MUTEX; }

    /** Busy, unavailable, invalid, canceled or failed generation returns null. */
    public static String generate(Context context, String systemPrompt, String guestPrompt,
                                  long timeoutMillis) {
        if (context == null || systemPrompt == null || guestPrompt == null
                || systemPrompt.trim().length() == 0 || systemPrompt.length() > 32768
                || guestPrompt.trim().length() == 0 || guestPrompt.length() > 8192
                || timeoutMillis < 100L || timeoutMillis > 120000L || quarantined
                || Looper.myLooper() == Looper.getMainLooper()) return null;
        Context application = context.getApplicationContext();
        final Operation operation = new Operation(application == null ? context : application,
                systemPrompt, guestPrompt, timeoutMillis);
        Future<String> future;
        try {
            future = WORKER.submit(operation);
        } catch (RejectedExecutionException fullQueue) {
            return null;
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            operation.cancelled.set(true);
            // Do not Future.cancel(true): a JNI call may still own the engine.
            return null;
        } catch (InterruptedException interrupted) {
            operation.cancelled.set(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failed) {
            return null;
        }
    }

    public static boolean isQuarantined() { return quarantined; }

    private static final class Operation implements Callable<String> {
        final Context context;
        final String systemPrompt;
        final String guestPrompt;
        final long deadlineNanos;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Object owner = new Object();
        final StringBuilder output = new StringBuilder();

        Operation(Context context, String systemPrompt, String guestPrompt, long timeoutMillis) {
            this.context = context;
            this.systemPrompt = systemPrompt;
            this.guestPrompt = guestPrompt;
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }

        boolean canceled() {
            if (System.nanoTime() >= deadlineNanos) cancelled.set(true);
            return cancelled.get();
        }

        @Override public String call() {
            if (canceled() || quarantined || !MODEL_MUTEX.tryLock(owner)) return null;
            InferenceEngine engine = null;
            boolean nativeTouched = false;
            boolean safeToUnlock = true;
            String answer = null;
            try {
                engine = AiChat.INSTANCE.getInferenceEngine(context);
                String state = stateName(engine);
                while (("Uninitialized".equals(state) || "Initializing".equals(state))
                        && !canceled()) {
                    Thread.sleep(20L);
                    state = stateName(engine);
                }
                if (canceled()) return null;
                if ("ModelReady".equals(state)) {
                    nativeTouched = true;
                    engine.cleanUp();
                    state = stateName(engine);
                }
                // A busy/error native state without our ownership is not a fresh session.
                if (!"Initialized".equals(state) || canceled()) return null;
                File model = new File(context.getFilesDir(), "models/assistant.gguf");
                if (!model.isFile() || !model.canRead()) return null;

                nativeTouched = true;
                BlockingContinuation loaded = new BlockingContinuation();
                loaded.awaitResult(engine.loadModel(model.getAbsolutePath(), loaded));
                if (canceled()) return null;
                BlockingContinuation system = new BlockingContinuation();
                system.awaitResult(engine.setSystemPrompt(systemPrompt, system));
                if (canceled()) return null;
                Flow<String> flow = engine.sendUserPrompt(guestPrompt + "\n/no_think", PREDICT_LENGTH);
                BlockingContinuation generated = new BlockingContinuation();
                Object initial = flow.collect(new FlowCollector<String>() {
                    @Override public Object emit(String token, Continuation<? super Unit> continuation) {
                        if (canceled()) throw new CancellationException("Guest inference canceled");
                        if (token != null) {
                            if (output.length() + token.length() > MAX_OUTPUT_CHARS) {
                                cancelled.set(true);
                                throw new CancellationException("Guest inference output bound");
                            }
                            output.append(token);
                        }
                        return Unit.INSTANCE;
                    }
                }, generated);
                generated.awaitResult(initial);
                if (!canceled() && "ModelReady".equals(stateName(engine))) {
                    String text = output.toString().trim();
                    if (text.length() > 0) answer = text;
                }
            } catch (Exception failed) {
                // Never log prompts, model output, memories or exception messages.
                Log.w(TAG, "Text generation unavailable: " + failed.getClass().getSimpleName());
            } finally {
                if (nativeTouched && engine != null) {
                    try {
                        String state = stateName(engine);
                        if ("ModelReady".equals(state)) engine.cleanUp();
                        // The original native Error cleanup only resets a flag; it does
                        // not unload the model. Do not admit UI work into that session.
                        safeToUnlock = "Initialized".equals(stateName(engine));
                    } catch (Throwable cleanupFailed) {
                        safeToUnlock = false;
                    }
                }
                if (safeToUnlock) MODEL_MUTEX.unlock(owner);
                else {
                    quarantined = true;
                    answer = null;
                    Log.w(TAG, "Native session quarantined until process restart");
                }
            }
            return answer;
        }
    }

    private static String stateName(InferenceEngine engine) {
        Object state = engine.getState().getValue();
        return state == null ? "Unknown" : state.getClass().getSimpleName();
    }

    private static final class BlockingContinuation implements Continuation<Unit> {
        private final CountDownLatch complete = new CountDownLatch(1);
        private volatile Object result;
        @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
        @Override public void resumeWith(Object value) {
            result = value;
            complete.countDown();
        }
        void awaitResult(Object initial) {
            if (initial != IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                ResultKt.throwOnFailure(initial);
                return;
            }
            // Public generate() is bounded. This owner must wait past that bound to
            // avoid admitting concurrent UI JNI work before actual completion.
            boolean interrupted = false;
            for (;;) {
                try { complete.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            ResultKt.throwOnFailure(result);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
