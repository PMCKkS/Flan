package top.pmckk.flan.model;

import top.pmckk.flan.config.MessageException;

/** Coordinates belong to the claim's world and to the backend holding that claim. */
public record TeleportPoint(double x, double y, double z, float yaw, float pitch) {
    public TeleportPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(x) > 30000000 || Math.abs(z) > 30000000 || Math.abs(y) > 100000 || pitch < -90 || pitch > 90)
            throw new MessageException("errors.teleport-point");
    }
    public boolean inside(Bounds bounds) { return bounds.contains((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)); }
}
