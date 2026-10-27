package top.pmckk.flan.storage;

import top.pmckk.flan.config.MessageException;

import top.pmckk.flan.model.Snapshot;
import top.pmckk.flan.service.ClaimRegistry;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Single writer prevents older snapshots from overtaking newer saves. */
public final class Persistence {
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Flan-storage"); t.setDaemon(true); return t; });
    private final Storage storage;
    private final ClaimRegistry registry;
    private final Consumer<Throwable> failure;
    private volatile boolean healthy = true;
    private volatile long savedRevision = -1;
    private CompletableFuture<Void> pending;
    private long queuedRevision = -1;
    public Persistence(Storage storage, ClaimRegistry registry, Consumer<Throwable> failure) { this.storage = storage; this.registry = registry; this.failure = failure; }
    public String type() { return storage.type(); }
    public boolean healthy() { return healthy; }
    public synchronized CompletableFuture<Void> save() {
        long revision; Snapshot snapshot;
        synchronized (registry) { revision = registry.revision(); snapshot = registry.snapshot(); }
        if (pending != null && !pending.isDone() && queuedRevision == revision) return pending;
        if (savedRevision == revision && healthy) return CompletableFuture.completedFuture(null);
        queuedRevision = revision;
        pending = CompletableFuture.runAsync(() -> {
            try { storage.save(snapshot); savedRevision = revision; healthy = true; }
            catch (Exception e) { healthy = false; failure.accept(e); throw new CompletionException(e); }
        }, writer);
        return pending;
    }
    public CompletableFuture<Void> migrate(Storage target) {
        Snapshot snapshot = registry.snapshot();
        return CompletableFuture.runAsync(() -> {
            try (target) {
                if (!target.load().isEmpty()) throw new MessageException("errors.migration-not-empty");
                target.save(snapshot);
                if (!target.load().equals(snapshot)) {
                    Snapshot read = target.load();
                    if (!new java.util.HashSet<>(read.claims()).equals(new java.util.HashSet<>(snapshot.claims()))
                            || !new java.util.HashSet<>(read.players()).equals(new java.util.HashSet<>(snapshot.players()))) throw new MessageException("errors.migration-verification");
                }
            } catch (Exception e) { throw new CompletionException(e); }
        }, writer);
    }
    public void close(Path directory) throws Exception {
        Snapshot snapshot = registry.snapshot();
        Future<?> finalSave = writer.submit(() -> {
            try { storage.save(snapshot); } catch (Exception e) { throw new CompletionException(e); }
        });
        writer.shutdown();
        try { finalSave.get(30, TimeUnit.SECONDS); }
        catch (Exception e) {
            Files.createDirectories(directory);
            new YamlStorage(directory.resolve("recovery-" + Instant.now().toEpochMilli() + ".yml")).save(snapshot);
            failure.accept(e);
        } finally {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
            storage.close();
        }
    }
}
