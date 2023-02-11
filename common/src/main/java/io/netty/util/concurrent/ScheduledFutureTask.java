/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.concurrent;

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 延迟任务，可以基于任务开始时间的固定速率延迟也可以基于任务的结束时间固定速率延迟
 * @param <V>
 */
@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    private static final AtomicLong nextTaskId = new AtomicLong();
    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    /**
     * 给定延迟时间，返回执行时间
     * @param delay
     * @return
     */
    static long deadlineNanos(long delay) {
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    // 任务数
    private final long id = nextTaskId.getAndIncrement();
    // 任务执行时间 纳秒的时间戳
    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    // 任务执行周期
    private final long periodNanos;

    private int queueIndex = INDEX_NOT_IN_QUEUE;

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Runnable runnable, V result, long nanoTime) {

        this(executor, toCallable(runnable, result), nanoTime);
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long delayNanos() {
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    @Override
    public void run() {
        assert executor().inEventLoop();
        try {
            if (periodNanos == 0) {
                if (setUncancellableInternal()) {
                    V result = task.call();
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) {
                    task.call();
                    if (!executor().isShutdown()) {
                        long p = periodNanos;
                        // 这里比较有意思
                        if (p > 0) {
                            // 假设deadlineNanos为15:00:00，periodNanos为5s，task执行时间为15:00:00
                            // 1.task在15:00:01执行完，那么下次执行时间为15:00:05(deadlineNanos += p)
                            // 2.task在15:00:06执行完，那么下次执行时间为15:00:10(deadlineNanos += p)
                            // 2.task在15:00:16执行完，那么下次执行时间为15:00:15(deadlineNanos += p) 也就是当任务执行时间超过了延迟时间，那么任务结束后立即执行下一次
                            // 以当前任务的开始时间计算下次的执行时间
                            deadlineNanos += p;
                        } else {
                            // 假设deadlineNanos为15:00:00，periodNanos为-5s，task执行时间为15:00:00
                            // 1.task在15:00:01执行完，那么下次执行时间为15:00:06
                            // 2.task在15:00:07执行完，那么下次执行时间为15:00:12
                            // 2.task在15:00:16执行完，那么下次执行时间为15:00:21
                            // 以当前任务执行的结束时间计算下次的执行时间
                            deadlineNanos = nanoTime() - p;
                        }
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            assert scheduledTaskQueue != null;
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" id: ")
                  .append(id)
                  .append(", deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
