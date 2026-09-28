package com.hellocr.conversaciones;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El par de un chat directo se ordena en PostgreSQL con LEAST/GREATEST: Java compara los UUID con signo y
 * daría otro orden que el de la restricción chats_directos_orden.
 */
@Repository
public class ChatDirectoRepository {

    private final JdbcClient jdbc;

    public ChatDirectoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> buscar(UUID uno, UUID otro) {
        return jdbc.sql("""
                        SELECT conversacion_id FROM chats_directos
                        WHERE usuario_a_id = LEAST(:uno, :otro) AND usuario_b_id = GREATEST(:uno, :otro)
                        """)
                .param("uno", uno).param("otro", otro)
                .query(UUID.class).optional();
    }

    /** Falla con DuplicateKeyException si otra transacción ya registró ese par. */
    public void registrar(UUID conversacionId, UUID uno, UUID otro) {
        jdbc.sql("""
                        INSERT INTO chats_directos (conversacion_id, usuario_a_id, usuario_b_id)
                        VALUES (:c, LEAST(:uno, :otro), GREATEST(:uno, :otro))
                        """)
                .param("c", conversacionId).param("uno", uno).param("otro", otro)
                .update();
    }

    /** Las personas con las que el usuario tiene un chat directo (ven su presencia, spec 7.5). */
    public Set<UUID> contactosDe(UUID usuarioId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT CASE WHEN usuario_a_id = :u THEN usuario_b_id ELSE usuario_a_id END
                        FROM chats_directos WHERE usuario_a_id = :u OR usuario_b_id = :u
                        """)
                .param("u", usuarioId)
                .query(UUID.class).list());
    }
}
