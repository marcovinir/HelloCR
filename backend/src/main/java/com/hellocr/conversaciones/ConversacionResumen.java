package com.hellocr.conversaciones;

import com.hellocr.mensajes.MensajeDto;
import java.util.UUID;

/** Una fila de la lista de chats (spec 9.2). ultimaSecuencia es la última visible; 0 si no hay mensajes. */
public record ConversacionResumen(UUID id, TipoConversacion tipo, String titulo, OtroUsuario otroUsuario,
        MensajeDto ultimoMensaje, long ultimaSecuencia, long noLeidos, boolean activa) {
}
