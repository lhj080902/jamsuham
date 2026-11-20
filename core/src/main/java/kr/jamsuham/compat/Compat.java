package kr.jamsuham.compat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 오래된 안드로이드 버전(8~13)에 없는 Java 9~16 메서드의 대체 구현.
 * tools/ViaPatcher.java 가 Via jar 의 호출을 여기로 연결한다.
 */
public final class Compat {

    private Compat() {
    }

    public static boolean isEmpty(StringBuilder sb) {
        return sb.length() == 0;
    }

    public static String formatted(String format, Object[] args) {
        return String.format(format, args);
    }

    public static Stream<String> lines(String s) {
        BufferedReader reader = new BufferedReader(new StringReader(s));
        return reader.lines();
    }

    @SuppressWarnings("unchecked")
    public static <T> T[] toArray(Collection<?> collection, IntFunction<T[]> generator) {
        T[] empty = generator.apply(0);
        if (collection.isEmpty()) {
            return empty;
        }
        T[] arr = (T[]) Array.newInstance(empty.getClass().getComponentType(), collection.size());
        return collection.toArray(arr);
    }

    public static <T> List<T> toList(Stream<T> stream) {
        List<T> list = stream.collect(Collectors.toCollection(ArrayList::new));
        return Collections.unmodifiableList(list);
    }

    public static boolean isEmpty(Optional<?> optional) {
        return !optional.isPresent();
    }

    public static <T> T orElseThrow(Optional<T> optional) {
        if (!optional.isPresent()) {
            throw new NoSuchElementException("No value present");
        }
        return optional.get();
    }

    public static <T> T requireNonNullElse(T obj, T defaultObj) {
        return obj != null ? obj : Objects.requireNonNull(defaultObj, "defaultObj");
    }

    public static <T> T requireNonNullElseGet(T obj, Supplier<? extends T> supplier) {
        return obj != null ? obj : Objects.requireNonNull(Objects.requireNonNull(supplier, "supplier").get(), "supplier.get()");
    }
}
