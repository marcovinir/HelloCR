package com.hellocr.comun;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class ApiErrorHandlerTest {

    private final ApiErrorHandler manejador = new ApiErrorHandler();

    @Test
    void errorDeNegocioUsaElEstadoDeSuCodigoYAgregaLosExtras() {
        ErrorNegocio error = new ErrorNegocio(CodigoError.NOMBRE_USUARIO_EN_USO, "Ese nombre de usuario ya está en uso.",
                Map.of("sugerencia", "ana_2"));

        ResponseEntity<ProblemDetail> respuesta = manejador.negocio(error);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respuesta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail cuerpo = respuesta.getBody();
        assertThat(cuerpo.getDetail()).isEqualTo("Ese nombre de usuario ya está en uso.");
        assertThat(cuerpo.getProperties())
                .containsEntry("codigo", "NOMBRE_USUARIO_EN_USO")
                .containsEntry("sugerencia", "ana_2");
    }

    @Test
    void unTokenInvalidoEs422() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.negocio(new ErrorNegocio(CodigoError.TOKEN_INVALIDO, "El enlace venció."));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void demasiadosIntentosEs429ConRetryAfter() {
        ErrorNegocio error = new ErrorNegocio(CodigoError.DEMASIADOS_INTENTOS, "Probá más tarde.",
                Map.of(ErrorNegocio.REINTENTAR_EN_SEGUNDOS, 900L));

        ResponseEntity<ProblemDetail> respuesta = manejador.negocio(error);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900");
        assertThat(respuesta.getBody().getProperties()).containsEntry("reintentarEnSegundos", 900L);
    }

    @Test
    void validacionDeUnCampoTieneElMismoFormatoQueBeanValidation() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.negocio(ErrorNegocio.validacion("contrasena", "La contraseña es demasiado larga."));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(respuesta.getBody().getProperties())
                .containsEntry("codigo", "VALIDACION")
                .containsEntry("errores", Map.of("contrasena", "La contraseña es demasiado larga."));
    }

    @Test
    void accesoDenegadoEs403SinPermiso() {
        ResponseEntity<ProblemDetail> respuesta = manejador.sinPermiso(new AccessDeniedException("no"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(respuesta.getBody().getProperties()).containsEntry("codigo", "SIN_PERMISO");
    }

    @Test
    void erroresDeSpringConservanSuEstadoYRecibenUnCodigo() {
        ResponseEntity<ProblemDetail> noEncontrado =
                manejador.inesperado(new ErrorResponseException(HttpStatus.NOT_FOUND));
        ResponseEntity<ProblemDetail> metodo =
                manejador.inesperado(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(noEncontrado.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noEncontrado.getBody().getProperties()).containsEntry("codigo", "NO_ENCONTRADO");
        assertThat(metodo.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(metodo.getBody().getProperties()).containsEntry("codigo", "VALIDACION");
    }

    @Test
    void errorInesperadoEs500ySinDetallesInternos() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.inesperado(new IllegalStateException("password de la base: 1234"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(respuesta.getBody().getProperties()).containsEntry("codigo", "ERROR_INTERNO");
        assertThat(respuesta.getBody().getDetail())
                .isEqualTo("Ocurrió un error inesperado. Intentá de nuevo.")
                .doesNotContain("1234");
    }
}
