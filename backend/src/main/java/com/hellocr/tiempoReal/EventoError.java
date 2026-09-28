package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.util.UUID;

/** idCliente viene solo en los errores de /app/mensajes.enviar: así el celular sabe qué mensaje marcar con ❗. */
public record EventoError(String tipo, UUID idCliente, String codigo, String detalle) {

    public static EventoError de(UUID idCliente, ErrorNegocio error) {
        return new EventoError("ERROR", idCliente, error.codigo().name(), error.getMessage());
    }

    public static EventoError de(CodigoError codigo, String detalle) {
        return new EventoError("ERROR", null, codigo.name(), detalle);
    }
}
