package com.hellocr.grupos;

import jakarta.validation.constraints.Size;

/** Campos opcionales: null no cambia nada; una descripción vacía la borra. */
public record SolicitudEdicionGrupo(
        @Size(min = 1, max = 50, message = "El nombre debe tener entre 1 y 50 caracteres.")
        String nombre,

        @Size(max = 500, message = "La descripción puede tener hasta 500 caracteres.")
        String descripcion) {

    public SolicitudEdicionGrupo {
        nombre = nombre == null ? null : nombre.trim();
        descripcion = descripcion == null ? null : descripcion.trim();
    }
}
