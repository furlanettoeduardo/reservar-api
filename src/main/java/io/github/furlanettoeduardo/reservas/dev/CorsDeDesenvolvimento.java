package io.github.furlanettoeduardo.reservas.dev;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS para o Vite em {@code localhost:5173} conversar com a API em {@code localhost:8080}.
 *
 * <p>Restrito ao perfil {@code dev} e a origem exata. Nao e anotacao de origem coringa espalhada
 * nos controllers, por dois motivos: origem coringa e incompativel com envio de credencial e vira
 * brecha no dia em que autenticacao entrar, e configuracao espalhada por anotacao nao tem um
 * lugar unico onde se possa auditar o que esta liberado.
 *
 * <p>Metodos limitados a GET e POST porque sao os unicos que a API expoe -- cancelamento e POST
 * em {@code /reservas/{id}/cancelamento}, nao DELETE.
 */
@Configuration
@Profile("dev")
public class CorsDeDesenvolvimento implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        registro.addMapping("/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
