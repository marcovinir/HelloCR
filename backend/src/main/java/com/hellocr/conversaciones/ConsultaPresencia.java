package com.hellocr.conversaciones;

import java.util.UUID;

/** ¿Tiene alguna sesión en tiempo real abierta? La implementa tiempoReal.RegistroSesiones. */
public interface ConsultaPresencia {

    boolean enLinea(UUID usuarioId);
}
