import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Network-free check of the real Java result/completion publisher; never starts the server. */
public final class CompletionContractProbe {
    static void reset() throws Exception {
        RuntimeIntegration.phase = "fresh";
        RuntimeIntegration.results.clear();
        Files.deleteIfExists(Path.of("fresh-complete.json"));
    }
    static void rejects(String reason) throws Exception {
        try {
            RuntimeIntegration.completePhase();
            throw new IllegalStateException("Accepted " + reason);
        } catch (AssertionError expected) {
            RuntimeIntegration.check(!Files.exists(Path.of("fresh-complete.json")), "Failed phase published completion");
        }
    }
    public static void main(String[] args) throws Exception {
        reset();
        rejects("empty phase");
        RuntimeIntegration.test("exact archive starts HTTP, game ticks and real Mongo", () -> {});
        rejects("startup only");
        RuntimeIntegration.test("exact archive starts HTTP, game ticks and real Mongo", () -> {});
        rejects("duplicate assertions");
        reset();
        RuntimeIntegration.results.add(Map.of("name", "startup", "status", "passed", "durationMillis", 0));
        RuntimeIntegration.results.add(Map.of("name", "login", "status", "failed", "durationMillis", 0));
        rejects("failed assertion");
        reset();
        RuntimeIntegration.test("exact archive starts HTTP, game ticks and real Mongo", () -> {});
        RuntimeIntegration.test("unseeded fresh account completes world login", () -> {});
        RuntimeIntegration.completePhase();
        RuntimeIntegration.check(Files.exists(Path.of("fresh-complete.json")), "Completion not published");
        System.out.println("PASS: Java publisher rejects empty/incomplete/duplicate/failed phases; complete phase atomically published");
    }
}
