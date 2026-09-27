package com.hellocr.usuarios;

import java.util.UUID;

/** Los datos de la cuenta que solo ve su dueño. El plan 3 agrega fotoId. */
public record UsuarioPropio(UUID id, String correo, String nombreUsuario, String nombreVisible, String info,
        String codigoInvitacion) {

    public static UsuarioPropio de(Usuario usuario) {
        return new UsuarioPropio(usuario.getId(), usuario.getCorreo(), usuario.getNombreUsuario(),
                usuario.getNombreVisible(), usuario.getInfo(), usuario.getCodigoInvitacion());
    }
}
