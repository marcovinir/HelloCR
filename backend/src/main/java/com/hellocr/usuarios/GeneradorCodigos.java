package com.hellocr.usuarios;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class GeneradorCodigos {

    /** Sin 0, O, 1, I, l ni o, para que nadie los confunda al dictar o copiar un código. */
    static final String ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    static final int LARGO = 8;

    private final SecureRandom aleatorio = new SecureRandom();
    private final UsuarioRepository usuarios;

    public GeneradorCodigos(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Código de invitación nuevo que ningún usuario tiene todavía. */
    public String codigoInvitacion() {
        String codigo;
        do {
            codigo = generar();
        } while (usuarios.existsByCodigoInvitacion(codigo));
        return codigo;
    }

    private String generar() {
        StringBuilder codigo = new StringBuilder(LARGO);
        for (int i = 0; i < LARGO; i++) {
            codigo.append(ALFABETO.charAt(aleatorio.nextInt(ALFABETO.length())));
        }
        return codigo.toString();
    }
}
