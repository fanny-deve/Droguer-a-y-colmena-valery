package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import co.valery.pos.security.RateLimiter;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void permiteHastaElLimiteBloqueaElExcedenteYSeReiniciaCadaMinuto() {
        RateLimiter l = new RateLimiter(3);
        long t = 1_000_000_000L;
        assertThat(l.intentar("ip-a", t)).isZero();
        assertThat(l.intentar("ip-a", t + 10)).isZero();
        assertThat(l.intentar("ip-a", t + 20)).isZero();
        assertThat(l.intentar("ip-a", t + 30)).isPositive();         // el cuarto en el mismo minuto se bloquea
        assertThat(l.intentar("ip-b", t + 30)).isZero();             // otra IP no se ve afectada
        assertThat(l.intentar("ip-a", t + 60_000)).isZero();         // al minuto siguiente se reinicia
    }

    @Test
    void elTiempoDeEsperaNuncaSuperaUnMinuto() {
        RateLimiter l = new RateLimiter(1);
        long t = 5 * 60_000L + 10_000;
        l.intentar("x", t);
        int espera = l.intentar("x", t + 1);
        assertThat(espera).isBetween(1, 60);
    }
}
