package com.hellocr.mensajes;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Las marcas de agua de cada miembro (spec 6, nota 1): nunca bajan, por eso todo usa GREATEST. */
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
}
