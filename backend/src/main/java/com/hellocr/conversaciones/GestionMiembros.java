package com.hellocr.conversaciones;

import java.util.Optional;
import java.util.UUID;

/** Altas, bajas y roles de los miembros. Solo la usan los chats directos al crearse y los grupos. */
public interface GestionMiembros {

    /** Crea (o reutiliza) la fila del miembro con ese rol y abre un periodo desde esa secuencia. */
    void incorporar(UUID conversacionId, UUID usuarioId, Rol rol, long desdeSecuencia);

    /** Cierra el periodo abierto en esa secuencia (inclusive) y deja el rol en MIEMBRO. */
    void retirar(UUID conversacionId, UUID usuarioId, long hastaSecuencia);

    /** El rol de un miembro activo; vacío si no tiene un periodo abierto. */
    Optional<Rol> rolActivo(UUID conversacionId, UUID usuarioId);

    void cambiarRol(UUID conversacionId, UUID usuarioId, Rol rol);

    int contarActivos(UUID conversacionId);

    int contarAdministradoresActivos(UUID conversacionId);

    /** El miembro activo con el periodo abierto más antiguo. */
    Optional<UUID> activoMasAntiguo(UUID conversacionId);
}
