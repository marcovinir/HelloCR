package com.hellocr.conversaciones;

import com.hellocr.comun.Tiempos;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversacionRepository {

    private final JdbcClient jdbc;

    public ConversacionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID crear(TipoConversacion tipo, Instant ahora) {
        return jdbc.sql("INSERT INTO conversaciones (tipo, creada_en) VALUES (:tipo, :ahora) RETURNING id")
                .param("tipo", tipo.name()).param("ahora", Tiempos.sql(ahora))
                .query(UUID.class).single();
    }

    /**
     * SELECT … FOR UPDATE: toda escritura en una conversación empieza por acá (spec, sección 8), así las
     * secuencias no se repiten y los bloqueos siempre se toman en el mismo orden. Vacío si no existe.
     */
    public Optional<TipoConversacion> bloquear(UUID id) {
        return jdbc.sql("SELECT tipo FROM conversaciones WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(String.class).optional()
                .map(TipoConversacion::valueOf);
    }
}
