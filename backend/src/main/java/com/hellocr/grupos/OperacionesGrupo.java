package com.hellocr.grupos;

import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ConversacionActualizada;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.ErroresConversacion;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.Rol;
import com.hellocr.conversaciones.TipoConversacion;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.MensajeEnviado;
import com.hellocr.mensajes.MensajeRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Lo que comparten todas las operaciones de un grupo: el bloqueo, los permisos, los eventos y los avisos. */
@Component
public class OperacionesGrupo {

    private final ConversacionRepository conversaciones;
    private final ConsultaMembresia membresia;
    private final GestionMiembros gestion;
    private final MensajeRepository mensajes;
    private final EventosGrupoRepository eventosGrupo;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public OperacionesGrupo(ConversacionRepository conversaciones, ConsultaMembresia membresia,
            GestionMiembros gestion, MensajeRepository mensajes, EventosGrupoRepository eventosGrupo,
            ApplicationEventPublisher eventos, Clock clock) {
        this.conversaciones = conversaciones;
        this.membresia = membresia;
        this.gestion = gestion;
        this.mensajes = mensajes;
        this.eventosGrupo = eventosGrupo;
        this.eventos = eventos;
        this.clock = clock;
    }

    /**
     * Bloquea el grupo (spec 8) y exige que el actor sea miembro activo. A quien nunca fue miembro se le dice que
     * el grupo no existe; a quien ya salió, que no es miembro.
     */
    public void bloquearComoMiembro(UUID grupoId, UUID actorId) {
        boolean esGrupo = conversaciones.bloquear(grupoId).filter(tipo -> tipo == TipoConversacion.GRUPO).isPresent();
        if (!esGrupo || !membresia.fueMiembro(grupoId, actorId)) {
            throw ErroresConversacion.grupoNoEncontrado();
        }
        if (!membresia.esMiembroActivo(grupoId, actorId)) {
            throw ErroresConversacion.noEsMiembro();
        }
    }

    public void bloquearComoAdmin(UUID grupoId, UUID actorId) {
        bloquearComoMiembro(grupoId, actorId);
        if (gestion.rolActivo(grupoId, actorId).filter(rol -> rol == Rol.ADMIN).isEmpty()) {
            throw ErroresConversacion.sinPermiso();
        }
    }

    /** Inserta el mensaje EVENTO con la próxima secuencia y lo publica. Llamar con el grupo bloqueado. */
    public long registrarEvento(UUID grupoId, UUID actorId, TipoEvento evento, UUID afectadoId, String valor) {
        long secuencia = mensajes.siguienteSecuencia(grupoId);
        MensajeDto mensaje = eventosGrupo.insertar(grupoId, secuencia, actorId, evento, afectadoId, valor,
                Tiempos.ahora(clock));
        eventos.publishEvent(new MensajeEnviado(mensaje));
        return secuencia;
    }

    /** CONVERSACION_ACTUALIZADA para los miembros activos y, además, para quienes acaban de salir. */
    public void avisarCambio(UUID grupoId, UUID... ademas) {
        Set<UUID> afectados = new LinkedHashSet<>(membresia.miembrosActivos(grupoId));
        afectados.addAll(Arrays.asList(ademas));
        eventos.publishEvent(new ConversacionActualizada(grupoId, Set.copyOf(afectados)));
    }
}
