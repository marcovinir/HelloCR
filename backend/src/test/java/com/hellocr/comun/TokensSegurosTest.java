package com.hellocr.comun;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokensSegurosTest {

    @Test
    void generaTokensDistintosSeguroParaUrls() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String token = TokensSeguros.generar();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            vistos.add(token);
        }
        assertThat(vistos).hasSize(100);
    }

    @Test
    void elHashEsSha256EnHexadecimal() {
        assertThat(TokensSeguros.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
