package com.hellocr.mensajes;

/** Se guardó un mensaje nuevo (texto o evento). Tiempo real lo reparte después del commit. */
public record MensajeEnviado(MensajeDto mensaje) {
}
