package com.hellocr.soporte;

import com.hellocr.tiempoReal.RegistroSesiones;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

/** Base de los tests con WebSocket: servidor real en un puerto aleatorio y clientes STOMP que se cierran solos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PruebaTiempoReal extends PruebaIntegracion {

    @Value("${local.server.port}")
    private int puerto;
    @Autowired
    private SimpUserRegistry usuariosStomp;
    @Autowired
    protected RegistroSesiones registroSesiones;

    private final List<ClienteStomp> clientes = new ArrayList<>();

    protected ClienteStomp nuevoCliente() {
        ClienteStomp cliente = new ClienteStomp("ws://localhost:" + puerto + "/ws");
        clientes.add(cliente);
        return cliente;
    }

    /** Conecta y espera a que el servidor registre la suscripción, para no perder los primeros eventos. */
    protected ClienteStomp conectar(SesionPrueba sesion) throws Exception {
        UUID usuario = UUID.fromString(sesion.usuarioId());
        int antes = sesionesSuscritas(usuario);
        ClienteStomp cliente = nuevoCliente();
        cliente.conectar(sesion.accessToken());
        esperarHasta(() -> sesionesSuscritas(usuario) > antes, "la suscripción de " + usuario);
        return cliente;
    }

    @AfterEach
    void cerrarClientes() throws InterruptedException {
        clientes.forEach(ClienteStomp::cerrar);
        clientes.clear();
        esperarHasta(() -> registroSesiones.cantidad() == 0, "que el servidor cierre todas las sesiones");
    }

    protected static void esperarHasta(BooleanSupplier condicion, String que) throws InterruptedException {
        long limite = System.nanoTime() + 5_000_000_000L;
        while (!condicion.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("Pasaron 5 s esperando " + que);
            }
            Thread.sleep(20);
        }
    }

    private int sesionesSuscritas(UUID usuario) {
        SimpUser registrado = usuariosStomp.getUser(usuario.toString());
        return registrado == null ? 0
                : (int) registrado.getSessions().stream().filter(s -> !s.getSubscriptions().isEmpty()).count();
    }
}
