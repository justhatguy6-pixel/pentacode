import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import org.json.JSONArray;

public class CollisionServer {

    static final String INDOOR_MODE = "INDOOR";
    static final String OUTDOOR_MODE = "OUTDOOR";

    static volatile String SYSTEM_MODE = INDOOR_MODE;

    static final double HUMAN_MODE = 1.0;
    static final double ADAS_MODE = 0.6;
    static final double AUTONOMOUS_MODE = 0.3;

    static volatile double CONTROL_MODE = HUMAN_MODE;
    static final double BASE_REACTION_TIME = 1.0;

    static final ConcurrentHashMap<String, JSONObject> devices = new ConcurrentHashMap<>();
    static volatile JSONObject latestWarnings = new JSONObject();

    // ─── Physics ───

    static double reactionTime() {
        return BASE_REACTION_TIME * CONTROL_MODE;
    }

    static double stoppingDistance(double velocity, String vehicleType) {
        double reactionDistance = velocity * reactionTime();
        double deceleration = vehicleType.equals("BIKE") ? 6.0 : 7.0;
        double brakingDistance = (velocity * velocity) / (2 * deceleration);
        return reactionDistance + brakingDistance;
    }

    static double indoorDistance(JSONObject A, JSONObject B) {
        double dx = A.getDouble("x") - B.getDouble("x");
        double dy = A.getDouble("y") - B.getDouble("y");
        return Math.sqrt(dx * dx + dy * dy);
    }

    static double outdoorDistance(JSONObject A, JSONObject B) {
        double dx = A.getDouble("lat") - B.getDouble("lat");
        double dy = A.getDouble("lng") - B.getDouble("lng");
        return Math.sqrt(dx * dx + dy * dy) * 111000;
    }

    static double calculateDistance(JSONObject A, JSONObject B) {
        return SYSTEM_MODE.equals(INDOOR_MODE) ? indoorDistance(A, B) : outdoorDistance(A, B);
    }

    // ─── Collision Detection ───

    static JSONObject detectCollision() {
        JSONObject warnings = new JSONObject();
        List<String> keys = new ArrayList<>(devices.keySet());

        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                JSONObject A = devices.get(keys.get(i));
                JSONObject B = devices.get(keys.get(j));
                if (A == null || B == null) continue;

                double distance = calculateDistance(A, B);
                double vA = A.getDouble("velocity");
                double vB = B.getDouble("velocity");
                double relativeVelocity = Math.abs(vA - vB);

                double stopA = stoppingDistance(relativeVelocity, A.getString("vehicle_type"));
                double stopB = stoppingDistance(relativeVelocity, B.getString("vehicle_type"));
                double safeStop = Math.max(stopA, stopB);

                if (vA < 5 && vB < 5) continue;

                if (distance < safeStop) {
                    JSONObject warnA = new JSONObject();
                    warnA.put("status", "RISK");
                    warnA.put("with", B.getString("id"));
                    warnA.put("distance", Math.round(distance * 100.0) / 100.0);
                    warnA.put("safe_distance", Math.round(safeStop * 100.0) / 100.0);

                    JSONObject warnB = new JSONObject();
                    warnB.put("status", "RISK");
                    warnB.put("with", A.getString("id"));
                    warnB.put("distance", Math.round(distance * 100.0) / 100.0);
                    warnB.put("safe_distance", Math.round(safeStop * 100.0) / 100.0);

                    warnings.put(A.getString("id"), warnA);
                    warnings.put(B.getString("id"), warnB);
                }
            }
        }
        return warnings;
    }

    // ─── HTTP Helpers ───

    static void addCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type");
    }

    static void sendJson(HttpExchange exchange, int code, JSONObject json) throws IOException {
        addCorsHeaders(exchange);
        byte[] bytes = json.toString().getBytes("UTF-8");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    static String readBody(HttpExchange exchange) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    static void handleOptions(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    // ─── HTTP Server ───

    public static void main(String[] args) throws IOException {
        int PORT = 5000;
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        // POST /api/update — Android/device sends location data
        server.createContext("/api/update", exchange -> {
            if (exchange.getRequestMethod().equals("OPTIONS")) { handleOptions(exchange); return; }
            try {
                JSONObject device = new JSONObject(readBody(exchange));
                device.put("timestamp", System.currentTimeMillis());
                devices.put(device.getString("id"), device);
                latestWarnings = detectCollision();

                JSONObject resp = new JSONObject();
                resp.put("status", "ok");
                if (latestWarnings.has(device.getString("id"))) {
                    resp.put("warning", latestWarnings.get(device.getString("id")));
                }
                sendJson(exchange, 200, resp);
            } catch (Exception e) {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                sendJson(exchange, 400, err);
            }
        });

        // GET /api/devices — frontend fetches all tracked devices
        server.createContext("/api/devices", exchange -> {
            if (exchange.getRequestMethod().equals("OPTIONS")) { handleOptions(exchange); return; }
            try {
                JSONObject resp = new JSONObject();
                JSONArray arr = new JSONArray();
                for (JSONObject d : devices.values()) arr.put(d);
                resp.put("devices", arr);
                resp.put("count", devices.size());
                sendJson(exchange, 200, resp);
            } catch (Exception e) {
                sendJson(exchange, 500, new JSONObject().put("error", e.getMessage()));
            }
        });

        // GET /api/warnings — frontend fetches collision warnings
        server.createContext("/api/warnings", exchange -> {
            if (exchange.getRequestMethod().equals("OPTIONS")) { handleOptions(exchange); return; }
            try {
                JSONObject resp = new JSONObject();
                resp.put("warnings", latestWarnings);
                resp.put("risk_count", latestWarnings.length());
                sendJson(exchange, 200, resp);
            } catch (Exception e) {
                sendJson(exchange, 500, new JSONObject().put("error", e.getMessage()));
            }
        });

        // POST /api/mode — switch system/control mode
        server.createContext("/api/mode", exchange -> {
            if (exchange.getRequestMethod().equals("OPTIONS")) { handleOptions(exchange); return; }
            try {
                JSONObject body = new JSONObject(readBody(exchange));
                if (body.has("system_mode")) {
                    String mode = body.getString("system_mode").toUpperCase();
                    if (mode.equals(INDOOR_MODE) || mode.equals(OUTDOOR_MODE)) SYSTEM_MODE = mode;
                }
                if (body.has("control_mode")) {
                    String cm = body.getString("control_mode").toUpperCase();
                    switch (cm) {
                        case "HUMAN":      CONTROL_MODE = HUMAN_MODE;      break;
                        case "ADAS":       CONTROL_MODE = ADAS_MODE;       break;
                        case "AUTONOMOUS": CONTROL_MODE = AUTONOMOUS_MODE; break;
                    }
                }
                latestWarnings = detectCollision();

                JSONObject resp = new JSONObject();
                resp.put("system_mode", SYSTEM_MODE);
                resp.put("control_mode", CONTROL_MODE == HUMAN_MODE ? "HUMAN" :
                                          CONTROL_MODE == ADAS_MODE ? "ADAS" : "AUTONOMOUS");
                sendJson(exchange, 200, resp);
            } catch (Exception e) {
                sendJson(exchange, 400, new JSONObject().put("error", e.getMessage()));
            }
        });

        // GET /api/status — health check
        server.createContext("/api/status", exchange -> {
            if (exchange.getRequestMethod().equals("OPTIONS")) { handleOptions(exchange); return; }
            JSONObject resp = new JSONObject();
            resp.put("server", "running");
            resp.put("system_mode", SYSTEM_MODE);
            resp.put("control_mode", CONTROL_MODE == HUMAN_MODE ? "HUMAN" :
                                      CONTROL_MODE == ADAS_MODE ? "ADAS" : "AUTONOMOUS");
            resp.put("device_count", devices.size());
            resp.put("active_warnings", latestWarnings.length());
            sendJson(exchange, 200, resp);
        });

        // POST /api/simulate — inject test devices for demo
        server.createContext("/api/simulate", exchange -> {
            if (exchange.getRequestMethod().equals("OPTIONS")) { handleOptions(exchange); return; }
            try {
                devices.clear();
                Random rand = new Random();
                String[] types = {"CAR", "BIKE"};
                for (int i = 1; i <= 5; i++) {
                    JSONObject d = new JSONObject();
                    d.put("id", "device_" + i);
                    d.put("vehicle_type", types[rand.nextInt(2)]);
                    d.put("velocity", 10 + rand.nextInt(60));
                    d.put("x", rand.nextInt(200));
                    d.put("y", rand.nextInt(200));
                    d.put("lat", 12.9 + rand.nextDouble() * 0.01);
                    d.put("lng", 77.5 + rand.nextDouble() * 0.01);
                    d.put("timestamp", System.currentTimeMillis());
                    devices.put(d.getString("id"), d);
                }
                latestWarnings = detectCollision();

                JSONObject resp = new JSONObject();
                resp.put("status", "simulated");
                resp.put("devices_created", devices.size());
                resp.put("warnings", latestWarnings);
                sendJson(exchange, 200, resp);
            } catch (Exception e) {
                sendJson(exchange, 500, new JSONObject().put("error", e.getMessage()));
            }
        });

        // DELETE /api/clear — clear all devices
        server.createContext("/api/clear", exchange -> {
            if (exchange.getRequestMethod().equals("OPTIONS")) { handleOptions(exchange); return; }
            devices.clear();
            latestWarnings = new JSONObject();
            sendJson(exchange, 200, new JSONObject().put("status", "cleared"));
        });

        server.start();
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   Collision Prevention Server Started    ║");
        System.out.println("║   http://localhost:" + PORT + "                 ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║   POST /api/update    — device data     ║");
        System.out.println("║   GET  /api/devices   — all devices     ║");
        System.out.println("║   GET  /api/warnings  — collision risks ║");
        System.out.println("║   POST /api/mode      — change mode     ║");
        System.out.println("║   GET  /api/status    — server health   ║");
        System.out.println("║   POST /api/simulate  — demo data       ║");
        System.out.println("║   POST /api/clear     — reset all       ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }
}
