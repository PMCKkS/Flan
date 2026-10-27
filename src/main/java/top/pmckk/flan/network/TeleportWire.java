package top.pmckk.flan.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, authenticated plugin messages; contains claim IDs, never client-selected coordinates. */
public final class TeleportWire {
    public static final String CHANNEL = "flan:teleport";
    public static final int PAGE_SIZE = 100, MAX_PAGES = 1000, MAX_BYTES = 30000;
    private static final int MAGIC = 0x464C414E, VERSION = 1;
    public static final Set<String> STATUSES = Set.of("network.accepted", "network.failed", "network.not-found", "network.ambiguous", "network.busy");
    public sealed interface Message permits Catalog, Request, Ready, Intent, Ack, Status, Names {}
    public record Entry(UUID id, UUID owner, String name) {
        public Entry { Objects.requireNonNull(id); text(name, 48); }
    }
    public record Catalog(long generation, int page, int pages, List<Entry> entries) implements Message {
        public Catalog {
            if (generation <= 0 || pages < 1 || pages > MAX_PAGES || page < 0 || page >= pages || entries.size() > PAGE_SIZE) throw new IllegalArgumentException("catalog");
            entries = List.copyOf(entries);
        }
    }
    public record Request(UUID id, UUID player, String selector, boolean admin) implements Message {
        public Request { Objects.requireNonNull(id); Objects.requireNonNull(player); text(selector, 256); }
    }
    public record Ready(UUID player) implements Message { public Ready { Objects.requireNonNull(player); } }
    public record Intent(UUID id, UUID player, UUID claim, long deadline) implements Message {
        public Intent { Objects.requireNonNull(id); Objects.requireNonNull(player); Objects.requireNonNull(claim); if (deadline <= 0) throw new IllegalArgumentException("deadline"); }
    }
    public record Ack(UUID id, UUID player) implements Message {
        public Ack { Objects.requireNonNull(id); Objects.requireNonNull(player); }
    }
    public record Status(UUID id, UUID player, String key) implements Message {
        public Status { Objects.requireNonNull(id); Objects.requireNonNull(player); if (!STATUSES.contains(key)) throw new IllegalArgumentException("status"); }
    }
    public record Names(UUID player, List<String> names) implements Message {
        public Names { Objects.requireNonNull(player); if (names.size() > 200) throw new IllegalArgumentException("names"); names = List.copyOf(names); names.forEach(name -> text(name, 256)); }
    }
    private TeleportWire() {}
    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("text");
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static byte[] mac(byte[] bytes, String secret) {
        try {
            if (secret == null || secret.length() < 32) throw new IllegalArgumentException("secret");
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return mac.doFinal(bytes);
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    public static byte[] encode(Message message, String secret, long now) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeByte(VERSION); out.writeLong(now);
            if (message instanceof Catalog c) {
                out.writeByte(0); out.writeLong(c.generation()); out.writeInt(c.page()); out.writeInt(c.pages()); out.writeInt(c.entries().size());
                for (Entry e : c.entries()) { uuid(out, e.id()); out.writeBoolean(e.owner() != null); if (e.owner() != null) uuid(out, e.owner()); out.writeUTF(e.name()); }
            } else if (message instanceof Request r) { out.writeByte(1); uuid(out, r.id()); uuid(out, r.player()); out.writeUTF(r.selector()); out.writeBoolean(r.admin()); }
            else if (message instanceof Ready r) { out.writeByte(2); uuid(out, r.player()); }
            else if (message instanceof Intent i) { out.writeByte(3); uuid(out, i.id()); uuid(out, i.player()); uuid(out, i.claim()); out.writeLong(i.deadline()); }
            else if (message instanceof Ack a) { out.writeByte(4); uuid(out, a.id()); uuid(out, a.player()); }
            else if (message instanceof Status s) { out.writeByte(5); uuid(out, s.id()); uuid(out, s.player()); out.writeUTF(s.key()); }
            else if (message instanceof Names n) { out.writeByte(6); uuid(out, n.player()); out.writeInt(n.names().size()); for (String name : n.names()) out.writeUTF(name); }
            byte[] body = bytes.toByteArray(); if (body.length + 32 > MAX_BYTES) throw new IllegalArgumentException("packet size");
            bytes.write(mac(body, secret)); return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    public static Message decode(byte[] packet, String secret, long now) throws IOException {
        if (packet.length < 46 || packet.length > MAX_BYTES) throw new IOException("packet size");
        byte[] body = Arrays.copyOf(packet, packet.length - 32), signature = Arrays.copyOfRange(packet, packet.length - 32, packet.length);
        if (!MessageDigest.isEqual(mac(body, secret), signature)) throw new IOException("signature");
        try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("protocol");
            long sent = in.readLong(); if (sent < now - 30000 || sent > now + 30000) throw new IOException("expired");
            Message result;
            switch (in.readUnsignedByte()) {
                case 0 -> {
                    long generation = in.readLong(); int page = in.readInt(), pages = in.readInt(), count = bounded(in.readInt(), PAGE_SIZE);
                    List<Entry> entries = new ArrayList<>();
                    for (int i = 0; i < count; i++) { UUID id = uuid(in); UUID owner = in.readBoolean() ? uuid(in) : null; entries.add(new Entry(id, owner, in.readUTF())); }
                    result = new Catalog(generation, page, pages, entries);
                }
                case 1 -> result = new Request(uuid(in), uuid(in), in.readUTF(), in.readBoolean());
                case 2 -> result = new Ready(uuid(in));
                case 3 -> result = new Intent(uuid(in), uuid(in), uuid(in), in.readLong());
                case 4 -> result = new Ack(uuid(in), uuid(in));
                case 5 -> result = new Status(uuid(in), uuid(in), in.readUTF());
                case 6 -> { UUID player = uuid(in); int count = bounded(in.readInt(), 200); List<String> names = new ArrayList<>(); for (int i = 0; i < count; i++) names.add(in.readUTF()); result = new Names(player, names); }
                default -> throw new IOException("message type");
            }
            if (in.available() != 0) throw new IOException("trailing data"); return result;
        } catch (IllegalArgumentException error) { throw new IOException("invalid message", error); }
    }
    private static int bounded(int value, int maximum) throws IOException { if (value < 0 || value > maximum) throw new IOException("count"); return value; }
}
