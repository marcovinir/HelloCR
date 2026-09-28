package com.hellocr.grupos;

import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.Rol;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec 8.5: cada cambio deja un evento con su secuencia, y los periodos de membresía empiezan o terminan
 * justo en ese evento. Todo pasa con el grupo bloqueado, así nunca se supera el máximo de miembros.
 */
@Service
public class MiembrosGrupoService {

    private final UsuarioRepository usuarios;
    private final ConsultaMembresia membresia;
    private final GestionMiembros gestion;
    private final OperacionesGrupo operaciones;
    private final GruposProperties propiedades;

    public MiembrosGrupoService(UsuarioRepository usuarios, ConsultaMembresia membresia, GestionMiembros gestion,
            OperacionesGrupo operaciones, GruposProperties propiedades) {
        this.usuarios = usuarios;
        this.membresia = membresia;
        this.gestion = gestion;
        this.operaciones = operaciones;
        this.propiedades = propiedades;
    }

    /** Quien entra ve desde el evento "te agregaron" en adelante. */
    @Transactional
    public void agregar(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        if (usuarios.verificadoPorId(usuarioId).isEmpty()) {
            throw ErroresUsuario.noEncontrado();
        }
        if (membresia.esMiembroActivo(grupoId, usuarioId)) {
            throw ErroresConversacion.yaEsMiembro();
        }
        if (gestion.contarActivos(grupoId) >= propiedades.maxMiembros()) {
            throw ErroresConversacion.grupoLleno(propiedades.maxMiembros());
        }
        long secuencia = operaciones.registrarEvento(grupoId, actorId, TipoEvento.MIEMBRO_AGREGADO, usuarioId, null);
        gestion.incorporar(grupoId, usuarioId, Rol.MIEMBRO, secuencia);
        operaciones.avisarCambio(grupoId);
    }

    /** Quien sale ve hasta el evento "te quitaron"; después, el chat le queda de solo lectura. */
    @Transactional
    public void quitar(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        if (actorId.equals(usuarioId)) {
            throw ErrorNegocio.validacion("usuarioId", "Para irte del grupo usá «Salir».");
        }
        if (!membresia.esMiembroActivo(grupoId, usuarioId)) {
            throw ErroresConversacion.personaFueraDelGrupo();
        }
        long secuencia = operaciones.registrarEvento(grupoId, actorId, TipoEvento.MIEMBRO_QUITADO, usuarioId, null);
        gestion.retirar(grupoId, usuarioId, secuencia);
        operaciones.avisarCambio(grupoId, usuarioId);
    }

    /** Si se va el último admin y queda alguien, asciende el miembro activo más antiguo. */
    @Transactional
    public void salir(UUID actorId, UUID grupoId) {
        operaciones.bloquearComoMiembro(grupoId, actorId);
        long secuencia = operaciones.registrarEvento(grupoId, actorId, TipoEvento.MIEMBRO_SALIO, null, null);
        gestion.retirar(grupoId, actorId, secuencia);
        if (gestion.contarAdministradoresActivos(grupoId) == 0) {
            gestion.activoMasAntiguo(grupoId).ifPresent(sucesor -> {
                gestion.cambiarRol(grupoId, sucesor, Rol.ADMIN);
                operaciones.registrarEvento(grupoId, actorId, TipoEvento.ADMIN_ASIGNADO, sucesor, null);
            });
        }
        operaciones.avisarCambio(grupoId, actorId);
    }

    @Transactional
    public void hacerAdmin(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        Rol rol = gestion.rolActivo(grupoId, usuarioId).orElseThrow(ErroresConversacion::personaFueraDelGrupo);
        if (rol == Rol.ADMIN) {
            return;
        }
        gestion.cambiarRol(grupoId, usuarioId, Rol.ADMIN);
        operaciones.registrarEvento(grupoId, actorId, TipoEvento.ADMIN_ASIGNADO, usuarioId, null);
        operaciones.avisarCambio(grupoId);
    }

    /** Un admin puede quitarse el rol a sí mismo, siempre que quede otro. */
    @Transactional
    public void quitarAdmin(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        Rol rol = gestion.rolActivo(grupoId, usuarioId).orElseThrow(ErroresConversacion::personaFueraDelGrupo);
        if (rol == Rol.MIEMBRO) {
            return;
        }
        if (gestion.contarAdministradoresActivos(grupoId) == 1) {
            throw ErroresConversacion.ultimoAdmin();
        }
        gestion.cambiarRol(grupoId, usuarioId, Rol.MIEMBRO);
        operaciones.registrarEvento(grupoId, actorId, TipoEvento.ADMIN_QUITADO, usuarioId, null);
        operaciones.avisarCambio(grupoId);
    }
}
