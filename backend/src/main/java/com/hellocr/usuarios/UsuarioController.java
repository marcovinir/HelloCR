package com.hellocr.usuarios;

import com.hellocr.comun.UsuarioAutenticado;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final UsuarioService usuarios;

    public UsuarioController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/yo")
    public UsuarioPropio yo(@AuthenticationPrincipal Jwt jwt) {
        return usuarios.propio(UsuarioAutenticado.id(jwt));
    }

    @PatchMapping("/yo")
    public UsuarioPropio actualizar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SolicitudPerfil solicitud) {
        return usuarios.actualizar(UsuarioAutenticado.id(jwt), solicitud);
    }

    @PostMapping("/yo/codigo-invitacion")
    public NuevoCodigo regenerarCodigo(@AuthenticationPrincipal Jwt jwt) {
        return usuarios.regenerarCodigo(UsuarioAutenticado.id(jwt));
    }

    @GetMapping("/buscar")
    public PerfilPublico buscar(@RequestParam String nombreUsuario) {
        return usuarios.buscar(nombreUsuario);
    }

    @GetMapping("/{id}")
    public PerfilPublico publico(@PathVariable UUID id) {
        return usuarios.publico(id);
    }
}
