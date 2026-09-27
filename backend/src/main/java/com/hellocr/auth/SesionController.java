package com.hellocr.auth;

import com.hellocr.comun.ApiErrorHandler;
import com.hellocr.comun.ErrorNegocio;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SesionController {

    private final SesionService sesiones;
    private final CookieRefresh cookie;

    public SesionController(SesionService sesiones, CookieRefresh cookie) {
        this.sesiones = sesiones;
        this.cookie = cookie;
    }

    @PostMapping("/login")
    public ResponseEntity<RespuestaSesion> login(@Valid @RequestBody SolicitudLogin solicitud,
            HttpServletRequest http) {
        return cookie.responder(HttpStatus.OK, sesiones.login(solicitud, http.getRemoteAddr()));
    }

    /** Si el refresh se rechaza, también se borra la cookie: así el navegador no vuelve a presentar un token muerto. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refrescar(
            @CookieValue(name = CookieRefresh.NOMBRE, required = false) String refreshToken) {
        try {
            return cookie.responder(HttpStatus.OK, sesiones.refrescar(refreshToken));
        } catch (ErrorNegocio rechazo) {
            return ResponseEntity.status(rechazo.codigo().estado())
                    .header(HttpHeaders.SET_COOKIE, cookie.borrar())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ApiErrorHandler.problema(rechazo.codigo(), rechazo.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = CookieRefresh.NOMBRE, required = false) String refreshToken) {
        if (refreshToken != null) {
            sesiones.cerrarSesion(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.borrar()).build();
    }
}
