package io.toolgate.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Loads the application's classes: a policy, a catalog, a resolver, a JDBC driver. */
final class Classes implements AutoCloseable {

    private final URLClassLoader loader;

    Classes(List<Path> classpath) {
        var urls = new ArrayList<URL>();
        for (Path entry : classpath) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("not a classpath entry: " + entry, e);
            }
        }
        this.loader = new URLClassLoader(urls.toArray(URL[]::new), Classes.class.getClassLoader());
    }

    ClassLoader loader() {
        return loader;
    }

    /**
     * What a class supplies: it must have a public no-argument constructor and implement
     * {@code Supplier<T>}.
     */
    <T> T supplied(String className, Class<T> type) {
        Object instance;
        try {
            instance = Class.forName(className, true, loader).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot create " + className + " (it needs a public no-argument"
                    + " constructor, on the classpath): " + e, e);
        }
        if (!(instance instanceof Supplier<?> supplier)) {
            throw new IllegalArgumentException(className + " must implement java.util.function.Supplier<"
                    + type.getSimpleName() + ">");
        }
        Object supplied = supplier.get();
        if (!type.isInstance(supplied)) {
            throw new IllegalArgumentException(className + " supplied " + (supplied == null ? "null" : supplied.getClass().getName())
                    + ", not a " + type.getName());
        }
        return type.cast(supplied);
    }

    @Override
    public void close() {
        try {
            loader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
