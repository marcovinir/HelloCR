package com.hellocr.usuarios;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

/** Errores que comparten el registro y la edición del perfil. */
public final class ErroresUsuario {

    private ErroresUsuario() {
    }

    public static ErrorNegocio correoEnUso() {
        return new ErrorNegocio(CodigoError.CORREO_EN_USO, "Ya existe una cuenta con ese correo.");
    }

    public static ErrorNegocio nombreUsuarioEnUso() {
        return new ErrorNegocio(CodigoError.NOMBRE_USUARIO_EN_USO, "Ese nombre de usuario ya está en uso.");
    }

    public static ErrorNegocio nombreUsuarioReservado() {
        return new ErrorNegocio(CodigoError.NOMBRE_USUARIO_RESERVADO, "Ese nombre de usuario no está disponible.");
    }

    public static ErrorNegocio noEncontrado() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "No encontramos a esa persona.");
    }

    /** Dos requests simultáneos pasaron la verificación previa: la restricción única de la base decide. */
    public static RuntimeException traducirDuplicado(DataIntegrityViolationException error) {
        String mensaje = String.valueOf(NestedExceptionUtils.getMostSpecificCause(error).getMessage());
        if (mensaje.contains("usuarios_correo_unico")) {
            return correoEnUso();
        }
        if (mensaje.contains("usuarios_nombre_usuario_unico")) {
            return nombreUsuarioEnUso();
        }
        return error;
    }
}
