package com.motion.backend.processing;

import com.motion.backend.config.MotionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring-managed persistent job queue consumer.
 *
 * <p>Jobs live in SQLite. The bounded wake queue only removes poll latency; workers also poll on a
 * timer so dropped wake-ups cannot lose work. Exactly {@code workerCount} long-lived workers exist,
 * and no FFmpeg work ever runs on an HTTP request thread.</p>
 */
@Component
public class AsyncJobManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobManager.class);
    private static final Object WAKE = new Object();

    private final MotionProperties properties;
    private final JobStore jobs;
    private final JobExecutionService execution;
    private final JobExecutionRegistry registry;
    private final JobRecoveryService recovery;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger workerNumber = new AtomicInteger();
    private final BlockingQueue<Object> wakeQueue;

    private volatile ExecutorService workers;
    private volatile boolean shutdownRequested;

    public AsyncJobManager(MotionProperties properties, JobStore jobs,
                           JobExecutionService execution, JobExecutionRegistry registry,
                           JobRecoveryService recovery) {
        this.properties = properties;
        this.jobs = jobs;
        this.execution = execution;
        this.registry = registry;
        this.recovery = recovery;
        this.wakeQueue = new ArrayBlockingQueue<>(Math.max(1, properties.queueCapacity()));
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        shutdownRequested = false;
        recovery.recoverOnStartup();

        int count = Math.max(1, properties.workerCount());
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "motion-job-worker-" + workerNumber.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        workers = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(count), factory,
                (task, executor) -> log.warn("job worker queue rejected a task"));
        for (int i = 0; i < count; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("async job manager started with {} workers", count);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        shutdownRequested = true;
        registry.stopForShutdown();
        for (int i = 0; i < properties.queueCapacity(); i++) {
            wakeQueue.offer(WAKE);
        }
        ExecutorService executor = workers;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(properties.jobShutdownTimeout().toMillis(),
                        TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        recovery.requeueRemainingAfterShutdown();
        log.info("async job manager stopped");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Non-blocking hint; the periodic DB poll is the durable fallback. */
    public void notifyJobs() {
        if (running.get()) {
            wakeQueue.offer(WAKE);
        }
    }

    public void requestCancel(String jobId, String token) {
        registry.requestCancel(jobId, token);
    }

    private void workerLoop() {
        long pollMillis = Math.max(10L, properties.jobPollInterval().toMillis());
        String workerId = Thread.currentThread().getName();
        while (running.get() && !shutdownRequested && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<JobLease> lease = jobs.claimNext(workerId);
                if (lease.isPresent()) {
                    if (shutdownRequested) {
                        jobs.requeueInterrupted(lease.get().job().id(), lease.get().token());
                        break;
                    }
                    execution.execute(lease.get());
                    // A user cancellation deliberately interrupts this thread to stop FFmpeg.
                    // The execution restores that flag before returning; clear it here so one
                    // cancelled job cannot retire a pooled worker. Shutdown still exits below.
                    Thread.interrupted();
                    continue;
                }
                wakeQueue.poll(pollMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                if (shutdownRequested || !running.get()) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Stale interrupt from a completed cancellation; keep the worker alive.
                Thread.interrupted();
            } catch (Throwable exception) {
                log.error("job worker {} crashed; continuing", workerId, exception);
                if (!shutdownRequested && running.get()) {
                    Thread.interrupted();
                }
                try {
                    Thread.sleep(pollMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** Test hook for waiting until this manager has no registered executions. */
    public boolean idle() {
        return registry.activeExecutions().isEmpty();
    }
}
