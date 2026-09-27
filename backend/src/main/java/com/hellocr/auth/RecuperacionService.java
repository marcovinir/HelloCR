package com.hellocr.auth;

import com.hellocr.correo.CorreosDeCuenta;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecuperacionService {

    private final UsuarioRepository usuarios;
    private final TokenCorreoService tokensCorreo;
    private final CorreosDeCuenta correos;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;

    public RecuperacionService(UsuarioRepository usuarios, TokenCorreoService tokensCorreo, CorreosDeCuenta correos,
            PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens, Clock clock) {
        this.usuarios = usuarios;
        this.tokensCorreo = tokensCorreo;
        this.correos = correos;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    /** No revela si la cuenta existe: siempre termina bien. */
    @Transactional
    public void solicitar(String correo) {
        usuarios.findByCorreo(correo)
                .filter(usuario -> tokensCorreo.puedeEmitir(usuario, PropositoToken.RECUPERACION))
                .ifPresent(usuario -> correos.enviarRecuperacion(usuario,
                        tokensCorreo.emitir(usuario, PropositoToken.RECUPERACION)));
    }

    /**
     * La contraseña se valida antes de consumir el token, para no gastar el enlace con un error de tipeo.
     * Abrir el enlace prueba que la persona es dueña del correo, así que también lo verifica.
     */
    @Transactional
    public void restablecer(SolicitudRestablecer solicitud) {
        Contrasenas.validarLongitud(solicitud.contrasenaNueva(), "contrasenaNueva");
        Usuario usuario = tokensCorreo.consumir(solicitud.token(), PropositoToken.RECUPERACION);
        usuario.cambiarContrasena(passwordEncoder.encode(solicitud.contrasenaNueva()));
        usuario.verificarCorreo(clock.instant());
        refreshTokens.revocarTodos(usuario.getId());
    }
}
