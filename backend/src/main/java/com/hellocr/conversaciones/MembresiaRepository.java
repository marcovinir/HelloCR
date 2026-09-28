package com.hellocr.conversaciones;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MembresiaRepository implements ConsultaMembresia, GestionMiembros {

    private static final String PERIODO_ABIERTO = """
            EXISTS (SELECT 1 FROM periodos_membresia p
                    WHERE p.conversacion_id = m.conversacion_id AND p.usuario_id = m.usuario_id
                      AND p.hasta_secuencia IS NULL)""";

    private final JdbcClient jdbc;

    public MembresiaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Condición SQL "el mensaje con alias {@code aliasMensaje} está dentro de un periodo del usuario :yo"
     * (spec 9.2, visibilidad). La consulta que la usa debe pasar el parámetro {@code yo}.
     */
    public static String visiblePara(String aliasMensaje) {
        return """
                EXISTS (SELECT 1 FROM periodos_membresia p
                        WHERE p.conversacion_id = %1$s.conversacion_id AND p.usuario_id = :yo
                          AND %1$s.secuencia >= p.desde_secuencia
                          AND (p.hasta_secuencia IS NULL OR %1$s.secuencia <= p.hasta_secuencia))""".formatted(aliasMensaje);
    }

    @Override
    public boolean esMiembroActivo(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM periodos_membresia
                                       WHERE conversacion_id = :c AND usuario_id = :u AND hasta_secuencia IS NULL)
                        """)
                .param("c", conversacionId).param("u", usuarioId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean fueMiembro(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM miembros WHERE conversacion_id = :c AND usuario_id = :u)")
                .param("c", conversacionId).param("u", usuarioId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean puedeVer(UUID conversacionId, UUID usuarioId, long secuencia) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM periodos_membresia
                                       WHERE conversacion_id = :c AND usuario_id = :u AND desde_secuencia <= :s
                                         AND (hasta_secuencia IS NULL OR hasta_secuencia >= :s))
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("s", secuencia)
                .query(Boolean.class).single();
    }

    @Override
    public Set<UUID> quienesPuedenVer(UUID conversacionId, long secuencia) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT DISTINCT usuario_id FROM periodos_membresia
                        WHERE conversacion_id = :c AND desde_secuencia <= :s
                          AND (hasta_secuencia IS NULL OR hasta_secuencia >= :s)
                        """)
                .param("c", conversacionId).param("s", secuencia)
                .query(UUID.class).list());
    }

    @Override
    public Set<UUID> miembrosActivos(UUID conversacionId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT usuario_id FROM periodos_membresia
                        WHERE conversacion_id = :c AND hasta_secuencia IS NULL
                        ORDER BY desde_secuencia, id
                        """)
                .param("c", conversacionId)
                .query(UUID.class).list());
    }

    @Override
    public void incorporar(UUID conversacionId, UUID usuarioId, Rol rol, long desdeSecuencia) {
        jdbc.sql("""
                        INSERT INTO miembros (conversacion_id, usuario_id, rol) VALUES (:c, :u, :rol)
                        ON CONFLICT (conversacion_id, usuario_id) DO UPDATE SET rol = EXCLUDED.rol
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("rol", rol.name())
                .update();
        jdbc.sql("INSERT INTO periodos_membresia (conversacion_id, usuario_id, desde_secuencia) VALUES (:c, :u, :s)")
                .param("c", conversacionId).param("u", usuarioId).param("s", desdeSecuencia)
                .update();
    }

    @Override
    public void retirar(UUID conversacionId, UUID usuarioId, long hastaSecuencia) {
        jdbc.sql("""
                        UPDATE periodos_membresia SET hasta_secuencia = :s
                        WHERE conversacion_id = :c AND usuario_id = :u AND hasta_secuencia IS NULL
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("s", hastaSecuencia)
                .update();
        cambiarRol(conversacionId, usuarioId, Rol.MIEMBRO);
    }

    @Override
    public Optional<Rol> rolActivo(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("SELECT m.rol FROM miembros m WHERE m.conversacion_id = :c AND m.usuario_id = :u AND "
                        + PERIODO_ABIERTO)
                .param("c", conversacionId).param("u", usuarioId)
                .query(String.class).optional()
                .map(Rol::valueOf);
    }

    @Override
    public void cambiarRol(UUID conversacionId, UUID usuarioId, Rol rol) {
        jdbc.sql("UPDATE miembros SET rol = :rol WHERE conversacion_id = :c AND usuario_id = :u")
                .param("c", conversacionId).param("u", usuarioId).param("rol", rol.name())
                .update();
    }

    @Override
    public int contarActivos(UUID conversacionId) {
        return jdbc.sql("SELECT count(*) FROM periodos_membresia WHERE conversacion_id = :c AND hasta_secuencia IS NULL")
                .param("c", conversacionId)
                .query(Integer.class).single();
    }

    @Override
    public int contarAdministradoresActivos(UUID conversacionId) {
        return jdbc.sql("SELECT count(*) FROM miembros m WHERE m.conversacion_id = :c AND m.rol = 'ADMIN' AND "
                        + PERIODO_ABIERTO)
                .param("c", conversacionId)
                .query(Integer.class).single();
    }

    @Override
    public Optional<UUID> activoMasAntiguo(UUID conversacionId) {
        return jdbc.sql("""
                        SELECT usuario_id FROM periodos_membresia
                        WHERE conversacion_id = :c AND hasta_secuencia IS NULL
                        ORDER BY desde_secuencia, id LIMIT 1
                        """)
                .param("c", conversacionId)
                .query(UUID.class).optional();
    }
}
