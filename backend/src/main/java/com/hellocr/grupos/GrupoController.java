package com.hellocr.grupos;

import com.hellocr.comun.UsuarioAutenticado;
import com.hellocr.conversaciones.ConversacionDetalle;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grupos")
public class GrupoController {

    private final GrupoService grupos;

    public GrupoController(GrupoService grupos) {
        this.grupos = grupos;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversacionDetalle crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SolicitudGrupo solicitud) {
        return grupos.crear(UsuarioAutenticado.id(jwt), solicitud);
    }

    @PatchMapping("/{id}")
    public ConversacionDetalle editar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SolicitudEdicionGrupo solicitud) {
        return grupos.editar(UsuarioAutenticado.id(jwt), id, solicitud);
    }
}
