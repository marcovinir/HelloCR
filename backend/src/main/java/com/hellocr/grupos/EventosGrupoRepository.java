package com.hellocr.grupos;

import com.hellocr.comun.Tiempos;
import com.hellocr.mensajes.EventoDto;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.TipoMensaje;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Un evento de grupo es un mensaje EVENTO más su fila en eventos_grupo, en la misma transacción. */
@Repository
public class EventosGrupoRepository {

    private final JdbcClient jdbc;

    public EventosGrupoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MensajeDto insertar(UUID conversacionId, long secuencia, UUID actorId, TipoEvento evento, UUID afectadoId,
            String valor, Instant ahora) {
        UUID idCliente = UUID.randomUUID();
        long mensajeId = jdbc.sql("""
                        INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, creado_en)
                        VALUES (:c, :s, :r, :ic, 'EVENTO', :ahora) RETURNING id
                        """)
                .param("c", conversacionId).param("s", secuencia).param("r", actorId).param("ic", idCliente)
                .param("ahora", Tiempos.sql(ahora))
                .query(Long.class).single();
        jdbc.sql("INSERT INTO eventos_grupo (mensaje_id, evento, afectado_id, valor) VALUES (:m, :evento, :afectado, :valor)")
                .param("m", mensajeId).param("evento", evento.name())
                .param("afectado", afectadoId, Types.OTHER).param("valor", valor, Types.VARCHAR)
                .update();
        return new MensajeDto(conversacionId, secuencia, idCliente, actorId, TipoMensaje.EVENTO, null,
                new EventoDto(evento.name(), afectadoId, valor), ahora);
    }
}
