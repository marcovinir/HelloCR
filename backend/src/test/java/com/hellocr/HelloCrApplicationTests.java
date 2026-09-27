package com.hellocr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Verifica que el contexto arranca y se conecta a la base hellocr_test. */
@SpringBootTest
@ActiveProfiles("test")
class HelloCrApplicationTests {

    @Test
    void contextLoads() {
    }
}
