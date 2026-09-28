package com.hellocr.conversaciones;

import com.hellocr.usuarios.PerfilPublico;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LecturaConversacionesService {

    private record Cabecera(TipoConversacion tipo, String nombre, String descripcion) {
    }

    private final JdbcClient jdbc;
    private final ConsultaMembresia membresia;

    public LecturaConversacionesService(JdbcClient jdbc, ConsultaMembresia membresia) {
        this.jdbc = jdbc;
        this.membresia = membresia;
    }

    /** Solo para miembros actuales o pasados; a cualquier otro le responde que no existe. */
    @Transactional(readOnly = true)
    public ConversacionDetalle detalle(UUID yo, UUID conversacionId) {
        if (!membresia.fueMiembro(conversacionId, yo)) {
            throw ErroresConversacion.noEncontrada();
        }
        Cabecera cabecera = jdbc.sql("""
                        SELECT c.tipo, g.nombre, g.descripcion
                        FROM conversaciones c LEFT JOIN grupos g ON g.conversacion_id = c.id
                        WHERE c.id = :c
                        """)
                .param("c", conversacionId)
                .query((fila, n) -> new Cabecera(TipoConversacion.valueOf(fila.getString("tipo")),
                        fila.getString("nombre"), fila.getString("descripcion")))
                .single();
        Map<UUID, List<Periodo>> periodos = new HashMap<>();
        jdbc.sql("""
                        SELECT usuario_id, desde_secuencia, hasta_secuencia FROM periodos_membresia
                        WHERE conversacion_id = :c ORDER BY desde_secuencia
                        """)
                .param("c", conversacionId)
                .query(fila -> {
                    periodos.computeIfAbsent(fila.getObject("usuario_id", UUID.class), id -> new ArrayList<>())
                            .add(new Periodo(fila.getLong("desde_secuencia"),
                                    fila.getObject("hasta_secuencia", Long.class)));
                });
        List<MiembroDetalle> miembros = jdbc.sql("""
                        SELECT u.id, u.nombre_usuario, u.nombre_visible, u.info,
                               m.rol, m.ultima_entregada, m.ultima_leida
                        FROM miembros m JOIN usuarios u ON u.id = m.usuario_id
                        WHERE m.conversacion_id = :c
                        ORDER BY u.nombre_usuario
                        """)
                .param("c", conversacionId)
                .query((fila, n) -> {
                    UUID id = fila.getObject("id", UUID.class);
                    return new MiembroDetalle(
                            new PerfilPublico(id, fila.getString("nombre_usuario"), fila.getString("nombre_visible"),
                                    fila.getString("info")),
                            Rol.valueOf(fila.getString("rol")), fila.getLong("ultima_entregada"),
                            fila.getLong("ultima_leida"), periodos.getOrDefault(id, List.of()));
                })
                .list();
        MiembroDetalle propio = miembros.stream().filter(m -> m.usuario().id().equals(yo)).findFirst().orElseThrow();
        boolean activa = propio.periodos().stream().anyMatch(periodo -> periodo.hasta() == null);
        return new ConversacionDetalle(conversacionId, cabecera.tipo(), titulo(cabecera, miembros, yo),
                cabecera.descripcion(), activa, propio.rol(), miembros);
    }

    /** El nombre del grupo, o el nombre visible de la otra persona en un chat directo. */
    private static String titulo(Cabecera cabecera, List<MiembroDetalle> miembros, UUID yo) {
        if (cabecera.tipo() == TipoConversacion.GRUPO) {
            return cabecera.nombre();
        }
        return miembros.stream().filter(m -> !m.usuario().id().equals(yo))
                .map(m -> m.usuario().nombreVisible()).findFirst().orElse("");
    }
}
