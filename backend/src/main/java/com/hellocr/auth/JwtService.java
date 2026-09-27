package com.hellocr.auth;

import com.hellocr.config.JwtProperties;
import com.hellocr.usuarios.Usuario;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String EMISOR = "hellocr";

    private final JwtEncoder encoder;
    private final JwtProperties propiedades;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, JwtProperties propiedades, Clock clock) {
        this.encoder = encoder;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    public String emitir(Usuario usuario) {
        Instant ahora = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(EMISOR)
                .subject(usuario.getId().toString())
                .issuedAt(ahora)
                .expiresAt(ahora.plus(propiedades.duracionAccess()))
                .claim("nombreUsuario", usuario.getNombreUsuario())
                .build();
        JwsHeader cabecera = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(cabecera, claims)).getTokenValue();
    }
}
