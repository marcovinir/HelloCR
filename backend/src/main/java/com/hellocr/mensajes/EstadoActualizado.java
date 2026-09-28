package com.hellocr.mensajes;

import java.util.UUID;

/** Cambiaron las marcas de un miembro. Tiempo real lo reparte como ESTADO_ACTUALIZADO después del commit. */
public record EstadoActualizado(UUID conversacionId, UUID usuarioId, long ultimaEntregada, long ultimaLeida) {
}
