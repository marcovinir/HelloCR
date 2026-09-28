package com.hellocr.tiempoReal;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Lleva el registro de sesiones y avisa la presencia al conectarse y al desconectarse. */
@Component
public class EventosDeSesion {

    private final RegistroSesiones registro;
    private final PresenciaService presencia;

    public EventosDeSesion(RegistroSesiones registro, PresenciaService presencia) {
        this.registro = registro;
        this.presencia = presencia;
    }

    @EventListener
    public void alConectar(SessionConnectedEvent evento) {
        if (evento.getUser() instanceof UsuarioStomp usuario) {
            String sesionId = SimpMessageHeaderAccessor.getSessionId(evento.getMessage().getHeaders());
            if (registro.abrir(sesionId, usuario.id(), usuario.venceEn())) {
                presencia.conectado(usuario.id());
            }
        }
    }

    @EventListener
    public void alDesconectar(SessionDisconnectEvent evento) {
        registro.cerrar(evento.getSessionId()).ifPresent(presencia::desconectado);
    }
}
