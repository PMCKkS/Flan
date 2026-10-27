package top.pmckk.flan.config;

/** Carries a language key across domain and storage layers without a global locale. */
public final class MessageException extends IllegalArgumentException {
    private final String key;
    private final Object[] arguments;

    public MessageException(String key, Object... arguments) {
        super(key);
        this.key = key;
        this.arguments = arguments.clone();
    }

    public String key() { return key; }
    public Object[] arguments() { return arguments.clone(); }
}
