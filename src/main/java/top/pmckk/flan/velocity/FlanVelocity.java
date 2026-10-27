package top.pmckk.flan.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import top.pmckk.flan.network.*;
import top.pmckk.flan.network.TeleportWire.*;
import top.pmckk.flan.network.TeleportTickets.Ticket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

@Plugin(id = "flan", name = "Flan", authors = {"PMCKK"})
public final class FlanVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(TeleportWire.CHANNEL);
    private final CatalogPages pages = new CatalogPages();
    private final ClaimDirectory claims = new ClaimDirectory();
    private final TeleportTickets tickets = new TeleportTickets();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicLong lastWarning = new AtomicLong();
    private ProxyFiles files;
    private ScheduledTask task;
    private volatile boolean active;

    @Inject public FlanVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy; this.logger = logger; this.directory = directory;
    }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            files = new ProxyFiles(directory);
            files.catalog().forEach((server, entries) -> { if (proxy.getServer(server).isPresent()) claims.replace(server, entries); });
            proxy.getChannelRegistrar().register(channel); active = true;
            task = proxy.getScheduler().buildTask(this, this::tick).repeat(1, TimeUnit.SECONDS).schedule();
            logger.info(plain(files.notification("logs.velocity-enabled")));
        } catch (Exception error) {
            logger.error(files == null ? "Flan" : plain(files.notification("logs.velocity-failed")), error);
        }
    }
    private static String plain(String value) { return value.replaceAll("(?i)&[0-9a-fk-or]", ""); }
    private void tell(Player player, String key) { player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(files.notification(key))); }
    private byte[] encode(Message message) { return TeleportWire.encode(message, files.secret(), System.currentTimeMillis()); }
    private void status(ServerConnection connection, Request request, String key) { connection.sendPluginMessage(channel, encode(new Status(request.id(), request.player(), key))); }
    @Subscribe public void message(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!active || !(event.getSource() instanceof ServerConnection source)) return;
        String server = source.getServer().getServerInfo().getName();
        if (proxy.getServer(server).isEmpty()) return;
        try {
            Message message = TeleportWire.decode(event.getData(), files.secret(), System.currentTimeMillis());
            if (message instanceof Catalog catalog) {
                pages.accept(server, catalog, System.currentTimeMillis()).ifPresent(entries -> {
                    if (claims.replace(server, entries)) { dirty.set(true); for (Player player : proxy.getAllPlayers()) names(player); }
                });
            } else if (message instanceof Request request) {
                if (!source.getPlayer().getUniqueId().equals(request.player()) || !onServer(source.getPlayer(), server)) return;
                request(source, request);
            } else if (message instanceof Ready ready) {
                if (!source.getPlayer().getUniqueId().equals(ready.player()) || !onServer(source.getPlayer(), server)) return;
                names(source.getPlayer()); deliver(source.getPlayer(), server);
            } else if (message instanceof Ack ack) {
                if (!source.getPlayer().getUniqueId().equals(ack.player())) return;
                tickets.acknowledge(ack.id(), ack.player(), server);
            }
        } catch (Exception error) { warn(error); }
    }
    private boolean onServer(Player player, String server) { return player.getCurrentServer().map(connection -> connection.getServer().getServerInfo().getName().equals(server)).orElse(false); }
    private void request(ServerConnection source, Request request) {
        ClaimDirectory.Destination destination;
        try { destination = claims.resolve(request.player(), request.selector(), request.admin()); }
        catch (ClaimDirectory.LookupException error) { status(source, request, error.key()); return; }
        var target = proxy.getServer(destination.server());
        if (target.isEmpty()) { status(source, request, "network.failed"); return; }
        Ticket ticket = new Ticket(request.id(), request.player(), destination.server(), destination.entry().id(),
                System.currentTimeMillis() + files.timeout() * 1000L, false);
        if (!tickets.open(ticket, System.currentTimeMillis())) { status(source, request, "network.busy"); return; }
        status(source, request, "network.accepted");
        Player player = source.getPlayer();
        if (onServer(player, destination.server())) { deliver(player, destination.server()); return; }
        player.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            if (error != null || !result.isSuccessful()) {
                if (tickets.cancel(request.id(), request.player())) tell(player, "network.failed");
            } else if (onServer(player, destination.server())) deliver(player, destination.server());
        });
    }
    private void names(Player player) {
        player.getCurrentServer().ifPresent(connection -> connection.sendPluginMessage(channel, encode(new Names(player.getUniqueId(), claims.names(player.getUniqueId(), false)))));
    }
    private void sendIntent(Ticket ticket) {
        proxy.getPlayer(ticket.player()).filter(player -> onServer(player, ticket.server())).flatMap(Player::getCurrentServer)
                .ifPresent(connection -> connection.sendPluginMessage(channel, encode(new Intent(ticket.id(), ticket.player(), ticket.claim(), ticket.deadline()))));
    }
    private void deliver(Player player, String server) { tickets.deliver(player.getUniqueId(), server, System.currentTimeMillis()).ifPresent(this::sendIntent); }
    @Subscribe public void connected(ServerPostConnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(connection -> deliver(event.getPlayer(), connection.getServer().getServerInfo().getName()));
    }
    @Subscribe public void disconnected(DisconnectEvent event) { tickets.disconnect(event.getPlayer().getUniqueId()); }
    private void tick() {
        long now = System.currentTimeMillis();
        for (Ticket expired : tickets.expire(now)) proxy.getPlayer(expired.player()).ifPresent(player -> tell(player, "network.timeout"));
        // Retry until the destination acknowledges; the backend executes each request ID only once.
        for (Ticket ticket : tickets.snapshot()) proxy.getPlayer(ticket.player()).filter(player -> onServer(player, ticket.server())).ifPresent(player -> {
            if (ticket.delivered()) sendIntent(ticket); else deliver(player, ticket.server());
        });
        if (dirty.getAndSet(false)) try { files.save(claims.snapshot()); }
        catch (Exception error) { dirty.set(true); warn(error); }
    }
    private void warn(Throwable error) {
        long now = System.currentTimeMillis(), last = lastWarning.get();
        if (now - last >= 10000 && lastWarning.compareAndSet(last, now)) logger.warn(plain(files.notification("logs.velocity-message")), error);
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        active = false; if (task != null) task.cancel(); proxy.getChannelRegistrar().unregister(channel);
        if (files != null) try { files.save(claims.snapshot()); } catch (Exception error) { warn(error); }
    }
}
