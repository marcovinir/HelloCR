package com.hellocr.usuarios;

import java.util.Set;

/** Nombres de usuario que nadie puede tomar (spec, sección 4). */
public final class NombresReservados {

    private static final Set<String> RESERVADOS =
            Set.of("admin", "administrador", "hellocr", "soporte", "sistema", "root");

    private NombresReservados() {
    }

    /** Recibe el nombre ya normalizado con Usuario.normalizarNombreUsuario. */
    public static boolean contiene(String nombreUsuario) {
        return RESERVADOS.contains(nombreUsuario);
    }
}
