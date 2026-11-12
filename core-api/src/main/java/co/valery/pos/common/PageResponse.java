package co.valery.pos.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static int limitarTamano(int size) {
        return Math.min(Math.max(size, 1), 200);
    }
}
