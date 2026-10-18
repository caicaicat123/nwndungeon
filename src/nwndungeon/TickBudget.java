package nwndungeon;

import java.util.Iterator;
import java.util.function.LongSupplier;

/** 主线程分批执行：同时限制操作数和时间；单次 Bukkit 操作不能被中途抢占。 */
public final class TickBudget {
    private final Iterator<Runnable> work;
    private final LongSupplier clock;
    private boolean cancelled;
    private long operations;
    private long maximumNanos;

    public TickBudget(Iterator<Runnable> work) { this(work, System::nanoTime); }
    TickBudget(Iterator<Runnable> work, LongSupplier clock) {
        this.work = work;
        this.clock = clock;
    }
    public boolean step(int maxOperations, long maxNanos) {
        if (maxOperations < 1 || maxNanos < 1) { throw new IllegalArgumentException("预算必须为正数"); }
        if (cancelled) { return true; }
        long start = clock.getAsLong();
        int count = 0;
        while (!cancelled && count < maxOperations && work.hasNext()) {
            if (count > 0 && clock.getAsLong() - start >= maxNanos) { break; }
            work.next().run();
            count++;
            operations++;
        }
        maximumNanos = Math.max(maximumNanos, clock.getAsLong() - start);
        return cancelled || !work.hasNext();
    }
    public void cancel() { cancelled = true; }
    public long operations() { return operations; }
    public long maximumNanos() { return maximumNanos; }
}
