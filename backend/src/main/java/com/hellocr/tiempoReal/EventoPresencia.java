package com.hellocr.tiempoReal;

import java.time.Instant;
import java.util.UUID;

public record EventoPresencia(String tipo, UUID usuarioId, boolean enLinea, Instant ultimaConexion) {

    public EventoPresencia(UUID usuarioId, boolean enLinea, Instant ultimaConexion) {
        this("PRESENCIA", usuarioId, enLinea, ultimaConexion);
    }
}
