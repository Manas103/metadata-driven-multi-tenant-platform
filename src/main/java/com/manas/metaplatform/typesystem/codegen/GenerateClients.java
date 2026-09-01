package com.manas.metaplatform.typesystem.codegen;

import com.manas.metaplatform.typesystem.compiler.JavaClientEmitter;
import com.manas.metaplatform.typesystem.compiler.TypePlatformCompiler;
import com.manas.metaplatform.typesystem.compiler.TypeScriptClientEmitter;
import com.manas.metaplatform.typesystem.dsl.TypeDecl;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Standalone entry point (not a Spring bean; needs no application context) that writes the
 * generated Java and TypeScript client source for every declared type to disk. Run
 * explicitly, after {@code mvn compile}, with:
 *
 * <pre>mvn exec:java -Dexec.mainClass=com.manas.metaplatform.typesystem.codegen.GenerateClients</pre>
 *
 * <p>This two-step shape (compile the emitters, then run them to produce sources that a
 * second {@code mvn test} compiles alongside everything else) is what let this repository
 * avoid fighting Maven's phase ordering: a Maven module cannot compile source files that a
 * test running inside that same module's test phase has not written yet. See the README's
 * "Findings" section for the version of this that did not work first.
 */
public final class GenerateClients {

    private static final String DSL_RESOURCE = "typesystem/declared-types.dsl";
    private static final Path JAVA_OUT = Path.of("generated-clients", "java");
    private static final Path TS_OUT = Path.of("generated-clients", "typescript");

    public static void main(String[] args) {
        TypePlatformCompiler compiler = new TypePlatformCompiler();
        List<TypeDecl> types = compiler.parse(readDslSource());

        JavaClientEmitter javaEmitter = compiler.javaClientEmitter();
        TypeScriptClientEmitter tsEmitter = compiler.typeScriptClientEmitter();

        int javaFiles = 0;
        int tsFiles = 0;
        for (TypeDecl type : types) {
            javaFiles += writeAll(JAVA_OUT, javaEmitter.emit(type));
            tsFiles += writeAll(TS_OUT, tsEmitter.emit(type));
        }
        writeOne(TS_OUT.resolve("index.ts"), tsEmitter.emitIndex(types));
        tsFiles++;

        System.out.println("Declared types: " + types.size());
        System.out.println("Java files written: " + javaFiles + " under " + JAVA_OUT);
        System.out.println("TypeScript files written: " + tsFiles + " under " + TS_OUT);
    }

    private static int writeAll(Path root, Map<String, String> files) {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            writeOne(root.resolve(entry.getKey()), entry.getValue());
        }
        return files.size();
    }

    private static void writeOne(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readDslSource() {
        try (var input = new ClassPathResource(DSL_RESOURCE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
