package com.hellocr.mensajes;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.ErroresConversacion;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Spec 8.1: valida, asigna la secuencia y guarda. Quién se entera lo deciden los que escuchan MensajeEnviado. */
@Service
public class EnvioMensajesService {

    static final int MAX_CARACTERES = 4096;

    public record Resultado(MensajeDto mensaje, boolean nuevo) {
    }

    private final ConversacionRepository conversaciones;
    private final ConsultaMembresia membresia;
    private final MensajeRepository mensajes;
    private final MarcasRepository marcas;
    private final LimiteMensajes limite;
    private final ApplicationEventPublisher eventos;
    private final TransactionTemplate transacciones;
    private final Clock clock;

    public EnvioMensajesService(ConversacionRepository conversaciones, ConsultaMembresia membresia,
            MensajeRepository mensajes, MarcasRepository marcas, LimiteMensajes limite,
            ApplicationEventPublisher eventos, TransactionTemplate transacciones, Clock clock) {
        this.conversaciones = conversaciones;
        this.membresia = membresia;
        this.mensajes = mensajes;
        this.marcas = marcas;
        this.limite = limite;
        this.eventos = eventos;
        this.transacciones = transacciones;
        this.clock = clock;
    }

    public Resultado enviar(UUID remitenteId, SolicitudEnvio solicitud) {
        String texto = validar(solicitud);
        limite.consumir(remitenteId);
        try {
            return transacciones.execute(estado -> guardar(remitenteId, solicitud, texto));
        } catch (DuplicateKeyException carrera) {
            // Otra transacción guardó el mismo idCliente entre la consulta y el INSERT: es un reintento.
            return mensajes.porIdCliente(remitenteId, solicitud.idCliente())
                    .map(original -> new Resultado(original, false))
                    .orElseThrow(() -> carrera);
        }
    }

    private Resultado guardar(UUID remitenteId, SolicitudEnvio solicitud, String texto) {
        UUID conversacionId = solicitud.conversacionId();
        if (conversaciones.bloquear(conversacionId).isEmpty()) {
            throw ErroresConversacion.noEncontrada();
        }
        if (!membresia.esMiembroActivo(conversacionId, remitenteId)) {
            throw ErroresConversacion.noEsMiembro();
        }
        Optional<MensajeDto> repetido = mensajes.porIdCliente(remitenteId, solicitud.idCliente());
        if (repetido.isPresent()) {
            return new Resultado(repetido.get(), false);
        }
        long secuencia = mensajes.siguienteSecuencia(conversacionId);
        MensajeDto mensaje = mensajes.insertarTexto(conversacionId, secuencia, remitenteId, solicitud.idCliente(),
                texto, Tiempos.ahora(clock));
        marcas.avanzarPropias(conversacionId, remitenteId, secuencia);
        eventos.publishEvent(new MensajeEnviado(mensaje));
        return new Resultado(mensaje, true);
    }

    /**
     * Devuelve el texto recortado. Se cuenta por punto de código, como PostgreSQL: un emoji es un carácter
     * aunque en Java ocupe dos. El carácter nulo se rechaza acá porque PostgreSQL no lo admite en un texto.
     */
    static String validar(SolicitudEnvio solicitud) {
        if (solicitud == null || solicitud.idCliente() == null || solicitud.conversacionId() == null) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "Faltan datos del mensaje.");
        }
        String texto = solicitud.texto() == null ? "" : solicitud.texto().strip();
        if (texto.isEmpty()) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "El mensaje está vacío.");
        }
        if (texto.indexOf('\u0000') >= 0) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "El mensaje tiene caracteres no permitidos.");
        }
        if (texto.codePointCount(0, texto.length()) > MAX_CARACTERES) {
            throw new ErrorNegocio(CodigoError.TEXTO_MUY_LARGO, "El mensaje puede tener hasta 4096 caracteres.");
        }
        return texto;
    }
}
