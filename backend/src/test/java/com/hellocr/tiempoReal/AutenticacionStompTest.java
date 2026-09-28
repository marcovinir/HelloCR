package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * Con setPreserveReceiveOrder, los frames que llegan detrás de un CONNECT rechazado se procesan antes de que se
 * cierre la conexión: el interceptor tiene que frenarlos aunque su destino sea válido.
 */
class AutenticacionStompTest {

    private final List<Message<?>> salida = new ArrayList<>();
    private final AutenticacionStomp autenticacion = new AutenticacionStomp(token -> {
        throw new BadJwtException("No se usa en estos tests.");
    }, (mensaje, espera) -> salida.add(mensaje));

    @Test
    void sinUnConnectValidoNingunFrameLlegaALaAplicacion() {
        assertThat(autenticacion.preSend(frame(StompCommand.SEND, "/app/escribiendo", null), null)).isNull();
        assertThat(autenticacion.preSend(frame(StompCommand.SUBSCRIBE, AutenticacionStomp.COLA_EVENTOS, null), null))
                .isNull();

        assertThat(salida).hasSize(2).allSatisfy(error -> {
            StompHeaderAccessor cabeceras = StompHeaderAccessor.wrap(error);
            assertThat(cabeceras.getCommand()).isEqualTo(StompCommand.ERROR);
            assertThat(cabeceras.getMessage()).isEqualTo("Falta el token o no es válido.");
            assertThat(cabeceras.getSessionId()).isEqualTo("sesion-1");
        });
    }

    @Test
    void conUnUsuarioAutenticadoElFramePasa() {
        UsuarioStomp ana = new UsuarioStomp(UUID.randomUUID(), Instant.parse("2026-10-01T15:15:00Z"));
        Message<byte[]> envio = frame(StompCommand.SEND, "/app/escribiendo", ana);

        assertThat(autenticacion.preSend(envio, null)).isSameAs(envio);
        assertThat(salida).isEmpty();
    }

    private static Message<byte[]> frame(StompCommand comando, String destino, UsuarioStomp usuario) {
        StompHeaderAccessor cabeceras = StompHeaderAccessor.create(comando);
        cabeceras.setSessionId("sesion-1");
        cabeceras.setDestination(destino);
        cabeceras.setUser(usuario);
        cabeceras.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], cabeceras.getMessageHeaders());
    }
}
