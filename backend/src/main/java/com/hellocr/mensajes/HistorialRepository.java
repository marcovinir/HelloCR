package com.hellocr.mensajes;

import com.hellocr.conversaciones.MembresiaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Paginación keyset por secuencia (nunca OFFSET): igual de rápida en la primera página que en la número 500. */
@Repository
public class HistorialRepository {

    private static final String VISIBLES = "SELECT " + MensajeRepository.COLUMNAS + " FROM " + MensajeRepository.DESDE
            + " WHERE m.conversacion_id = :c AND " + MembresiaRepository.visiblePara("m");

    private final JdbcClient jdbc;

    public HistorialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Los últimos {@code cantidad} mensajes visibles anteriores a antesDe (o los más recientes), de mayor a menor. */
    public List<MensajeDto> anteriores(UUID conversacionId, UUID usuarioId, Long antesDe, int cantidad) {
        JdbcClient.StatementSpec consulta = jdbc.sql(VISIBLES
                        + (antesDe == null ? "" : " AND m.secuencia < :antes")
                        + " ORDER BY m.secuencia DESC LIMIT :cantidad")
                .param("c", conversacionId).param("yo", usuarioId).param("cantidad", cantidad);
        if (antesDe != null) {
            consulta = consulta.param("antes", antesDe);
        }
        return consulta.query(MensajeRepository.MAPEO).list();
    }

    /** Los primeros {@code cantidad} mensajes visibles posteriores a despuesDe, de menor a mayor. */
    public List<MensajeDto> posteriores(UUID conversacionId, UUID usuarioId, long despuesDe, int cantidad) {
        return jdbc.sql(VISIBLES + " AND m.secuencia > :despues ORDER BY m.secuencia LIMIT :cantidad")
                .param("c", conversacionId).param("yo", usuarioId).param("despues", despuesDe)
                .param("cantidad", cantidad)
                .query(MensajeRepository.MAPEO).list();
    }
}
