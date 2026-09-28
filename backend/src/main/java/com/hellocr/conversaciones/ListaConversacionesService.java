package com.hellocr.conversaciones;

import com.hellocr.comun.Tiempos;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.MensajeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Una sola consulta para toda la lista (decenas de chats): el último mensaje visible sale con LATERAL y los no
 * leídos con una subconsulta; nada de esto se guarda (spec 6, 3FN).
 */
@Service
public class ListaConversacionesService {

    private static final String CONSULTA = """
            SELECT c.id, c.tipo AS tipo_conversacion, c.creada_en, g.nombre AS nombre_grupo,
                   EXISTS (SELECT 1 FROM periodos_membresia pa
                           WHERE pa.conversacion_id = c.id AND pa.usuario_id = :yo AND pa.hasta_secuencia IS NULL)
                       AS activa,
                   o.id AS otro_id, o.nombre_usuario AS otro_nombre_usuario, o.nombre_visible AS otro_nombre_visible,
                   o.info AS otro_info, o.ultima_conexion AS otro_ultima_conexion,
                   u.conversacion_id, u.secuencia, u.id_cliente, u.remitente_id, u.tipo, u.texto, u.creado_en,
                   u.evento, u.afectado_id, u.valor,
                   (SELECT count(*) FROM mensajes x
                    WHERE x.conversacion_id = c.id AND x.tipo <> 'EVENTO' AND x.remitente_id <> :yo
                      AND x.secuencia > mi.ultima_leida AND %1$s) AS no_leidos
            FROM miembros mi
            JOIN conversaciones c ON c.id = mi.conversacion_id
            LEFT JOIN grupos g ON g.conversacion_id = c.id
            LEFT JOIN chats_directos d ON d.conversacion_id = c.id
            LEFT JOIN usuarios o ON o.id = CASE WHEN d.usuario_a_id = :yo THEN d.usuario_b_id ELSE d.usuario_a_id END
            LEFT JOIN LATERAL (
                SELECT %2$s FROM %3$s
                WHERE m.conversacion_id = c.id AND %4$s
                ORDER BY m.secuencia DESC LIMIT 1) u ON true
            WHERE mi.usuario_id = :yo
            ORDER BY COALESCE(u.creado_en, c.creada_en) DESC
            """.formatted(MembresiaRepository.visiblePara("x"), MensajeRepository.COLUMNAS, MensajeRepository.DESDE,
            MembresiaRepository.visiblePara("m"));

    private final JdbcClient jdbc;
    private final ConsultaPresencia presencia;

    public ListaConversacionesService(JdbcClient jdbc, ConsultaPresencia presencia) {
        this.jdbc = jdbc;
        this.presencia = presencia;
    }

    @Transactional(readOnly = true)
    public List<ConversacionResumen> de(UUID usuarioId) {
        return jdbc.sql(CONSULTA)
                .param("yo", usuarioId)
                .query((fila, numero) -> {
                    TipoConversacion tipo = TipoConversacion.valueOf(fila.getString("tipo_conversacion"));
                    UUID otroId = fila.getObject("otro_id", UUID.class);
                    OtroUsuario otro = otroId == null ? null : new OtroUsuario(otroId,
                            fila.getString("otro_nombre_usuario"), fila.getString("otro_nombre_visible"),
                            fila.getString("otro_info"), presencia.enLinea(otroId),
                            Tiempos.instante(fila, "otro_ultima_conexion"));
                    MensajeDto ultimo = fila.getObject("secuencia") == null ? null
                            : MensajeRepository.MAPEO.mapRow(fila, numero);
                    String titulo = tipo == TipoConversacion.GRUPO ? fila.getString("nombre_grupo")
                            : otro == null ? "" : otro.nombreVisible();
                    return new ConversacionResumen(fila.getObject("id", UUID.class), tipo, titulo, otro, ultimo,
                            ultimo == null ? 0 : ultimo.secuencia(), fila.getLong("no_leidos"),
                            fila.getBoolean("activa"));
                })
                .list();
    }
}
