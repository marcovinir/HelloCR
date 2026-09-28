package com.hellocr.mensajes;

import java.util.List;

/** Mensajes en orden ascendente de secuencia. hayMas indica si quedan más en la dirección pedida. */
public record PaginaMensajes(List<MensajeDto> mensajes, boolean hayMas) {
}
