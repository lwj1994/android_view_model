import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs unshrunk against only the R8 output, including its obfuscated class names. */
public final class MetadataVerifier {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        require(lines.size() == 8, "Expected eight fixture classes, received " + lines);
        Class<?>[] types = new Class<?>[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            require(line.startsWith("class "), "Unexpected smoke output: " + line);
            types[index] = Class.forName(line.substring("class ".length()));
        }

        parameters(types[0], 1, "generic ViewModel");
        parameters(types[1], 0, "plain ViewModel");
        require(types[1].getEnclosingClass() == null, "Plain ViewModel acquired an enclosing class");
        inner(types[2], "inner ViewModel of a generic outer");
        inner(types[3], "ViewModel nested through two non-static inner classes");
        inner(types[4], "inner ViewModel without an enclosing-object read");
        staticNested(types[5], 0, "static ViewModel of a generic outer");
        staticNested(types[6], 1, "generic static ViewModel");
        staticNested(types[7], 0, "non-generic static nested ViewModel");
        System.out.println("PASS: generic and inner-class metadata checks passed for all eight fixtures.");
    }

    private static void parameters(Class<?> type, int expected, String label) {
        require(type.getTypeParameters().length == expected,
            label + " lost its type parameters: " + type.getName());
    }

    private static void inner(Class<?> type, String label) {
        parameters(type, 0, label);
        require(type.getEnclosingClass() != null, label + " lost its enclosing class: " + type.getName());
        require(!Modifier.isStatic(type.getModifiers()), label + " became static: " + type.getName());
    }

    private static void staticNested(Class<?> type, int expectedParameters, String label) {
        parameters(type, expectedParameters, label);
        // R8 may remove an unused outer relationship. Either representation must
        // remain distinguishable from a non-static inner class at the raw API.
        require(type.getEnclosingClass() == null || Modifier.isStatic(type.getModifiers()),
            label + " became indistinguishable from a non-static inner class: " + type.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
