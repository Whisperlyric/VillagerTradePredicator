package com.villagertradepredicator.core.data;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the vanilla data files that define trade sets straight from the classpath
 * (the game jar), so the predictor follows whatever trades the running game ships —
 * no hardcoded tables. Works inside the game client and in plain unit tests alike.
 */
public final class ClasspathTradeData {
    private ClasspathTradeData() {}

    /** All {@code <prefix>...json} resource paths visible on the classpath, in jar order. */
    public static List<String> listJsonFiles(String prefix) {
        Map<String, Void> out = new LinkedHashMap<>();
        String dirPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        try {
            ClassLoader cl = ClasspathTradeData.class.getClassLoader();
            Enumeration<URL> roots = cl.getResources(prefix);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                String protocol = url.getProtocol();
                if ("jar".equals(protocol)) {
                    listFromJar(url, dirPrefix, out);
                } else if ("file".equals(protocol)) {
                    listFromFile(URI.create(url.toString()).getPath(), dirPrefix, out);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list classpath resources under " + prefix, e);
        }
        return new ArrayList<>(out.keySet());
    }

    private static void listFromJar(URL url, String dirPrefix, Map<String, Void> out) throws IOException {
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(dirPrefix) && name.endsWith(".json")) {
                    out.putIfAbsent(name, null);
                }
            }
        }
    }

    private static void listFromFile(String rootPath, String dirPrefix, Map<String, Void> out) throws IOException {
        Path root = Path.of(rootPath);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace(File.separatorChar, '/'))
                    .map(rel -> dirPrefix + rel)
                    .filter(p -> p.endsWith(".json"))
                    .forEach(p -> out.putIfAbsent(p, null));
        }
    }

    public static Optional<JsonObject> readJson(String path) {
        try (InputStream in = ClasspathTradeData.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Optional.empty();
            }
            JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return Optional.of(parsed.getAsJsonObject());
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
