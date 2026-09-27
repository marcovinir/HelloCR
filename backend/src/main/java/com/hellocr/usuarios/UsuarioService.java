package com.hellocr.usuarios;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarios;
    private final GeneradorCodigos codigos;

    public UsuarioService(UsuarioRepository usuarios, GeneradorCodigos codigos) {
        this.usuarios = usuarios;
        this.codigos = codigos;
    }

    @Transactional(readOnly = true)
    public UsuarioPropio propio(UUID id) {
        return UsuarioPropio.de(cargar(id));
    }

    @Transactional
    public UsuarioPropio actualizar(UUID id, SolicitudPerfil solicitud) {
        Usuario usuario = cargar(id);
        if (solicitud.nombreVisible() != null) {
            usuario.cambiarNombreVisible(solicitud.nombreVisible());
        }
        if (solicitud.info() != null) {
            usuario.cambiarInfo(solicitud.info().isEmpty() ? null : solicitud.info());
        }
        String nombreUsuario = solicitud.nombreUsuario();
        if (nombreUsuario != null && !nombreUsuario.equals(usuario.getNombreUsuario())) {
            if (NombresReservados.contiene(nombreUsuario)) {
                throw ErroresUsuario.nombreUsuarioReservado();
            }
            if (usuarios.existsByNombreUsuario(nombreUsuario)) {
                throw ErroresUsuario.nombreUsuarioEnUso();
            }
            usuario.cambiarNombreUsuario(nombreUsuario);
        }
        try {
            usuarios.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException carrera) {
            throw ErroresUsuario.traducirDuplicado(carrera);
        }
        return UsuarioPropio.de(usuario);
    }

    /** El link y el QR anteriores dejan de funcionar. */
    @Transactional
    public NuevoCodigo regenerarCodigo(UUID id) {
        Usuario usuario = cargar(id);
        usuario.cambiarCodigoInvitacion(codigos.codigoInvitacion());
        return new NuevoCodigo(usuario.getCodigoInvitacion());
    }

    /** Solo coincidencia exacta, para que nadie pueda recorrer la lista de usuarios. */
    @Transactional(readOnly = true)
    public PerfilPublico buscar(String nombreUsuario) {
        return usuarios.verificadoPorNombreUsuario(Usuario.normalizarNombreUsuario(nombreUsuario))
                .map(PerfilPublico::de)
                .orElseThrow(ErroresUsuario::noEncontrado);
    }

    @Transactional(readOnly = true)
    public PerfilPublico publico(UUID id) {
        return usuarios.verificadoPorId(id).map(PerfilPublico::de).orElseThrow(ErroresUsuario::noEncontrado);
    }

    @Transactional(readOnly = true)
    public PerfilPublico porInvitacion(String codigo) {
        return usuarios.verificadoPorCodigo(codigo.trim())
                .map(PerfilPublico::de)
                .orElseThrow(ErroresUsuario::noEncontrado);
    }

    /** Si la cuenta ya no existe, el token que la nombra no sirve. */
    private Usuario cargar(UUID id) {
        return usuarios.findById(id).orElseThrow(
                () -> new ErrorNegocio(CodigoError.NO_AUTENTICADO, "La sesión expiró. Iniciá sesión de nuevo."));
    }
}
