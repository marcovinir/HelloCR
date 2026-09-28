package com.hellocr.tiempoReal;

import java.util.UUID;

public record EventoConversacion(String tipo, UUID conversacionId) {

    public EventoConversacion(UUID conversacionId) {
        this("CONVERSACION_ACTUALIZADA", conversacionId);
    }
}
