package com.hellocr.correo;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DespachadorCorreo {

    private static final Logger log = LoggerFactory.getLogger(DespachadorCorreo.class);

    private final EnviadorCorreo enviador;
    private final Executor ejecutor;

    public DespachadorCorreo(EnviadorCorreo enviador, CorreoProperties propiedades) {
        this.enviador = enviador;
        this.ejecutor = propiedades.asincrono() ? Executors.newVirtualThreadPerTaskExecutor() : Runnable::run;
    }

    /**
     * Después del commit: si la transacción se revierte, el correo no sale. Sin transacción, sale enseguida.
     * Un fallo del servidor de correo se registra y no afecta la respuesta; la persona puede pedir un reenvío.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void alConfirmar(CorreoPendiente pendiente) {
        ejecutor.execute(() -> enviar(pendiente.correo()));
    }

    private void enviar(CorreoSaliente correo) {
        try {
            enviador.enviar(correo);
        } catch (RuntimeException error) {
            log.error("No se pudo enviar el correo \"{}\" a {}", correo.asunto(), correo.destinatario(), error);
        }
    }
}
