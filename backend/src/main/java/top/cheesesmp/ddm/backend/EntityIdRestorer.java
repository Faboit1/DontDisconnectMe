package top.cheesesmp.ddm.backend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.entity.Player;

/**
 * Gives a reconnecting player back the entity id they had a moment ago.
 *
 * <p>This is what lets the proxy skip the join-game packet on a reconnect. That
 * packet is the only thing that tells a client its entity id changed, and it is
 * also what makes the client throw its world away and show the loading screen.
 * If the id never changes, the packet is not needed and the world stays on
 * screen.
 *
 * <p>The id is safe to reuse: the player's old entity was removed from the
 * world when they dropped, so nothing else is using it.
 *
 * <p>Server internals are reached reflectively and resolved once at startup. If
 * anything is missing, {@link #supported()} reports false, the plugin tells the
 * proxy it cannot help, and the proxy falls back to an ordinary reconnect.
 */
public final class EntityIdRestorer {

    private final boolean supported;
    private final String unsupportedReason;
    private final Method getHandle;
    private final Method setId;
    private final Field idField;

    public EntityIdRestorer(Player sample) {
        Method handle = null;
        Method setter = null;
        Field field = null;
        String reason = "";
        try {
            handle = sample.getClass().getMethod("getHandle");
            handle.setAccessible(true);
            Class<?> entityClass = handle.getReturnType();

            setter = findSetId(entityClass);
            if (setter == null) {
                field = findIdField(entityClass);
            }
            if (setter == null && field == null) {
                reason = "no setId(int) or int id field on " + entityClass.getName();
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }

        this.getHandle = handle;
        this.setId = setter;
        this.idField = field;
        this.supported = handle != null && (setter != null || field != null);
        this.unsupportedReason = supported ? "" : (reason.isEmpty() ? "getHandle unavailable" : reason);
    }

    private static Method findSetId(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("setId", int.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Try the superclass; the id lives on the base Entity type.
            }
        }
        return null;
    }

    private static Field findIdField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field candidate : current.getDeclaredFields()) {
                if (candidate.getType() == int.class && candidate.getName().equals("id")) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
        }
        return null;
    }

    public boolean supported() {
        return supported;
    }

    public String unsupportedReason() {
        return unsupportedReason;
    }

    /**
     * @return true if the player now carries {@code entityId}
     */
    public boolean restore(Player player, int entityId) {
        if (!supported || player.getEntityId() == entityId) {
            return supported;
        }
        try {
            Object handle = getHandle.invoke(player);
            if (setId != null) {
                setId.invoke(handle, entityId);
            } else {
                idField.setInt(handle, entityId);
            }
            return player.getEntityId() == entityId;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
