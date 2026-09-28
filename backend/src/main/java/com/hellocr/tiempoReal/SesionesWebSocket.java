package com.hellocr.tiempoReal;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** Guarda las conexiones abiertas para poder cerrarlas desde el servidor. El id es el mismo de la sesión STOMP. */
@Component
public class SesionesWebSocket {

    private static final Logger log = LoggerFactory.getLogger(SesionesWebSocket.class);

    private final Map<String, WebSocketSession> abiertas = new ConcurrentHashMap<>();

    public WebSocketHandler decorar(WebSocketHandler manejador) {
        return new WebSocketHandlerDecorator(manejador) {
            @Override
            public void afterConnectionEstablished(WebSocketSession sesion) throws Exception {
                abiertas.put(sesion.getId(), sesion);
                super.afterConnectionEstablished(sesion);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) throws Exception {
                abiertas.remove(sesion.getId());
                super.afterConnectionClosed(sesion, estado);
            }
        };
    }

    public void cerrar(String sesionId) {
        WebSocketSession sesion = abiertas.get(sesionId);
        if (sesion == null || !sesion.isOpen()) {
            return;
        }
        try {
            sesion.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException error) {
            log.warn("No se pudo cerrar la sesión {}", sesionId, error);
        }
    }
}
