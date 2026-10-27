package top.pmckk.flan.storage;

import top.pmckk.flan.model.Snapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class YamlStorage implements Storage {
    private final Path file;
    public YamlStorage(Path file) { this.file = file; }
    public String type() { return "YAML"; }
    public Snapshot load() throws Exception {
        return Files.exists(file) ? YamlCodec.decode(Files.readString(file, StandardCharsets.UTF_8)) : Snapshot.empty();
    }
    public void save(Snapshot snapshot) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, YamlCodec.encode(snapshot), StandardCharsets.UTF_8);
        if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    public void close() {}
}
