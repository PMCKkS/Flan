package top.pmckk.flan.model;

import java.util.Locale;
import top.pmckk.flan.config.MessageException;

public enum ClaimPermission {
    BUILD, CONTAINER, INTERACT, ENTITY, PVP, EXPLOSION, FIRE, FLUID, PISTON, MOB_GRIEF,
    PICKUP, DROP, TELEPORT, ENTER, MANAGE;
    public boolean personal() { return switch (this) { case PVP, EXPLOSION, FIRE, FLUID, PISTON, MOB_GRIEF -> false; default -> true; }; }
    public static ClaimPermission parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException e) { throw new MessageException("errors.unknown-permission", "permission", value); }
    }
}
