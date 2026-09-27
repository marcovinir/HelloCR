package com.hellocr.usuarios;

import java.util.UUID;

/** Lo que cualquier usuario con sesión puede ver de otro. Sin correo ni código de invitación. */
public record PerfilPublico(UUID id, String nombreUsuario, String nombreVisible, String info) {

    public static PerfilPublico de(Usuario usuario) {
        return new PerfilPublico(usuario.getId(), usuario.getNombreUsuario(), usuario.getNombreVisible(),
                usuario.getInfo());
    }
}
