package com.hellocr.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RecuperacionController {

    private final RecuperacionService recuperacion;

    public RecuperacionController(RecuperacionService recuperacion) {
        this.recuperacion = recuperacion;
    }

    @PostMapping("/recuperar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recuperar(@Valid @RequestBody SolicitudCorreo solicitud) {
        recuperacion.solicitar(solicitud.correo());
    }

    @PostMapping("/restablecer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restablecer(@Valid @RequestBody SolicitudRestablecer solicitud) {
        recuperacion.restablecer(solicitud);
    }
}
