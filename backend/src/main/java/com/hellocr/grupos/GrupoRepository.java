package com.hellocr.grupos;

import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GrupoRepository {

    private final JdbcClient jdbc;

    public GrupoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void crear(UUID conversacionId, String nombre, String descripcion, UUID creadoPor) {
        jdbc.sql("""
                        INSERT INTO grupos (conversacion_id, nombre, descripcion, creado_por)
                        VALUES (:c, :nombre, :descripcion, :creadoPor)
                        """)
                .param("c", conversacionId).param("nombre", nombre)
                .param("descripcion", descripcion, Types.VARCHAR).param("creadoPor", creadoPor)
                .update();
    }

    public String nombre(UUID conversacionId) {
        return jdbc.sql("SELECT nombre FROM grupos WHERE conversacion_id = :c")
                .param("c", conversacionId)
                .query(String.class).single();
    }

    public void cambiarNombre(UUID conversacionId, String nombre) {
        jdbc.sql("UPDATE grupos SET nombre = :nombre WHERE conversacion_id = :c")
                .param("c", conversacionId).param("nombre", nombre)
                .update();
    }

    /** null borra la descripción. */
    public void cambiarDescripcion(UUID conversacionId, String descripcion) {
        jdbc.sql("UPDATE grupos SET descripcion = :descripcion WHERE conversacion_id = :c")
                .param("c", conversacionId).param("descripcion", descripcion, Types.VARCHAR)
                .update();
    }
}
