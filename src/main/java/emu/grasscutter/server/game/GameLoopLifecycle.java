package emu.grasscutter.server.game;

import emu.grasscutter.server.ShutdownSequence;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

/** Owns the tick producer and its shutdown boundary. */
final class GameLoopLifecycle {
    private Timer timer;
    private volatile boolean stopped;
    private final java.util.concurrent.locks.ReentrantLock tickLock = new java.util.concurrent.locks.ReentrantLock();
    private final ShutdownSequence shutdown = new ShutdownSequence();

    synchronized void start(Runnable tick, long periodMillis) {
        if (stopped || timer != null) throw new IllegalStateException("Game loop already started or stopped");
        timer = new Timer("game-loop");
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                tickLock.lock();
                try {
                    if (!stopped) tick.run();
                } finally { tickLock.unlock(); }
            }
        }, new Date(), Math.max(1, periodMillis));
    }

    boolean isStopped() { return stopped; }

    void stop(Runnable cleanup) {
        synchronized (this) {
            stopped = true;
            if (timer != null) timer.cancel();
        }
        IllegalStateException tickFailure = null;
        try {
            if (tickLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) tickLock.unlock();
            else tickFailure = new IllegalStateException("Timed out stopping the active game tick; cleanup is incomplete");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            tickFailure = new IllegalStateException("Interrupted while stopping the game tick", interrupted);
        }
        var failure = tickFailure;
        shutdown.run(() -> { if (failure != null) throw failure; }, cleanup);
    }
}
