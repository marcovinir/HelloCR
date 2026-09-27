package com.hellocr.auth;

import com.hellocr.config.JwtProperties;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** La cookie del refresh token: solo viaja a /api/auth y el JavaScript de la página no puede leerla. */
@Component
public class CookieRefresh {

    public static final String NOMBRE = "refresh_token";

    private final JwtProperties propiedades;

    public CookieRefresh(JwtProperties propiedades) {
        this.propiedades = propiedades;
    }

    public ResponseEntity<RespuestaSesion> responder(HttpStatus estado, SesionService.Sesion sesion) {
        return ResponseEntity.status(estado)
                .header(HttpHeaders.SET_COOKIE, crear(sesion.refreshToken(), propiedades.duracionRefresh()).toString())
                .body(sesion.respuesta());
    }

    public String borrar() {
        return crear("", Duration.ZERO).toString();
    }

    private ResponseCookie crear(String valor, Duration duracion) {
        return ResponseCookie.from(NOMBRE, valor)
                .httpOnly(true)
                .secure(propiedades.cookieSegura())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(duracion)
                .build();
    }
}
