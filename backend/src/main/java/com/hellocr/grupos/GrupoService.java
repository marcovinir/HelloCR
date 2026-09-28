package com.hellocr.grupos;

import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ConversacionDetalle;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.LecturaConversacionesService;
import com.hellocr.conversaciones.Rol;
import com.hellocr.conversaciones.TipoConversacion;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Spec 8.5: crear y editar grupos. Los miembros y roles los maneja MiembrosGrupoService. */
@Service
public class GrupoService {

    private final UsuarioRepository usuarios;
    private final ConversacionRepository conversaciones;
    private final GrupoRepository grupos;
    private final GestionMiembros gestion;
    private final OperacionesGrupo operaciones;
    private final LecturaConversacionesService lectura;
    private final GruposProperties propiedades;
    private final Clock clock;

    public GrupoService(UsuarioRepository usuarios, ConversacionRepository conversaciones, GrupoRepository grupos,
            GestionMiembros gestion, OperacionesGrupo operaciones, LecturaConversacionesService lectura,
            GruposProperties propiedades, Clock clock) {
        this.usuarios = usuarios;
        this.conversaciones = conversaciones;
        this.grupos = grupos;
        this.gestion = gestion;
        this.operaciones = operaciones;
        this.lectura = lectura;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Quien crea queda como ADMIN; todos ven desde el evento GRUPO_CREADO (secuencia 1). */
    @Transactional
    public ConversacionDetalle crear(UUID creadorId, SolicitudGrupo solicitud) {
        Set<UUID> invitados = new LinkedHashSet<>(solicitud.miembrosIds());
        if (invitados.contains(creadorId)) {
            throw ErrorNegocio.validacion("miembrosIds", "No te incluyas en la lista: ya sos parte del grupo.");
        }
        if (invitados.size() > propiedades.maxMiembros() - 1) {
            throw ErrorNegocio.validacion("miembrosIds",
                    "Un grupo puede tener hasta " + propiedades.maxMiembros() + " personas, contándote a vos.");
        }
        for (UUID invitado : invitados) {
            if (usuarios.verificadoPorId(invitado).isEmpty()) {
                throw ErroresUsuario.noEncontrado();
            }
        }
        UUID grupoId = conversaciones.crear(TipoConversacion.GRUPO, Tiempos.ahora(clock));
        grupos.crear(grupoId, solicitud.nombre(), solicitud.descripcion(), creadorId);
        gestion.incorporar(grupoId, creadorId, Rol.ADMIN, 1);
        invitados.forEach(invitado -> gestion.incorporar(grupoId, invitado, Rol.MIEMBRO, 1));
        operaciones.registrarEvento(grupoId, creadorId, TipoEvento.GRUPO_CREADO, null, null);
        operaciones.avisarCambio(grupoId);
        return lectura.detalle(creadorId, grupoId);
    }

    /** Cambiar el nombre deja un evento en el historial; la descripción cambia sin evento. */
    @Transactional
    public ConversacionDetalle editar(UUID actorId, UUID grupoId, SolicitudEdicionGrupo solicitud) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        if (solicitud.nombre() != null && !solicitud.nombre().equals(grupos.nombre(grupoId))) {
            grupos.cambiarNombre(grupoId, solicitud.nombre());
            operaciones.registrarEvento(grupoId, actorId, TipoEvento.NOMBRE_CAMBIADO, null, solicitud.nombre());
        }
        if (solicitud.descripcion() != null) {
            grupos.cambiarDescripcion(grupoId, solicitud.descripcion().isEmpty() ? null : solicitud.descripcion());
        }
        operaciones.avisarCambio(grupoId);
        return lectura.detalle(actorId, grupoId);
    }
}
