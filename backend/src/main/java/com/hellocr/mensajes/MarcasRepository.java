package com.hellocr.mensajes;

import com.hellocr.conversaciones.MembresiaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Las marcas de agua de cada miembro (spec 6, nota 1): nunca bajan, por eso todo usa GREATEST o compara antes. */
@Repository
public class MarcasRepository {

    private final JdbcClient jdbc;

    public MarcasRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Los mensajes propios cuentan como entregados y leídos; responder implica haber leído lo anterior. */
    public void avanzarPropias(UUID conversacionId, UUID usuarioId, long secuencia) {
        jdbc.sql("""
                        UPDATE miembros
                        SET ultima_entregada = GREATEST(ultima_entregada, :s), ultima_leida = GREATEST(ultima_leida, :s)
                        WHERE conversacion_id = :c AND usuario_id = :u
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("s", secuencia)
                .update();
    }

    /** La mayor secuencia que ese usuario puede ver en la conversación (0 si no ve ninguna). */
    public long maxVisible(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("SELECT COALESCE(MAX(m.secuencia), 0) FROM mensajes m WHERE m.conversacion_id = :c AND "
                        + MembresiaRepository.visiblePara("m"))
                .param("c", conversacionId).param("yo", usuarioId)
                .query(Long.class).single();
    }

    /** Vacío si la marca ya estaba en esa secuencia o más adelante. */
    public Optional<Marcas> marcarEntregados(UUID conversacionId, UUID usuarioId, long hasta) {
        return jdbc.sql("""
                        UPDATE miembros SET ultima_entregada = :h
                        WHERE conversacion_id = :c AND usuario_id = :u AND ultima_entregada < :h
                        RETURNING ultima_entregada, ultima_leida
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("h", hasta)
                .query((fila, n) -> new Marcas(fila.getLong("ultima_entregada"), fila.getLong("ultima_leida")))
                .optional();
    }

    /** Leer también entrega. Vacío si la marca de leído ya estaba en esa secuencia o más adelante. */
    public Optional<Marcas> marcarLeidos(UUID conversacionId, UUID usuarioId, long hasta) {
        return jdbc.sql("""
                        UPDATE miembros SET ultima_leida = :h, ultima_entregada = GREATEST(ultima_entregada, :h)
                        WHERE conversacion_id = :c AND usuario_id = :u AND ultima_leida < :h
                        RETURNING ultima_entregada, ultima_leida
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("h", hasta)
                .query((fila, n) -> new Marcas(fila.getLong("ultima_entregada"), fila.getLong("ultima_leida")))
                .optional();
    }
}
