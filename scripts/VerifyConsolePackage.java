import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Run in Maven verify: rejects missing, stale, or byte-different packaged assets. */
class VerifyConsolePackage {
    public static void main(String[] args) throws Exception {
        Path generated = Path.of(args[0]);
        Path artifact = Path.of(args[1]);
        if (!Files.isRegularFile(generated.resolve("index.html"))) {
            throw new IllegalStateException("Console index.html was not generated");
        }
        Set<String> expected = new TreeSet<>();
        String prefix = "BOOT-INF/classes/static/";
        try (var paths = Files.walk(generated); var zip = new ZipFile(artifact.toFile())) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String name = prefix + generated.relativize(file).toString().replace('\\', '/');
                expected.add(name);
                var entry = zip.getEntry(name);
                if (entry == null) throw new IllegalStateException("Missing packaged asset: " + name);
                try (var bytes = zip.getInputStream(entry)) {
                    if (!Arrays.equals(Files.readAllBytes(file), bytes.readAllBytes())) {
                        throw new IllegalStateException("Packaged asset differs from current build: " + name);
                    }
                }
            }
            Set<String> actual = new TreeSet<>();
            zip.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(prefix))
                    .forEach(e -> actual.add(e.getName()));
            if (!expected.equals(actual)) throw new IllegalStateException("Stale assets in executable JAR: " + actual);
        }
        System.out.println("Verified " + expected.size() + " current console assets in " + artifact);
    }
}
