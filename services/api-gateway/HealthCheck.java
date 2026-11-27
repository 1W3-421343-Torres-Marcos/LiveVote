import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Minimal shell-less HTTP probe for Distroless container healthchecks and diagnostics.
 */
public final class HealthCheck {

    private static final int TIMEOUT_MS = 3000;

    private HealthCheck() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.exit(1);
        }
        boolean print = "--print".equals(args[0]);
        String target = print ? (args.length > 1 ? args[1] : null) : args[0];
        if (target == null || target.isBlank()) {
            System.exit(1);
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(target).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);

            int status = conn.getResponseCode();
            if (print) {
                System.out.println("HTTP " + status);
                InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                if (stream != null) {
                    try (InputStream bodyStream = stream) {
                        String body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
                        if (!body.isBlank()) {
                            System.out.println(body);
                        }
                    }
                }
            }
            System.exit(status >= 200 && status < 300 ? 0 : 1);
        } catch (IOException ex) {
            if (print) {
                System.err.println(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            System.exit(1);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
