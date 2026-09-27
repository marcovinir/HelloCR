package com.hellocr.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RegistroController {

    private final RegistroService registro;
    private final CookieRefresh cookie;

    public RegistroController(RegistroService registro, CookieRefresh cookie) {
        this.registro = registro;
        this.cookie = cookie;
    }

    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    public RespuestaRegistro registrar(@Valid @RequestBody SolicitudRegistro solicitud) {
        return registro.registrar(solicitud);
    }

    @PostMapping("/verificar")
    public ResponseEntity<RespuestaSesion> verificar(@Valid @RequestBody SolicitudToken solicitud) {
        return cookie.responder(HttpStatus.OK, registro.verificar(solicitud.token()));
    }

    @PostMapping("/reenviar-verificacion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reenviar(@Valid @RequestBody SolicitudCorreo solicitud) {
        registro.reenviarVerificacion(solicitud.correo());
    }
}
