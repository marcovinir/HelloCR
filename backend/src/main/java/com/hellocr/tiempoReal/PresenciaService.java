package com.hellocr.tiempoReal;

import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ChatDirectoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Spec 7.5: la presencia la ven solo quienes tienen un chat directo con esa persona. */
@Service
public class PresenciaService {

    private final ChatDirectoRepository directos;
    private final EnviadorEventos enviador;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PresenciaService(ChatDirectoRepository directos, EnviadorEventos enviador, JdbcClient jdbc, Clock clock) {
        this.directos = directos;
        this.enviador = enviador;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Pasó de 0 a 1 sesiones. */
    public void conectado(UUID usuarioId) {
        Instant ultimaConexion = jdbc.sql("SELECT ultima_conexion FROM usuarios WHERE id = :id")
                .param("id", usuarioId)
                .query((fila, n) -> Tiempos.instante(fila, "ultima_conexion"))
                .optional().orElse(null);
        avisar(new EventoPresencia(usuarioId, true, ultimaConexion));
    }

    /** Pasó de 1 a 0 sesiones: se guarda cuándo fue la última vez. */
    public void desconectado(UUID usuarioId) {
        Instant ahora = Tiempos.ahora(clock);
        jdbc.sql("UPDATE usuarios SET ultima_conexion = :ahora WHERE id = :id")
                .param("ahora", Tiempos.sql(ahora)).param("id", usuarioId)
                .update();
        avisar(new EventoPresencia(usuarioId, false, ahora));
    }

    private void avisar(EventoPresencia evento) {
        directos.contactosDe(evento.usuarioId()).forEach(contacto -> enviador.enviar(contacto, evento));
    }
}
