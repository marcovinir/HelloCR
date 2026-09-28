package com.hellocr.tiempoReal;

import java.util.UUID;

/** Manda un evento a /user/queue/eventos de todas las sesiones abiertas del usuario (si no tiene, no pasa nada). */
public interface EnviadorEventos {

    void enviar(UUID usuarioId, Object evento);
}
