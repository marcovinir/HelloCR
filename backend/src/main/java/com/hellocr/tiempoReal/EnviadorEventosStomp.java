package com.hellocr.tiempoReal;

import java.util.UUID;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

@Component
public class EnviadorEventosStomp implements EnviadorEventos {

    /** Sin el prefijo /user: Spring lo agrega y lo resuelve a las sesiones del usuario. */
    public static final String DESTINO = "/queue/eventos";

    private final SimpMessagingTemplate plantilla;
    private final SimpUserRegistry usuarios;

    public EnviadorEventosStomp(SimpMessagingTemplate plantilla, SimpUserRegistry usuarios) {
        this.plantilla = plantilla;
        this.usuarios = usuarios;
    }

    /**
     * Un mensaje por sesión, con su sessionId. Spring 7.0 con setPreservePublishOrder(true) no puede repartir un
     * mismo mensaje a varias sesiones: desde la segunda falla ("Expected mutable SimpMessageHeaderAccessor") y ese
     * dispositivo se queda sin el evento.
     */
    @Override
    public void enviar(UUID usuarioId, Object evento) {
        SimpUser usuario = usuarios.getUser(usuarioId.toString());
        if (usuario == null) {
            return;
        }
        for (SimpSession sesion : usuario.getSessions()) {
            SimpMessageHeaderAccessor cabeceras = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            cabeceras.setSessionId(sesion.getId());
            cabeceras.setLeaveMutable(true);
            plantilla.convertAndSendToUser(usuarioId.toString(), DESTINO, evento, cabeceras.getMessageHeaders());
        }
    }
}
