package com.hellocr.tiempoReal;

import com.hellocr.comun.ErrorNegocio;
import java.util.UUID;

/** Un ErrorNegocio al enviar un mensaje, junto con el idCliente de ese mensaje. */
public class ErrorDeEnvio extends RuntimeException {

    private final UUID idCliente;
    private final ErrorNegocio causa;

    public ErrorDeEnvio(UUID idCliente, ErrorNegocio causa) {
        super(causa.getMessage(), causa);
        this.idCliente = idCliente;
        this.causa = causa;
    }

    public UUID idCliente() {
        return idCliente;
    }

    public ErrorNegocio causa() {
        return causa;
    }
}
