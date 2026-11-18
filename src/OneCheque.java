import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public final class OneCheque {
    private final Path data;
    private final Map<UUID, Table> tables = new HashMap<>();

    private record Order(UUID id, String name, int price) implements Serializable {}
    private record Diner(UUID id, String name, List<Order> orders) implements Serializable {
        Diner withOrders(List<Order> orders) {
            return new Diner(id, name, List.copyOf(orders));
        }
    }
    private record Table(UUID id, UUID host, int service, boolean closed, long revision, List<Diner> diners)
            implements Serializable {
        Table withDiners(List<Diner> diners) {
            return new Table(id, host, service, closed, revision + 1, List.copyOf(diners));
        }
    }
    private record Share(int index, long pennies, long remainder) {}
    private static final class Problem extends RuntimeException {
        final int status;
        Problem(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private OneCheque(Path data) throws Exception {
        this.data = data;
        Files.createDirectories(data);
        try (var files = Files.newDirectoryStream(data, "*.table")) {
            for (Path file : files) {
                try (var input = new ObjectInputStream(Files.newInputStream(file))) {
                    var table = (Table) input.readObject();
                    tables.put(table.id(), table);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var app = new OneCheque(Path.of(System.getenv().getOrDefault("ONE_CHEQUE_DATA", "data")));
        int port = Integer.parseInt(System.getenv().getOrDefault("ONE_CHEQUE_PORT", "8080"));
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("One Cheque is listening on port " + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                var path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/")) {
                    Map<String, String> fields = exchange.getRequestMethod().equals("POST")
                            ? readForm(exchange) : Map.of();
                    send(exchange, 200, "application/json", api(exchange, path, fields));
                } else {
                    servePage(exchange, path);
                }
            } catch (Problem problem) {
                send(exchange, problem.status, "application/json", "{\"error\":" + quote(problem.getMessage()) + "}");
            } catch (IllegalArgumentException error) {
                send(exchange, 400, "application/json", "{\"error\":\"Invalid request.\"}");
            } catch (IOException error) {
                error.printStackTrace();
                send(exchange, 500, "application/json", "{\"error\":\"Could not save the table. Try again.\"}");
            }
        }
    }

    // Serialize mutations and persist before publishing the new table to readers.
    private synchronized String api(HttpExchange exchange, String path, Map<String, String> fields) throws IOException {
        String method = exchange.getRequestMethod();
        if (path.equals("/api/tables") && method.equals("POST")) {
            var diner = new Diner(UUID.randomUUID(), name(fields, 40), List.of());
            var table = new Table(UUID.randomUUID(), diner.id(), 0, false, 0, List.of(diner));
            save(table);
            return session(table, diner);
        }

        String[] parts = path.split("/");
        if (parts.length < 4 || parts.length > 5 || !parts[2].equals("tables")) {
            throw new Problem(404, "Page not found.");
        }
        var table = tables.get(UUID.fromString(parts[3]));
        if (table == null) throw new Problem(404, "Table not found. Check the invite link.");
        String action = parts.length == 5 ? parts[4] : "";
        if (action.equals("join") && method.equals("POST")) {
            requireOpen(table);
            if (table.diners().size() >= 30) throw new Problem(409, "This table is full.");
            var diner = new Diner(UUID.randomUUID(), name(fields, 40), List.of());
            var diners = new ArrayList<>(table.diners());
            diners.add(diner);
            table = table.withDiners(diners);
            save(table);
            return session(table, diner);
        }

        String token = exchange.getRequestHeaders().getFirst("Authorization");
        var diner = table.diners().stream()
                .filter(person -> ("Bearer " + person.id()).equals(token))
                .findFirst().orElseThrow(() -> new Problem(401, "Join the table first."));
        if (action.isEmpty() && method.equals("GET")) return snapshot(table, diner);
        if (!method.equals("POST")) throw new Problem(405, "Method not allowed.");
        requireOpen(table);
        switch (action) {
            case "add", "edit", "remove" -> {
                var diners = new ArrayList<>(table.diners());
                diners.set(diners.indexOf(diner), changeOrders(diner, action, fields));
                table = table.withDiners(diners);
            }
            case "service", "close" -> {
                if (!table.host().equals(diner.id())) throw new Problem(403, "Only the host can do that.");
                if (action.equals("close") && table.diners().stream().allMatch(person -> person.orders().isEmpty())) {
                    throw new Problem(409, "Add an order before finalising the bill.");
                }
                int service = action.equals("service") ? price(fields, "price") : table.service();
                // Reject closing a total that changed after the host reviewed it.
                if (action.equals("close") && !Long.toString(table.revision()).equals(fields.get("reviewed"))) {
                    throw new Problem(409, "The bill changed. Review the latest total and try again.");
                }
                table = new Table(table.id(), table.host(), service, action.equals("close"), table.revision() + 1, table.diners());
            }
            default -> throw new Problem(404, "Action not found.");
        }
        save(table);
        return snapshot(table, diner);
    }

    private static Diner changeOrders(Diner diner, String action, Map<String, String> fields) {
        var orders = new ArrayList<>(diner.orders());
        UUID id = UUID.fromString(fields.getOrDefault("id", ""));
        int index = -1;
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).id().equals(id)) index = i;
        }
        if (action.equals("add")) {
            // The browser reuses this ID when retrying an item after a network failure.
            if (index >= 0) return diner;
            if (orders.size() >= 100) throw new Problem(409, "This diner has reached the item limit.");
            orders.add(new Order(id, name(fields, 80), price(fields, "price")));
        } else {
            if (index < 0) throw new Problem(404, "That item is no longer on your order.");
            if (action.equals("remove")) orders.remove(index);
            else orders.set(index, new Order(id, name(fields, 80), price(fields, "price")));
        }
        return diner.withOrders(orders);
    }

    private static void requireOpen(Table table) {
        if (table.closed()) throw new Problem(409, "The bill has been finalised.");
    }

    private void save(Table table) throws IOException {
        Path file = data.resolve(table.id() + ".table");
        Path temporary = Files.createTempFile(data, "table-", ".tmp");
        try {
            try (var output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
                output.writeObject(table);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tables.put(table.id(), table);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String session(Table table, Diner diner) {
        return "{\"table\":" + quote(table.id().toString()) + ",\"token\":" + quote(diner.id().toString()) + "}";
    }

    private static String snapshot(Table table, Diner viewer) {
        var subtotals = table.diners().stream()
                .mapToLong(diner -> diner.orders().stream().mapToLong(Order::price).sum()).toArray();
        long subtotal = Arrays.stream(subtotals).sum();
        long denominator = subtotal == 0 ? subtotals.length : subtotal;
        long[] charges = new long[subtotals.length];
        var shares = new ArrayList<Share>();
        long left = table.service();
        for (int i = 0; i < subtotals.length; i++) {
            long numerator = subtotal == 0 ? table.service() : subtotals[i] * table.service();
            var share = new Share(i, numerator / denominator, numerator % denominator);
            shares.add(share);
            charges[i] = share.pennies();
            left -= share.pennies();
        }
        shares.sort(Comparator.comparingLong(Share::remainder).reversed().thenComparingInt(Share::index));
        for (int i = 0; i < left; i++) charges[shares.get(i).index()]++;

        var diners = new StringJoiner(",", "[", "]");
        for (int i = 0; i < subtotals.length; i++) {
            var diner = table.diners().get(i);
            var orders = new StringJoiner(",", "[", "]");
            for (Order order : diner.orders()) {
                orders.add("{\"id\":" + quote(order.id().toString()) + ",\"name\":" + quote(order.name())
                        + ",\"price\":" + order.price() + "}");
            }
            diners.add("{\"mine\":" + diner.id().equals(viewer.id()) + ",\"name\":" + quote(diner.name())
                    + ",\"subtotal\":" + subtotals[i] + ",\"charge\":" + charges[i] + ",\"orders\":" + orders + "}");
        }
        return "{\"host\":" + table.host().equals(viewer.id()) + ",\"closed\":" + table.closed()
                + ",\"revision\":" + table.revision() + ",\"service\":" + table.service() + ",\"total\":" + (subtotal + table.service())
                + ",\"diners\":" + diners + "}";
    }

    private static int price(Map<String, String> fields, String key) {
        String value = fields.getOrDefault(key, "");
        if (!value.matches("[0-9]{1,5}(\\.[0-9]{1,2})?")) {
            throw new Problem(422, "Enter a price from £0 to £99,999.99 with up to two decimal places.");
        }
        return new java.math.BigDecimal(value).movePointRight(2).intValueExact();
    }

    private static String name(Map<String, String> fields, int limit) {
        String name = fields.getOrDefault("name", "").strip();
        if (name.isEmpty() || name.length() > limit) throw new Problem(422, "Enter a name up to " + limit + " characters.");
        return name;
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";")[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
            throw new Problem(415, "Expected a form request.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new Problem(413, "Request is too large.");
        var fields = new HashMap<String, String>();
        for (String part : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) {
                fields.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static String quote(String value) {
        var result = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '"' || ch == '\\') result.append('\\').append(ch);
            else if (ch < 32) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.append('"').toString();
    }

    private static void servePage(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) throw new Problem(405, "Method not allowed.");
        String file = switch (path) {
            case "/" -> "index.html";
            case "/app.js" -> "app.js";
            case "/style.css" -> "style.css";
            default -> throw new Problem(404, "Page not found.");
        };
        String type = switch (file) {
            case "index.html" -> "text/html";
            case "style.css" -> "text/css";
            default -> "text/javascript";
        };
        send(exchange, 200, type, Files.readString(Path.of("public", file)));
    }

    private static void send(HttpExchange exchange, int status, String type, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'self'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
