package com.hellocr.soporte;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class PruebasConfig {

    /** Reemplaza al Clock del sistema en todos los beans que piden un Clock. */
    @Bean
    @Primary
    public RelojAjustable relojAjustable(@Value("${app.zona-horaria}") String zonaHoraria) {
        return new RelojAjustable(ZoneId.of(zonaHoraria));
    }

    /** Reemplaza al enviador de la consola: los tests leen los correos del buzón. */
    @Bean
    @Primary
    public BuzonPrueba buzonPrueba() {
        return new BuzonPrueba();
    }

    /** Para que las aserciones de MockMvc lean bien las tildes de las respuestas. */
    @Bean
    public MockMvcBuilderCustomizer respuestasEnUtf8() {
        return constructor -> constructor.defaultResponseCharacterEncoding(StandardCharsets.UTF_8);
    }
}
