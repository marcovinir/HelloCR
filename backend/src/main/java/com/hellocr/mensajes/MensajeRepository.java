package com.hellocr.mensajes;

import com.hellocr.comun.Tiempos;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MensajeRepository {

    /** Columnas de un MensajeDto. La consulta tiene que usar {@link #DESDE} (alias m y e). */
    public static final String COLUMNAS = """
            m.conversacion_id, m.secuencia, m.id_cliente, m.remitente_id, m.tipo, m.texto, m.creado_en,
            e.evento, e.afectado_id, e.valor""";
    public static final String DESDE = "mensajes m LEFT JOIN eventos_grupo e ON e.mensaje_id = m.id";

    public static final RowMapper<MensajeDto> MAPEO = (fila, numero) -> {
        String evento = fila.getString("evento");
        return new MensajeDto(fila.getObject("conversacion_id", UUID.class), fila.getLong("secuencia"),
                fila.getObject("id_cliente", UUID.class), fila.getObject("remitente_id", UUID.class),
                TipoMensaje.valueOf(fila.getString("tipo")), fila.getString("texto"),
                evento == null ? null
                        : new EventoDto(evento, fila.getObject("afectado_id", UUID.class), fila.getString("valor")),
                Tiempos.instante(fila, "creado_en"));
    };

    private final JdbcClient jdbc;

    public MensajeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Llamar con la conversación bloqueada (ConversacionRepository.bloquear). */
    public long siguienteSecuencia(UUID conversacionId) {
        return jdbc.sql("SELECT COALESCE(MAX(secuencia), 0) + 1 FROM mensajes WHERE conversacion_id = :c")
                .param("c", conversacionId)
                .query(Long.class).single();
    }

    public Optional<MensajeDto> porIdCliente(UUID remitenteId, UUID idCliente) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM " + DESDE + " WHERE m.remitente_id = :r AND m.id_cliente = :ic")
                .param("r", remitenteId).param("ic", idCliente)
                .query(MAPEO).optional();
    }

    public MensajeDto insertarTexto(UUID conversacionId, long secuencia, UUID remitenteId, UUID idCliente,
            String texto, Instant ahora) {
        jdbc.sql("""
                        INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto, creado_en)
                        VALUES (:c, :s, :r, :ic, 'TEXTO', :t, :ahora)
                        """)
                .param("c", conversacionId).param("s", secuencia).param("r", remitenteId).param("ic", idCliente)
                .param("t", texto).param("ahora", Tiempos.sql(ahora))
                .update();
        return new MensajeDto(conversacionId, secuencia, idCliente, remitenteId, TipoMensaje.TEXTO, texto, null, ahora);
    }
}
