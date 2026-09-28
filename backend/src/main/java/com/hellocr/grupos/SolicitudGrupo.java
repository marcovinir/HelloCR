package com.hellocr.grupos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SolicitudGrupo(
        @NotBlank(message = "El grupo necesita un nombre.")
        @Size(max = 50, message = "El nombre puede tener hasta 50 caracteres.")
        String nombre,

        @Size(max = 500, message = "La descripción puede tener hasta 500 caracteres.")
        String descripcion,

        @NotNull(message = "Elegí al menos una persona.")
        @Size(min = 1, message = "Elegí al menos una persona.")
        List<@NotNull(message = "Hay una persona sin id.") UUID> miembrosIds) {

    public SolicitudGrupo {
        nombre = nombre == null ? null : nombre.trim();
        descripcion = descripcion == null || descripcion.isBlank() ? null : descripcion.trim();
    }
}
