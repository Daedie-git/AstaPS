package emu.grasscutter.server.game;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Owns login admission, queued cancellation and bounded producer/session joining. */
public final class LoginProducerLifecycle {
    private final ExecutorService executor;
    private final Set<GameSession> sessions = new HashSet<>();
    private boolean stopping;
    public LoginProducerLifecycle(ExecutorService executor) { this.executor = executor; }

    public boolean submit(GameSession session, Runnable action, boolean async) {
        Task task;
        synchronized (this) {
            if (stopping || !session.beginLoginWork()) return false;
            sessions.add(session);
            task = new Task(session, action);
        }
        if (!async) { task.run(); return true; }
        try { executor.execute(task); return true; }
        catch (RuntimeException failure) { task.cancel(); throw failure; }
    }

    private final class Task implements Runnable {
        final GameSession session;
        final Runnable action;
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        Task(GameSession session, Runnable action) { this.session = session; this.action = action; }
        public void run() {
            if (!started.compareAndSet(false, true)) return;
            try { if (session.acceptsGameWork()) action.run(); }
            finally { finish(); }
        }
        void cancel() { if (started.compareAndSet(false, true)) finish(); }
        void finish() {
            session.endLoginWork();
            synchronized (LoginProducerLifecycle.this) {
                if (session.loginWorkCompletion().isDone()) sessions.remove(session);
            }
        }
    }

    public void shutdown(Duration timeout) {
        List<GameSession> pending;
        synchronized (this) { stopping = true; pending = List.copyOf(sessions); }
        pending.forEach(GameSession::close);
        RuntimeException failure = null;
        try { stopExecutor(executor, timeout); }
        catch (RuntimeException e) { failure = e; pending.forEach(GameSession::quarantineUnstoppedLoader); }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (var session : pending) {
            try { session.retireAccountLease().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (Exception e) {
                session.quarantineUnstoppedLoader();
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (failure == null) failure = new IllegalStateException("Pending login session did not retire", e);
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    public static void stopExecutor(ExecutorService executor, Duration timeout) {
        boolean interrupted = false;
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) return;
        } catch (InterruptedException e) { interrupted = true; }
        for (var queued : executor.shutdownNow()) {
            if (queued instanceof LoginProducerLifecycle.Task task) task.cancel();
        }
        try {
            if (!executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS))
                throw new IllegalStateException("Producer executor did not terminate after cancellation");
        } catch (InterruptedException e) {
            interrupted = true;
            throw new IllegalStateException("Interrupted while joining producer executor", e);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (interrupted) throw new IllegalStateException("Interrupted while stopping producer executor");
    }
}
