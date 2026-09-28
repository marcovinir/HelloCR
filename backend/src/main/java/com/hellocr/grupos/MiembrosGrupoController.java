package com.hellocr.grupos;

import com.hellocr.comun.UsuarioAutenticado;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grupos/{id}")
public class MiembrosGrupoController {

    private final MiembrosGrupoService miembros;

    public MiembrosGrupoController(MiembrosGrupoService miembros) {
        this.miembros = miembros;
    }

    @PostMapping("/miembros")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void agregar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SolicitudMiembro solicitud) {
        miembros.agregar(UsuarioAutenticado.id(jwt), id, solicitud.usuarioId());
    }

    @DeleteMapping("/miembros/{usuarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quitar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID usuarioId) {
        miembros.quitar(UsuarioAutenticado.id(jwt), id, usuarioId);
    }

    @PutMapping("/administradores/{usuarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hacerAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID usuarioId) {
        miembros.hacerAdmin(UsuarioAutenticado.id(jwt), id, usuarioId);
    }

    @DeleteMapping("/administradores/{usuarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quitarAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID usuarioId) {
        miembros.quitarAdmin(UsuarioAutenticado.id(jwt), id, usuarioId);
    }

    @PostMapping("/salir")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void salir(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        miembros.salir(UsuarioAutenticado.id(jwt), id);
    }
}
