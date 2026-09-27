package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tokens_correo")
public class TokenCorreo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PropositoToken proposito;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    @Column(name = "usado_en")
    private Instant usadoEn;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected TokenCorreo() {
    }

    public TokenCorreo(Usuario usuario, PropositoToken proposito, String tokenHash, Instant expiraEn,
            Instant creadoEn) {
        this.usuario = usuario;
        this.proposito = proposito;
        this.tokenHash = tokenHash;
        this.expiraEn = expiraEn;
        this.creadoEn = creadoEn;
    }

    /** Sirve si es del propósito pedido, no se usó y no venció. */
    public boolean sirvePara(PropositoToken buscado, Instant ahora) {
        return proposito == buscado && usadoEn == null && expiraEn.isAfter(ahora);
    }

    public void usar(Instant ahora) {
        usadoEn = ahora;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
