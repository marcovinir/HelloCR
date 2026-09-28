package com.hellocr.tiempoReal;

import com.hellocr.mensajes.MensajeDto;

public record EventoMensajeNuevo(String tipo, MensajeDto mensaje) {

    public EventoMensajeNuevo(MensajeDto mensaje) {
        this("MENSAJE_NUEVO", mensaje);
    }
}
