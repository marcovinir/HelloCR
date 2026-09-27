package com.hellocr.comun;

import java.util.Map;

/** Error esperado de la aplicación: se convierte en una respuesta ProblemDetail con su código. */
public class ErrorNegocio extends RuntimeException {

    /** Extra con los segundos que faltan para reintentar; ApiErrorHandler lo copia al header Retry-After. */
    public static final String REINTENTAR_EN_SEGUNDOS = "reintentarEnSegundos";

    private final CodigoError codigo;
    private final Map<String, Object> extras;

    public ErrorNegocio(CodigoError codigo, String mensaje) {
        this(codigo, mensaje, Map.of());
    }

    public ErrorNegocio(CodigoError codigo, String mensaje, Map<String, Object> extras) {
        super(mensaje);
        this.codigo = codigo;
        this.extras = Map.copyOf(extras);
    }

    /** Error de un solo campo, con el mismo formato que las validaciones de Bean Validation. */
    public static ErrorNegocio validacion(String campo, String mensaje) {
        return new ErrorNegocio(CodigoError.VALIDACION, "Hay datos inválidos.",
                Map.of("errores", Map.of(campo, mensaje)));
    }

    public CodigoError codigo() {
        return codigo;
    }

    public Map<String, Object> extras() {
        return extras;
    }
}
