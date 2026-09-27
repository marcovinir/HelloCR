package com.hellocr.usuarios;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByCorreo(String correo);

    Optional<Usuario> findByNombreUsuario(String nombreUsuario);

    Optional<Usuario> findByCodigoInvitacion(String codigoInvitacion);

    boolean existsByCorreo(String correo);

    boolean existsByNombreUsuario(String nombreUsuario);

    boolean existsByCodigoInvitacion(String codigoInvitacion);

    @Query("select u from Usuario u where u.id = :id and u.correoVerificadoEn is not null")
    Optional<Usuario> verificadoPorId(UUID id);

    @Query("select u from Usuario u where u.nombreUsuario = :nombreUsuario and u.correoVerificadoEn is not null")
    Optional<Usuario> verificadoPorNombreUsuario(String nombreUsuario);

    @Query("select u from Usuario u where u.codigoInvitacion = :codigo and u.correoVerificadoEn is not null")
    Optional<Usuario> verificadoPorCodigo(String codigo);

    /** Borra la cuenta sin verificar que tenga ese correo (la base borra sus tokens en cascada). */
    @Modifying
    @Query("delete from Usuario u where u.correo = :correo and u.correoVerificadoEn is null")
    int borrarSinVerificarPorCorreo(String correo);

    @Modifying
    @Query("delete from Usuario u where u.correoVerificadoEn is null and u.creadoEn < :limite")
    int borrarSinVerificarCreadosAntesDe(Instant limite);
}
