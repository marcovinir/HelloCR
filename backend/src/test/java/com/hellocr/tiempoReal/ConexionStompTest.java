package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.auth.RefreshTokenService;
import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConexionStompTest extends PruebaTiempoReal {

    @Autowired
    private CierreSesiones cierre;
    @Autowired
    private RefreshTokenService refreshTokens;

    private SesionPrueba ana;

    @BeforeEach
    void sesion() throws Exception {
        ana = sesionDe("ana");
    }

    @Test
    void conUnTokenValidoSeConectaYQuedaEnLinea() throws Exception {
        conectar(ana);

        assertThat(registroSesiones.enLinea(UUID.fromString(ana.usuarioId()))).isTrue();
    }

    @Test
    void sinTokenElServidorRechazaLaConexion() {
        ClienteStomp cliente = nuevoCliente();

        assertThatThrownBy(() -> cliente.conectar(null)).isInstanceOf(ExecutionException.class);
        assertThat(cliente.erroresStomp()).containsExactly("Falta el token o no es válido.");
    }

    @Test
    void conUnTokenVencidoOAlteradoTambien() {
        reloj.avanzar(Duration.ofMinutes(16));
        assertThatThrownBy(() -> nuevoCliente().conectar(ana.accessToken())).isInstanceOf(ExecutionException.class);

        reloj.reiniciar();
        String alterado = ana.accessToken().substring(0, ana.accessToken().length() - 4) + "AAAA";
        assertThatThrownBy(() -> nuevoCliente().conectar(alterado)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void desdeUnOrigenNoPermitidoNiSiquieraAbreElWebSocket() {
        ClienteStomp cliente = nuevoCliente().conOrigen("https://malicioso.example");

        assertThatThrownBy(() -> cliente.conectar(ana.accessToken())).isInstanceOf(ExecutionException.class);
    }

    @Test
    void suscribirseAOtraColaCierraLaConexion() throws Exception {
        ClienteStomp cliente = conectar(ana);

        cliente.suscribir("/topic/todo");

        assertThat(cliente.esperarCierre()).isTrue();
        assertThat(cliente.erroresStomp()).containsExactly("Solo podés suscribirte a /user/queue/eventos.");
    }

    @Test
    void enviarAUnDestinoQueNoExisteCierraLaConexion() throws Exception {
        ClienteStomp cliente = conectar(ana);

        cliente.enviar("/app/otra.cosa", Map.of("hola", "mundo"));

        assertThat(cliente.esperarCierre()).isTrue();
        assertThat(cliente.erroresStomp()).containsExactly("Ese destino no existe.");
    }

    @Test
    void unaSesionConElTokenVencidoSeCierra() throws Exception {
        ClienteStomp cliente = conectar(ana);
        reloj.avanzar(Duration.ofMinutes(16));

        cierre.cerrarVencidas();

        assertThat(cliente.esperarCierre()).isTrue();
        esperarHasta(() -> !registroSesiones.enLinea(UUID.fromString(ana.usuarioId())), "que Ana quede desconectada");
    }

    @Test
    void revocarLasSesionesCierraLosWebSockets() throws Exception {
        ClienteStomp celular = conectar(ana);
        ClienteStomp pc = conectar(ana);

        refreshTokens.revocarTodos(UUID.fromString(ana.usuarioId()));

        assertThat(celular.esperarCierre()).isTrue();
        assertThat(pc.esperarCierre()).isTrue();
    }
}
