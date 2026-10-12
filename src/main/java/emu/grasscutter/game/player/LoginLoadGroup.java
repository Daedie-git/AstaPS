package emu.grasscutter.game.player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Required login components share one deadline, including cancellation and worker joining. */
public final class LoginLoadGroup {
    private LoginLoadGroup() {}
    public static final class UnstoppedLoaderException extends IllegalStateException {
        UnstoppedLoaderException(String message) { super(message); }
    }
    private static final class Job implements Callable<Void> {
        final Runnable component;
        final CountDownLatch finished = new CountDownLatch(1);
        boolean started, cancelled;
        Job(Runnable component) { this.component = component; }
        public Void call() {
            synchronized (this) {
                if (cancelled) return null;
                started = true;
            }
            try { component.run(); return null; }
            finally { finished.countDown(); }
        }
        synchronized void cancel() {
            cancelled = true;
            if (!started) finished.countDown();
        }
    }

    public static void load(ExecutorService executor, Duration timeout, Runnable... components) {
        long deadline = System.nanoTime() + timeout.toNanos();
        var completion = new ExecutorCompletionService<Void>(executor);
        var futures = new ArrayList<Future<Void>>();
        var jobs = new ArrayList<Job>();
        try {
            for (var component : components) {
                var job = new Job(component);
                jobs.add(job);
                futures.add(completion.submit(job));
            }
            for (int i = 0; i < components.length; i++) {
                var result = completion.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (result == null) throw new TimeoutException("Login component deadline exceeded");
                result.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Login component loading interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("Login component loading failed", failure);
        } finally {
            jobs.forEach(Job::cancel);
            futures.forEach(future -> future.cancel(true));
            boolean interrupted = Thread.interrupted();
            try {
                for (var job : jobs) {
                    if (!job.finished.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw new UnstoppedLoaderException("Login loader did not stop before deadline");
                    }
                }
            } catch (InterruptedException failure) {
                interrupted = true;
                throw new UnstoppedLoaderException("Interrupted joining login loaders");
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
