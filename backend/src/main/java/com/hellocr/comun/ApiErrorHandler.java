package com.hellocr.comun;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    public static ProblemDetail problema(CodigoError codigo, String detalle) {
        return problema(codigo.estado(), codigo, detalle);
    }

    private static ProblemDetail problema(HttpStatusCode estado, CodigoError codigo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setProperty("codigo", codigo.name());
        return problema;
    }

    @ExceptionHandler(ErrorNegocio.class)
    public ResponseEntity<ProblemDetail> negocio(ErrorNegocio error) {
        ProblemDetail problema = problema(error.codigo(), error.getMessage());
        error.extras().forEach(problema::setProperty);
        ResponseEntity.BodyBuilder respuesta = ResponseEntity.status(problema.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (error.extras().get(ErrorNegocio.REINTENTAR_EN_SEGUNDOS) instanceof Number segundos) {
            respuesta.header(HttpHeaders.RETRY_AFTER, String.valueOf(segundos.longValue()));
        }
        return respuesta.body(problema);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validacion(MethodArgumentNotValidException error) {
        Map<String, String> errores = new LinkedHashMap<>();
        for (FieldError campo : error.getBindingResult().getFieldErrors()) {
            errores.putIfAbsent(campo.getField(), campo.getDefaultMessage());
        }
        ProblemDetail problema = problema(CodigoError.VALIDACION, "Hay datos inválidos.");
        problema.setProperty("errores", errores);
        return responder(problema);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<ProblemDetail> solicitudIlegible(Exception error) {
        return responder(problema(CodigoError.VALIDACION, "La solicitud no tiene un formato válido."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> sinPermiso(AccessDeniedException error) {
        return responder(problema(CodigoError.SIN_PERMISO, "No tenés permiso para esta acción."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inesperado(Exception error) {
        if (error instanceof ErrorResponse respuesta && respuesta.getStatusCode().is4xxClientError()) {
            HttpStatusCode estado = respuesta.getStatusCode();
            return switch (estado.value()) {
                case 401 -> responder(problema(estado, CodigoError.NO_AUTENTICADO, "Tenés que iniciar sesión."));
                case 403 -> responder(problema(estado, CodigoError.SIN_PERMISO, "No tenés permiso para esta acción."));
                case 404 -> responder(problema(estado, CodigoError.NO_ENCONTRADO, "El recurso no existe."));
                default -> responder(problema(estado, CodigoError.VALIDACION, "La solicitud no es válida."));
            };
        }
        log.error("Error inesperado", error);
        return responder(problema(CodigoError.ERROR_INTERNO, "Ocurrió un error inesperado. Intentá de nuevo."));
    }

    private static ResponseEntity<ProblemDetail> responder(ProblemDetail problema) {
        return ResponseEntity.status(problema.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }
}
