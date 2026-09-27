package com.hellocr.usuarios;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** El frontend abre /i/{codigo} y pregunta acá de quién es la invitación. */
@RestController
@RequestMapping("/api/invitaciones")
public class InvitacionController {

    private final UsuarioService usuarios;

    public InvitacionController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/{codigo}")
    public PerfilPublico deQuienEs(@PathVariable String codigo) {
        return usuarios.porInvitacion(codigo);
    }
}
