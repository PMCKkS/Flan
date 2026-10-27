package top.pmckk.flan.model;

import java.util.*;

public record Claim(UUID id, UUID world, UUID owner, UUID parent, String name, Bounds bounds,
                    Map<ClaimPermission, Boolean> flags, Map<UUID, String> members,
                    Map<String, Set<ClaimPermission>> groups, Boolean threeDimensional,
                    Map<UUID, Map<ClaimPermission, Boolean>> playerFlags, Set<UUID> excludedMembers,
                    String entryTitle, String entrySubtitle, TeleportPoint teleportPoint) {
    public Claim(UUID id, UUID world, UUID owner, UUID parent, String name, Bounds bounds,
                 Map<ClaimPermission, Boolean> flags, Map<UUID, String> members, Map<String, Set<ClaimPermission>> groups, Boolean mode,
                 Map<UUID, Map<ClaimPermission, Boolean>> playerFlags, Set<UUID> excludedMembers, String entryTitle, String entrySubtitle) {
        this(id, world, owner, parent, name, bounds, flags, members, groups, mode, playerFlags, excludedMembers, entryTitle, entrySubtitle, null);
    }
    public Claim(UUID id, UUID world, UUID owner, UUID parent, String name, Bounds bounds,
                 Map<ClaimPermission, Boolean> flags, Map<UUID, String> members, Map<String, Set<ClaimPermission>> groups, Boolean mode,
                 Map<UUID, Map<ClaimPermission, Boolean>> playerFlags, Set<UUID> excludedMembers) {
        this(id, world, owner, parent, name, bounds, flags, members, groups, mode, playerFlags, excludedMembers, null, null);
    }
    public Claim(UUID id, UUID world, UUID owner, UUID parent, String name, Bounds bounds,
                 Map<ClaimPermission, Boolean> flags, Map<UUID, String> members, Map<String, Set<ClaimPermission>> groups, Boolean mode) {
        this(id, world, owner, parent, name, bounds, flags, members, groups, mode, Map.of(), Set.of());
    }
    /** A missing mode belongs to an older save and is resolved against the world's build heights. */
    public Claim(UUID id, UUID world, UUID owner, UUID parent, String name, Bounds bounds,
                 Map<ClaimPermission, Boolean> flags, Map<UUID, String> members, Map<String, Set<ClaimPermission>> groups) {
        this(id, world, owner, parent, name, bounds, flags, members, groups, null);
    }
    public Claim {
        validateEntryText(entryTitle); validateEntryText(entrySubtitle);
        Objects.requireNonNull(id); Objects.requireNonNull(world); Objects.requireNonNull(name); Objects.requireNonNull(bounds);
        if (teleportPoint != null && !teleportPoint.inside(bounds)) throw new top.pmckk.flan.config.MessageException("errors.teleport-outside");
        flags = Map.copyOf(flags); members = Map.copyOf(members);
        Map<String, Set<ClaimPermission>> copied = new HashMap<>();
        groups.forEach((key, value) -> copied.put(key, Set.copyOf(value)));
        groups = Map.copyOf(copied);
        Map<UUID, Map<ClaimPermission, Boolean>> individual = new HashMap<>();
        playerFlags.forEach((player, values) -> {
            if (values.keySet().stream().anyMatch(permission -> !permission.personal())) throw new top.pmckk.flan.config.MessageException("errors.personal-only");
            individual.put(player, Map.copyOf(values));
        });
        playerFlags = Map.copyOf(individual); excludedMembers = Set.copyOf(excludedMembers);
    }
    public boolean admin() { return owner == null; }
    public boolean isThreeDimensional(int worldMinimum, int worldMaximumExclusive) {
        return threeDimensional != null ? threeDimensional : bounds.minY() > worldMinimum || bounds.maxY() < worldMaximumExclusive - 1;
    }
    public Claim renamed(String value) { return new Claim(id, world, owner, parent, value, bounds, flags, members, groups, threeDimensional, playerFlags, excludedMembers, entryTitle, entrySubtitle, teleportPoint); }
    public Claim resized(Bounds value) { return resized(value, threeDimensional); }
    public Claim resized(Bounds value, Boolean mode) { return new Claim(id, world, owner, parent, name, value, flags, members, groups, mode, playerFlags, excludedMembers, entryTitle, entrySubtitle, teleportPoint != null && teleportPoint.inside(value) ? teleportPoint : null); }
    public Claim transferred(UUID value) { return new Claim(id, world, value, parent, name, bounds, flags, members, groups, threeDimensional, playerFlags, excludedMembers, entryTitle, entrySubtitle, teleportPoint); }
    public Claim withFlags(Map<ClaimPermission, Boolean> value) { return new Claim(id, world, owner, parent, name, bounds, value, members, groups, threeDimensional, playerFlags, excludedMembers, entryTitle, entrySubtitle, teleportPoint); }
    public Claim withMembers(Map<UUID, String> value) { return new Claim(id, world, owner, parent, name, bounds, flags, value, groups, threeDimensional, playerFlags, excludedMembers, entryTitle, entrySubtitle, teleportPoint); }
    public Claim withGroups(Map<String, Set<ClaimPermission>> value) { return new Claim(id, world, owner, parent, name, bounds, flags, members, value, threeDimensional, playerFlags, excludedMembers, entryTitle, entrySubtitle, teleportPoint); }
    public Claim withTrust(Map<UUID, String> members, Map<UUID, Map<ClaimPermission, Boolean>> individual, Set<UUID> excluded) {
        return new Claim(id, world, owner, parent, name, bounds, flags, members, groups, threeDimensional, individual, excluded, entryTitle, entrySubtitle, teleportPoint);
    }
    public static void validateEntryText(String text) {
        if (text != null && (text.length() > 128 || text.chars().anyMatch(Character::isISOControl)))
            throw new top.pmckk.flan.config.MessageException("errors.entry-text");
    }
    public Claim withEntryText(boolean subtitle, String text) {
        return new Claim(id, world, owner, parent, name, bounds, flags, members, groups, threeDimensional,
                playerFlags, excludedMembers, subtitle ? entryTitle : text, subtitle ? text : entrySubtitle, teleportPoint);
    }
    public Claim withTeleportPoint(TeleportPoint point) {
        if (point != null && !point.inside(bounds)) throw new top.pmckk.flan.config.MessageException("errors.teleport-outside");
        return new Claim(id, world, owner, parent, name, bounds, flags, members, groups, threeDimensional,
                playerFlags, excludedMembers, entryTitle, entrySubtitle, point);
    }
}
