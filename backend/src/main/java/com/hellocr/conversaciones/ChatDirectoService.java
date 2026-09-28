package com.hellocr.conversaciones;

import com.hellocr.comun.Tiempos;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatDirectoService {

    public record Apertura(ConversacionDetalle detalle, boolean creada) {
    }

    private final UsuarioRepository usuarios;
    private final ChatDirectoRepository directos;
    private final ConversacionRepository conversaciones;
    private final GestionMiembros gestion;
    private final LecturaConversacionesService lectura;
    private final TransactionTemplate transacciones;
    private final Clock clock;

    public ChatDirectoService(UsuarioRepository usuarios, ChatDirectoRepository directos,
            ConversacionRepository conversaciones, GestionMiembros gestion, LecturaConversacionesService lectura,
            TransactionTemplate transacciones, Clock clock) {
        this.usuarios = usuarios;
        this.directos = directos;
        this.conversaciones = conversaciones;
        this.gestion = gestion;
        this.lectura = lectura;
        this.transacciones = transacciones;
        this.clock = clock;
    }

    /**
     * Devuelve el chat con esa persona y lo crea si no existía (spec 8.4). Si dos lo crean a la vez, la
     * restricción única del par hace fallar una transacción: se revierte entera y se lee el chat que ganó.
     */
    public Apertura abrir(UUID yo, UUID otro) {
        if (yo.equals(otro)) {
            throw ErroresConversacion.chatConsigoMismo();
        }
        if (usuarios.verificadoPorId(otro).isEmpty()) {
            throw ErroresUsuario.noEncontrado();
        }
        Optional<UUID> existente = directos.buscar(yo, otro);
        if (existente.isPresent()) {
            return new Apertura(lectura.detalle(yo, existente.get()), false);
        }
        try {
            UUID nueva = transacciones.execute(estado -> crear(yo, otro));
            return new Apertura(lectura.detalle(yo, nueva), true);
        } catch (DuplicateKeyException carrera) {
            return new Apertura(lectura.detalle(yo, directos.buscar(yo, otro).orElseThrow()), false);
        }
    }

    private UUID crear(UUID yo, UUID otro) {
        UUID id = conversaciones.crear(TipoConversacion.DIRECTA, Tiempos.ahora(clock));
        gestion.incorporar(id, yo, Rol.MIEMBRO, 1);
        gestion.incorporar(id, otro, Rol.MIEMBRO, 1);
        directos.registrar(id, yo, otro);
        return id;
    }
}
