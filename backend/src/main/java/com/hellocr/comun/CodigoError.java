package com.hellocr.comun;

import org.springframework.http.HttpStatus;

/** Catálogo de códigos de error de la API (spec, sección 11). El plan 3 agrega los de archivos. */
public enum CodigoError {
    VALIDACION(HttpStatus.BAD_REQUEST),
    TEXTO_MUY_LARGO(HttpStatus.BAD_REQUEST),
    NO_AUTENTICADO(HttpStatus.UNAUTHORIZED),
    CREDENCIALES_INVALIDAS(HttpStatus.UNAUTHORIZED),
    SIN_PERMISO(HttpStatus.FORBIDDEN),
    NO_ES_MIEMBRO(HttpStatus.FORBIDDEN),
    CORREO_NO_VERIFICADO(HttpStatus.FORBIDDEN),
    NO_ENCONTRADO(HttpStatus.NOT_FOUND),
    CORREO_EN_USO(HttpStatus.CONFLICT),
    NOMBRE_USUARIO_EN_USO(HttpStatus.CONFLICT),
    YA_ES_MIEMBRO(HttpStatus.CONFLICT),
    GRUPO_LLENO(HttpStatus.CONFLICT),
    ULTIMO_ADMIN(HttpStatus.CONFLICT),
    TOKEN_INVALIDO(HttpStatus.UNPROCESSABLE_CONTENT),
    NOMBRE_USUARIO_RESERVADO(HttpStatus.UNPROCESSABLE_CONTENT),
    CHAT_CONSIGO_MISMO(HttpStatus.UNPROCESSABLE_CONTENT),
    DEMASIADOS_INTENTOS(HttpStatus.TOO_MANY_REQUESTS),
    DEMASIADOS_MENSAJES(HttpStatus.TOO_MANY_REQUESTS),
    ERROR_INTERNO(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus estado;

    CodigoError(HttpStatus estado) {
        this.estado = estado;
    }

    public HttpStatus estado() {
        return estado;
    }
}
