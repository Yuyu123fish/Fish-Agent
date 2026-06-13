package com.yuyu.fishagent.common.metrics;

import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单轮流式对话的可观测生命周期。
 * <p>SSE、Reactor subscribe 与客户端断连可能从不同线程触发多个终止回调；本类用 CAS 保证
 * Timer 只结束一次，防止重复计数。</p>
 */
public final class ChatTurnLifecycle {

    private final ChatMetrics chatMetrics;
    private final Timer.Sample sample;
    private final Consumer<ChatMetrics.Outcome> finishCallback;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private ChatTurnLifecycle(ChatMetrics chatMetrics, Timer.Sample sample, Consumer<ChatMetrics.Outcome> finishCallback) {
        this.chatMetrics = chatMetrics;
        this.sample = sample;
        this.finishCallback = finishCallback;
    }

    public static ChatTurnLifecycle start(ChatMetrics chatMetrics) {
        return start(chatMetrics, outcome -> { });
    }

    public static ChatTurnLifecycle start(ChatMetrics chatMetrics, Consumer<ChatMetrics.Outcome> finishCallback) {
        return new ChatTurnLifecycle(chatMetrics, chatMetrics.startSample(),
                finishCallback == null ? outcome -> { } : finishCallback);
    }

    public void success() {
        finish(ChatMetrics.Outcome.SUCCESS);
    }

    public void error(Throwable error) {
        // error 入参保留给调用点表达意图；异常日志由各出口自行记录，指标只需要 outcome。
        finish(ChatMetrics.Outcome.ERROR);
    }

    private void finish(ChatMetrics.Outcome outcome) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        sample.stop(chatMetrics.chatTurnTimer(outcome));
        finishCallback.accept(outcome);
    }
}
