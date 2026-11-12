package co.valery.pos.usuarios;

import co.valery.pos.common.ApiException;
import co.valery.pos.config.ConfiguracionService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Política de contraseñas: longitud mínima configurable, mayúscula, minúscula, dígito y símbolo (RNF-08). */
@Component
public class PasswordPolicy {
    private static final String MAYUS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String MINUS = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITOS = "23456789";
    private static final String SIMBOLOS = "#$%&*+-=?@";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConfiguracionService config;

    public PasswordPolicy(ConfiguracionService config) {
        this.config = config;
    }

    public void validar(String password, String username) {
        int min = Math.max(config.getInt("seguridad.password_min", 10), 8);
        List<String> problemas = new ArrayList<>();
        if (password == null || password.length() < min) problemas.add("al menos " + min + " caracteres");
        if (password != null) {
            if (password.length() > 100) problemas.add("máximo 100 caracteres");
            if (password.chars().noneMatch(Character::isUpperCase)) problemas.add("una mayúscula");
            if (password.chars().noneMatch(Character::isLowerCase)) problemas.add("una minúscula");
            if (password.chars().noneMatch(Character::isDigit)) problemas.add("un número");
            if (password.chars().allMatch(c -> Character.isLetterOrDigit(c))) problemas.add("un símbolo");
            if (username != null && !username.isBlank() && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT)))
                problemas.add("no contener el nombre de usuario");
        }
        if (!problemas.isEmpty()) {
            throw ApiException.badRequest("PASSWORD_DEBIL", "La contraseña debe tener: " + String.join(", ", problemas));
        }
    }

    /** Contraseña temporal de 14 caracteres que cumple la política; se entrega una sola vez. */
    public String generarTemporal() {
        List<Character> c = new ArrayList<>();
        for (int i = 0; i < 4; i++) c.add(MAYUS.charAt(RANDOM.nextInt(MAYUS.length())));
        for (int i = 0; i < 5; i++) c.add(MINUS.charAt(RANDOM.nextInt(MINUS.length())));
        for (int i = 0; i < 3; i++) c.add(DIGITOS.charAt(RANDOM.nextInt(DIGITOS.length())));
        for (int i = 0; i < 2; i++) c.add(SIMBOLOS.charAt(RANDOM.nextInt(SIMBOLOS.length())));
        java.util.Collections.shuffle(c, RANDOM);
        StringBuilder sb = new StringBuilder();
        c.forEach(sb::append);
        return sb.toString();
    }
}
