package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioPropio;
import com.hellocr.usuarios.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SesionService {

    private final UsuarioRepository usuarios;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final LimiteIntentosLogin limiteIntentos;
    /** Se compara contra este hash cuando la cuenta no existe, para que el login tarde lo mismo. */
    private final String hashFicticio;

    public SesionService(UsuarioRepository usuarios, JwtService jwtService, RefreshTokenService refreshTokens,
            PasswordEncoder passwordEncoder, LimiteIntentosLogin limiteIntentos) {
        this.usuarios = usuarios;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.limiteIntentos = limiteIntentos;
        this.hashFicticio = passwordEncoder.encode("contraseña-que-nadie-usa");
    }

    /** Sesión recién abierta: la respuesta para el cuerpo y el refresh token para la cookie. */
    public record Sesion(RespuestaSesion respuesta, String refreshToken) {
    }

    @Transactional
    public Sesion login(SolicitudLogin solicitud, String ip) {
        Identificador identificador = Identificador.de(solicitud.identificador());
        String clave = identificador.valor() + "|" + ip;
        limiteIntentos.verificar(clave);
        Usuario usuario = (identificador.esCorreo()
                ? usuarios.findByCorreo(identificador.valor())
                : usuarios.findByNombreUsuario(identificador.valor())).orElse(null);
        String hash = usuario == null ? hashFicticio : usuario.getHashContrasena();
        boolean valida = !Contrasenas.excedeLimite(solicitud.contrasena())
                && passwordEncoder.matches(solicitud.contrasena(), hash)
                && usuario != null;
        if (!valida) {
            limiteIntentos.registrarFallo(clave);
            throw new ErrorNegocio(CodigoError.CREDENCIALES_INVALIDAS, "El usuario o la contraseña no son correctos.");
        }
        limiteIntentos.registrarExito(clave);
        if (!usuario.correoVerificado()) {
            throw new ErrorNegocio(CodigoError.CORREO_NO_VERIFICADO,
                    "Todavía no verificaste tu correo. Revisá tu bandeja de entrada.");
        }
        return abrirSesion(usuario);
    }

    @Transactional(noRollbackFor = ErrorNegocio.class)
    public Sesion refrescar(String refreshToken) {
        RefreshTokenService.Rotacion rotacion = refreshTokens.rotar(refreshToken);
        return new Sesion(respuestaPara(rotacion.usuario()), rotacion.nuevoToken());
    }

    @Transactional
    public void cerrarSesion(String refreshToken) {
        refreshTokens.revocar(refreshToken);
    }

    /** También la usa la verificación de correo, que abre sesión al terminar. */
    @Transactional
    public Sesion abrirSesion(Usuario usuario) {
        return new Sesion(respuestaPara(usuario), refreshTokens.emitir(usuario));
    }

    private RespuestaSesion respuestaPara(Usuario usuario) {
        return new RespuestaSesion(jwtService.emitir(usuario), UsuarioPropio.de(usuario));
    }
}
