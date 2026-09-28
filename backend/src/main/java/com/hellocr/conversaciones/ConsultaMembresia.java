package com.hellocr.conversaciones;

import java.util.Set;
import java.util.UUID;

/** Preguntas sobre quién está en una conversación y qué puede ver. La usan mensajes, grupos y tiempo real. */
public interface ConsultaMembresia {

    /** Tiene un periodo abierto. */
    boolean esMiembroActivo(UUID conversacionId, UUID usuarioId);

    /** Estuvo alguna vez (tiene fila en miembros), aunque ya haya salido. */
    boolean fueMiembro(UUID conversacionId, UUID usuarioId);

    boolean puedeVer(UUID conversacionId, UUID usuarioId, long secuencia);

    Set<UUID> quienesPuedenVer(UUID conversacionId, long secuencia);

    Set<UUID> miembrosActivos(UUID conversacionId);
}
