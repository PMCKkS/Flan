package top.pmckk.flan.storage;

import top.pmckk.flan.model.Snapshot;

public interface Storage extends AutoCloseable {
    String type();
    Snapshot load() throws Exception;
    void save(Snapshot snapshot) throws Exception;
    @Override void close() throws Exception;
}
