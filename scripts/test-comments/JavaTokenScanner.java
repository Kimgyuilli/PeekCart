import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens;
import com.sun.tools.javac.util.Context;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only JDK 17 compiler scanner adapter for the D-102 evidence checker. */
public final class JavaTokenScanner {
    private record Part(String kind, String spelling) {}
    private record Gap(int line, String text) {}

    public static void main(String[] paths) throws Exception {
        if (Runtime.version().feature() != 17) {
            throw new IllegalStateException("This evidence checker requires JDK 17");
        }
        ScannerFactory factory = ScannerFactory.instance(new Context());
        DataOutputStream output = new DataOutputStream(System.out);
        output.writeInt(paths.length);
        for (String path : paths) {
            String source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            var scanner = factory.newScanner(source, false);
            List<Part> parts = new ArrayList<>();
            List<Gap> gaps = new ArrayList<>();
            int previousEnd = 0;
            int line = 1;
            do {
                scanner.nextToken();
                var token = scanner.token();
                if (token.kind == Tokens.TokenKind.ERROR) {
                    throw new IllegalArgumentException("Java lexical error: " + path);
                }
                String gap = source.substring(previousEnd, token.pos);
                if (!gap.isBlank()) {
                    gaps.add(new Gap(line, gap));
                }
                line += newlines(gap);
                if (token.kind == Tokens.TokenKind.EOF) {
                    break;
                }
                String spelling = source.substring(token.pos, token.endPos);
                parts.add(new Part(token.kind.name(), spelling));
                line += newlines(spelling);
                previousEnd = token.endPos;
            } while (true);
            output.writeInt(parts.size());
            for (Part part : parts) {
                write(output, part.kind());
                write(output, part.spelling());
            }
            output.writeInt(gaps.size());
            for (Gap gap : gaps) {
                output.writeInt(gap.line());
                write(output, gap.text());
            }
        }
        output.flush();
    }

    private static int newlines(String text) {
        return (int) text.chars().filter(c -> c == '\n').count();
    }

    private static void write(DataOutputStream output, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
