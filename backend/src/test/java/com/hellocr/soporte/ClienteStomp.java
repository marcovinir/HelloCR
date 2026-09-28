package com.hellocr.soporte;

import com.hellocr.tiempoReal.AutenticacionStomp;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

/** Cliente STOMP de pruebas: se conecta a /ws, se suscribe a /user/queue/eventos y guarda lo que le llega. */
public class ClienteStomp {

    private static final Duration ESPERA = Duration.ofSeconds(5);

    private record Recibido(JsonNode evento, AtomicBoolean leido) {
    }

    private final String url;
    private final WebSocketHttpHeaders cabecerasHandshake = new WebSocketHttpHeaders();
    private final WebSocketStompClient cliente;
    private final List<Recibido> recibidos = new CopyOnWriteArrayList<>();
    private final List<String> erroresStomp = new CopyOnWriteArrayList<>();
    private final CountDownLatch cerrado = new CountDownLatch(1);
    private StompSession sesion;

    public ClienteStomp(String url) {
        this.url = url;
        this.cliente = new WebSocketStompClient(new StandardWebSocketClient());
        this.cliente.setMessageConverter(new CompositeMessageConverter(
                List.of(new ByteArrayMessageConverter(), new JacksonJsonMessageConverter())));
    }

    public ClienteStomp conOrigen(String origen) {
        cabecerasHandshake.setOrigin(origen);
        return this;
    }

    /** Conecta con ese access token (o sin token si es null) y se suscribe a la cola de eventos. */
    public void conectar(String token) throws Exception {
        StompHeaders conexion = new StompHeaders();
        if (token != null) {
            conexion.add("Authorization", "Bearer " + token);
        }
        sesion = cliente.connectAsync(url, cabecerasHandshake, conexion, new ManejadorSesion())
                .get(ESPERA.toSeconds(), TimeUnit.SECONDS);
        suscribir(AutenticacionStomp.COLA_EVENTOS);
    }

    public void suscribir(String destino) {
        sesion.subscribe(destino, new ManejadorEventos());
    }

    /** El cuerpo se envía como JSON. */
    public void enviar(String destino, Object cuerpo) {
        sesion.send(destino, cuerpo);
    }

    /** El cuerpo se envía tal cual, como application/octet-stream. */
    public void enviarBytes(String destino, byte[] cuerpo) {
        sesion.send(destino, cuerpo);
    }

    public void renovar(String token) {
        StompHeaders cabeceras = new StompHeaders();
        cabeceras.setDestination("/app/sesion.renovar");
        cabeceras.add("Authorization", "Bearer " + token);
        sesion.send(cabeceras, new byte[0]);
    }

    /** Espera el próximo evento de ese tipo que todavía no se leyó y lo marca como leído. */
    public JsonNode esperar(String tipo) throws InterruptedException {
        long limite = System.nanoTime() + ESPERA.toNanos();
        while (System.nanoTime() < limite) {
            for (Recibido recibido : recibidos) {
                if (tipo.equals(recibido.evento().path("tipo").asString())
                        && recibido.leido().compareAndSet(false, true)) {
                    return recibido.evento();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No llegó ningún evento " + tipo + ". Llegaron: " + recibidos);
    }

    /** Falla si durante la espera llega un evento de ese tipo que nadie leyó. */
    public void sinEventos(String tipo, Duration espera) throws InterruptedException {
        Thread.sleep(espera.toMillis());
        List<JsonNode> sobrantes = recibidos.stream()
                .filter(recibido -> !recibido.leido().get() && tipo.equals(recibido.evento().path("tipo").asString()))
                .map(Recibido::evento).toList();
        if (!sobrantes.isEmpty()) {
            throw new AssertionError("Llegaron eventos " + tipo + " inesperados: " + sobrantes);
        }
    }

    public boolean esperarCierre() throws InterruptedException {
        return cerrado.await(ESPERA.toSeconds(), TimeUnit.SECONDS);
    }

    public boolean estaConectado() {
        return sesion != null && sesion.isConnected();
    }

    /** El header message de cada frame ERROR que mandó el servidor. */
    public List<String> erroresStomp() {
        return List.copyOf(erroresStomp);
    }

    public void cerrar() {
        if (estaConectado()) {
            sesion.disconnect();
        }
        cliente.stop();
    }

    private class ManejadorEventos implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders cabeceras) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders cabeceras, Object cuerpo) {
            recibidos.add(new Recibido((JsonNode) cuerpo, new AtomicBoolean()));
        }
    }

    /** Recibe los frames ERROR (a nivel de sesión) y se entera cuando el servidor corta la conexión. */
    private class ManejadorSesion extends StompSessionHandlerAdapter {

        @Override
        public Type getPayloadType(StompHeaders cabeceras) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders cabeceras, Object cuerpo) {
            erroresStomp.add(String.valueOf(cabeceras.getFirst("message")));
        }

        @Override
        public void handleTransportError(StompSession sesionCaida, Throwable error) {
            cerrado.countDown();
        }
    }
}
