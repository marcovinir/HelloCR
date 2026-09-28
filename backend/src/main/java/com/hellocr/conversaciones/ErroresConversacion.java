package com.hellocr.conversaciones;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;

/** Errores que comparten conversaciones, mensajes y grupos. */
public final class ErroresConversacion {

    private ErroresConversacion() {
    }

    public static ErrorNegocio noEncontrada() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "La conversación no existe.");
    }

    public static ErrorNegocio grupoNoEncontrado() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "El grupo no existe.");
    }

    public static ErrorNegocio noEsMiembro() {
        return new ErrorNegocio(CodigoError.NO_ES_MIEMBRO, "Ya no sos parte de esta conversación.");
    }

    public static ErrorNegocio sinPermiso() {
        return new ErrorNegocio(CodigoError.SIN_PERMISO, "Solo los administradores del grupo pueden hacer esto.");
    }

    public static ErrorNegocio personaFueraDelGrupo() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "Esa persona no está en el grupo.");
    }

    public static ErrorNegocio yaEsMiembro() {
        return new ErrorNegocio(CodigoError.YA_ES_MIEMBRO, "Esa persona ya está en el grupo.");
    }

    public static ErrorNegocio grupoLleno(int maximo) {
        return new ErrorNegocio(CodigoError.GRUPO_LLENO, "El grupo ya tiene el máximo de " + maximo + " personas.");
    }

    public static ErrorNegocio ultimoAdmin() {
        return new ErrorNegocio(CodigoError.ULTIMO_ADMIN, "El grupo tiene que tener al menos un administrador.");
    }

    public static ErrorNegocio chatConsigoMismo() {
        return new ErrorNegocio(CodigoError.CHAT_CONSIGO_MISMO, "No podés abrir un chat con vos mismo.");
    }
}
