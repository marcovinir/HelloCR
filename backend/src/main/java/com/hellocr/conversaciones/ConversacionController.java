package com.hellocr.conversaciones;

import com.hellocr.comun.UsuarioAutenticado;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversaciones")
public class ConversacionController {

    private final ChatDirectoService chats;
    private final LecturaConversacionesService lectura;

    public ConversacionController(ChatDirectoService chats, LecturaConversacionesService lectura) {
        this.chats = chats;
        this.lectura = lectura;
    }

    @PostMapping("/directas")
    public ResponseEntity<ConversacionDetalle> abrirDirecta(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SolicitudChatDirecto solicitud) {
        ChatDirectoService.Apertura apertura = chats.abrir(UsuarioAutenticado.id(jwt), solicitud.usuarioId());
        return ResponseEntity.status(apertura.creada() ? HttpStatus.CREATED : HttpStatus.OK).body(apertura.detalle());
    }

    @GetMapping("/{id}")
    public ConversacionDetalle detalle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lectura.detalle(UsuarioAutenticado.id(jwt), id);
    }
}
