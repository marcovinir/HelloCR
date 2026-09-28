package com.hellocr.conversaciones;

import java.util.Set;
import java.util.UUID;

/** Cambió un grupo (miembros, roles, nombre). Los afectados vuelven a pedir la lista y el detalle. */
public record ConversacionActualizada(UUID conversacionId, Set<UUID> afectados) {
}
