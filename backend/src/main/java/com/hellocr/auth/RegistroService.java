package com.hellocr.auth;

import com.hellocr.correo.CorreosDeCuenta;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.GeneradorCodigos;
import com.hellocr.usuarios.NombresReservados;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistroService {

    private final UsuarioRepository usuarios;
    private final GeneradorCodigos codigos;
    private final PasswordEncoder passwordEncoder;
    private final TokenCorreoService tokensCorreo;
    private final CorreosDeCuenta correos;
    private final SesionService sesiones;
    private final Clock clock;

    public RegistroService(UsuarioRepository usuarios, GeneradorCodigos codigos, PasswordEncoder passwordEncoder,
            TokenCorreoService tokensCorreo, CorreosDeCuenta correos, SesionService sesiones, Clock clock) {
        this.usuarios = usuarios;
        this.codigos = codigos;
        this.passwordEncoder = passwordEncoder;
        this.tokensCorreo = tokensCorreo;
        this.correos = correos;
        this.sesiones = sesiones;
        this.clock = clock;
    }

    /**
     * Crea la cuenta sin verificar y envía el enlace. Si el correo pertenecía a una cuenta sin verificar,
     * esa cuenta se reemplaza: así nadie puede bloquear un correo ajeno registrándose con él.
     */
    @Transactional
    public RespuestaRegistro registrar(SolicitudRegistro solicitud) {
        Contrasenas.validarLongitud(solicitud.contrasena(), "contrasena");
        if (NombresReservados.contiene(solicitud.nombreUsuario())) {
            throw ErroresUsuario.nombreUsuarioReservado();
        }
        usuarios.borrarSinVerificarPorCorreo(solicitud.correo());
        if (usuarios.existsByCorreo(solicitud.correo())) {
            throw ErroresUsuario.correoEnUso();
        }
        if (usuarios.existsByNombreUsuario(solicitud.nombreUsuario())) {
            throw ErroresUsuario.nombreUsuarioEnUso();
        }
        Usuario usuario = new Usuario(solicitud.correo(), solicitud.nombreUsuario(), solicitud.nombreVisible(),
                passwordEncoder.encode(solicitud.contrasena()), codigos.codigoInvitacion(), clock.instant());
        try {
            usuarios.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException carrera) {
            throw ErroresUsuario.traducirDuplicado(carrera);
        }
        correos.enviarVerificacion(usuario, tokensCorreo.emitir(usuario, PropositoToken.VERIFICACION));
        return new RespuestaRegistro(usuario.getCorreo());
    }

    /** Verifica el correo y abre sesión, para que la persona entre directo desde el enlace. */
    @Transactional
    public SesionService.Sesion verificar(String token) {
        Usuario usuario = tokensCorreo.consumir(token, PropositoToken.VERIFICACION);
        usuario.verificarCorreo(clock.instant());
        return sesiones.abrirSesion(usuario);
    }

    /** No revela si la cuenta existe: siempre termina bien. */
    @Transactional
    public void reenviarVerificacion(String correo) {
        usuarios.findByCorreo(correo)
                .filter(usuario -> !usuario.correoVerificado())
                .filter(usuario -> tokensCorreo.puedeEmitir(usuario, PropositoToken.VERIFICACION))
                .ifPresent(usuario -> correos.enviarVerificacion(usuario,
                        tokensCorreo.emitir(usuario, PropositoToken.VERIFICACION)));
    }
}
