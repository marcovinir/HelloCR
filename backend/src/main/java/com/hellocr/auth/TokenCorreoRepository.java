package com.hellocr.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TokenCorreoRepository extends JpaRepository<TokenCorreo, Long> {

    /** SELECT … FOR UPDATE: dos clics simultáneos en el mismo enlace no pueden usarlo los dos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TokenCorreo t where t.tokenHash = :tokenHash")
    Optional<TokenCorreo> bloquearPorHash(String tokenHash);

    Optional<TokenCorreo> findFirstByUsuario_IdAndPropositoOrderByCreadoEnDesc(UUID usuarioId,
            PropositoToken proposito);

    @Modifying
    @Query("""
            update TokenCorreo t set t.usadoEn = :ahora
            where t.usuario.id = :usuarioId and t.proposito = :proposito and t.usadoEn is null
            """)
    int invalidarVigentes(UUID usuarioId, PropositoToken proposito, Instant ahora);

    @Modifying
    @Query("delete from TokenCorreo t where t.expiraEn < :ahora")
    int borrarVencidos(Instant ahora);
}
