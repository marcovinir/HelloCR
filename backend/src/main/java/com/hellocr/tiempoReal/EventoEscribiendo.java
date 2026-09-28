package com.hellocr.tiempoReal;

import java.util.UUID;

public record EventoEscribiendo(String tipo, UUID conversacionId, UUID usuarioId) {

    public EventoEscribiendo(UUID conversacionId, UUID usuarioId) {
        this("ESCRIBIENDO", conversacionId, usuarioId);
    }
}
