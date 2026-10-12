package emu.grasscutter.server.http;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.config.Configuration;
import org.junit.jupiter.api.Test;

class CorsAllowHostsTest {
    @Test void allowedAndRejectedOriginsApplyToRequestsAndPreflightWithoutNetwork() throws Exception {
        var cors = Configuration.HTTP_POLICIES.cors; var old = cors.allowedOrigins;
        try {
            for (String[] allowed : new String[][] {{"https://one.example"}, {"https://one.example", "https://two.example"}}) {
                cors.allowedOrigins = allowed;
                var server = new HttpServer();
                var field = HttpServer.class.getDeclaredField("javalin"); field.setAccessible(true);
                var app = (io.javalin.Javalin) field.get(server);
                var manager = app.cfg.plugins.getPluginManager();
                var plugins = manager.getClass().getDeclaredField("plugins"); plugins.setAccessible(true);
                var plugin = (io.javalin.plugin.bundled.CorsPlugin) ((java.util.List<?>) plugins.get(manager)).stream()
                        .filter(p -> p instanceof io.javalin.plugin.bundled.CorsPlugin).findFirst().orElseThrow();
                var handle = plugin.getClass().getDeclaredMethod("handleCors", io.javalin.http.Context.class, io.javalin.plugin.bundled.CorsPluginConfig.class);
                handle.setAccessible(true);
                for (var method : new io.javalin.http.HandlerType[] {io.javalin.http.HandlerType.GET, io.javalin.http.HandlerType.OPTIONS}) {
                    for (String origin : new String[] {"https://one.example", "https://two.example", "https://denied.example"}) {
                        var output = new java.util.HashMap<String, Object>();
                        var context = (io.javalin.http.Context) java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(), new Class<?>[] {io.javalin.http.Context.class}, (proxy, m, args) -> {
                                    if (m.getName().equals("method")) return method;
                                    if (m.getName().equals("header") && args.length == 1) return switch ((String) args[0]) {
                                        case "Origin" -> origin;
                                        case "Access-Control-Request-Method" -> "GET";
                                        case "Access-Control-Request-Headers" -> "X-Test";
                                        default -> null;
                                    };
                                    if (m.getName().equals("header") && args.length == 2) { output.put((String) args[0], args[1]); return proxy; }
                                    if (m.getName().equals("status")) { output.put("status", args[0]); return proxy; }
                                    throw new UnsupportedOperationException(m.getName());
                                });
                        handle.invoke(plugin, context, plugin.getConfigs().get(0));
                        if (java.util.Arrays.asList(allowed).contains(origin)) {
                            assertEquals(origin, output.get("Access-Control-Allow-Origin"));
                            if (method == io.javalin.http.HandlerType.OPTIONS) assertEquals("GET", output.get("Access-Control-Allow-Methods"));
                        } else {
                            assertNull(output.get("Access-Control-Allow-Origin"));
                            assertEquals(io.javalin.http.HttpStatus.BAD_REQUEST, output.get("status"));
                        }
                    }
                }
            }
        } finally { cors.allowedOrigins = old; }
    }
    @Test void twoIndividualOriginsAreAcceptedByPinnedCorsApi() {
        var cors = Configuration.HTTP_POLICIES.cors;
        var old = cors.allowedOrigins;
        try {
            cors.allowedOrigins = new String[] {"https://one.example", "https://two.example"};
            assertDoesNotThrow(HttpServer::new);
        } finally { cors.allowedOrigins = old; }
    }
}
