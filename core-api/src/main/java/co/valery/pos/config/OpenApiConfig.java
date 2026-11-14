package co.valery.pos.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentación interactiva de la API en /swagger-ui.html (botón "Authorize" con el accessToken JWT). */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("POS Droguería & Colmena Valery — API del núcleo transaccional").version("0.1.0")
                        .description("Ventas con FEFO, inventario por lotes, caja, sincronización offline, reportes, ABC, proyecciones e importación. "
                                + "Roles: ADMIN (administradora) y CAJERO. Las rutas /api/v1/admin/** son exclusivas de la administradora."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
