package com.hellocr.tiempoReal;

import com.hellocr.mensajes.EstadoActualizado;
import java.util.UUID;

public record EventoEstado(String tipo, UUID conversacionId, UUID usuarioId, long ultimaEntregada, long ultimaLeida) {

    public EventoEstado(EstadoActualizado estado) {
        this("ESTADO_ACTUALIZADO", estado.conversacionId(), estado.usuarioId(), estado.ultimaEntregada(),
                estado.ultimaLeida());
    }
}
