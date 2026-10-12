package emu.grasscutter.server;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Runs cleanup once; all callers observe its completion or failure, including failed stages. */
public final class ShutdownSequence {
    private FutureTask<Void> shutdown;
    private volatile Thread owner;

    public void run(Runnable... stages) {
        FutureTask<Void> task;
        synchronized (this) {
            if (owner == Thread.currentThread()) {
                throw new IllegalStateException("Reentrant cleanup cannot wait for itself");
            }
            if (shutdown == null) {
                shutdown = new FutureTask<>(() -> {
                    owner = Thread.currentThread();
                    Throwable failure = null;
                    boolean interrupted = Thread.interrupted();
                    try {
                        for (var stage : stages) {
                            try { stage.run(); }
                            catch (Throwable stageFailure) {
                                if (failure == null) failure = stageFailure;
                                else if (stageFailure != failure) failure.addSuppressed(stageFailure);
                            } finally {
                                // Later cleanup stages retain their timeout budget even if a prior stage was interrupted.
                                interrupted |= Thread.interrupted();
                            }
                        }
                        if (failure != null) throw new IllegalStateException("Cleanup sequence failed", failure);
                        return null;
                    } finally {
                        owner = null;
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                });
            }
            task = shutdown;
        }
        task.run();
        boolean interrupted = false;
        try {
            while (true) {
                try { task.get(); return; }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { throw new IllegalStateException("Cleanup sequence failed", e.getCause()); }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
