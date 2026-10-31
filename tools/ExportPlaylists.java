import com.string.iptv.catalog.PlaylistEntry;
import com.string.iptv.catalog.PlaylistParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintStream;
import java.util.stream.Collectors;

/** Export with the application's real parser. Run using Java source mode and compiled app classes. */
public class ExportPlaylists {
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        String sourceId = args[0];
        String text = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        for (PlaylistEntry entry : PlaylistParser.INSTANCE.parse(text, sourceId)) {
            String headers = entry.getStream().getHeaders().entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + quote(e.getValue())).collect(Collectors.joining(","));
            System.out.println("{\"source\":" + quote(sourceId) + ",\"name\":" + quote(entry.getName())
                + ",\"group\":" + quote(entry.getGroup()) + ",\"url\":" + quote(entry.getStream().getUrl())
                + ",\"tvgId\":" + quote(entry.getTvgId()) + ",\"logo\":" + quote(entry.getLogo())
                + ",\"headers\":{" + headers + "}}");
        }
    }
}
