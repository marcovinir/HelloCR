package com.hellocr.tiempoReal;

import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.mensajes.EstadoActualizado;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.MensajeEnviado;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Reparte los eventos de dominio por /user/queue/eventos recién después del commit (spec 7.3). */
@Component
public class EntregaTiempoReal {

    private final ConsultaMembresia membresia;
    private final EnviadorEventos enviador;

    public EntregaTiempoReal(ConsultaMembresia membresia, EnviadorEventos enviador) {
        this.membresia = membresia;
        this.enviador = enviador;
    }

    /** A quienes pueden ver esa secuencia, incluido el remitente en todos sus dispositivos. */
    @TransactionalEventListener
    public void alEnviarMensaje(MensajeEnviado evento) {
        MensajeDto mensaje = evento.mensaje();
        EventoMensajeNuevo nuevo = new EventoMensajeNuevo(mensaje);
        membresia.quienesPuedenVer(mensaje.conversacionId(), mensaje.secuencia())
                .forEach(usuario -> enviador.enviar(usuario, nuevo));
    }

    /** A los miembros activos, incluidos los otros dispositivos de quien marcó. */
    @TransactionalEventListener
    public void alActualizarEstado(EstadoActualizado evento) {
        EventoEstado estado = new EventoEstado(evento);
        membresia.miembrosActivos(evento.conversacionId()).forEach(usuario -> enviador.enviar(usuario, estado));
    }
}
