// Run:  java Server.java     Then open http://localhost:5000
// DB PERSON and BACKEND PERSON: look for your name in the comments below. They only say what to add and where.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import java.util.stream.Stream;
// DB PERSON: add  import java.sql.*;  here when you add the database code.



public class Server {
    static Path UPLOADS = Paths.get(System.getenv().getOrDefault("BDO_UPLOADS",
            "C:/BDO-Project_Team_Soft_ware_2026/Final_format_BDO/BackEnd/Uploads"));
    static Path WEBSITE = Paths.get(System.getenv().getOrDefault("BDO_WEBSITE",
            "C:/BDO-Project_Team_Soft_ware_2026/Final_format_BDO")).toAbsolutePath().normalize();



    // DB PERSON: add the database connection string here (for example a SQLite file or your DBeaver database).
    //            Add the SQL text for your queries here too, so they are easy to find and change.



    public static void main(String[] args) throws Exception {
        Files.createDirectories(UPLOADS);


        // DB PERSON: create the table(s) here (CREATE TABLE IF NOT EXISTS ...).
        //            Choose the column names and tell the BACKEND PERSON what they are.


        HttpServer s = HttpServer.create(new InetSocketAddress(5000), 0);
        s.createContext("/", Server::site);
        s.createContext("/api/upload", Server::upload);
        s.createContext("/api/files", Server::files);



        // DB PERSON: replace "[]" in the next two lines with the result of your SELECT, returned as JSON.
        //   /api/summary -> feeds summary.html. Needs: a JSON array of objects, e.g. [{"item":"Total","value":"120"}]
        //   /api/graph   -> feeds graph.html.   Same format.
        //   The key names in the JSON become the table headers on the page.


        s.createContext("/api/summary", x -> json(x, 200, "[]"));
        s.createContext("/api/graph", x -> json(x, 200, "[]"));
        s.start();
        System.out.println("Open http://localhost:5000   (uploads go to " + UPLOADS + ")");
    }




    // POST /api/upload
    static void upload(HttpExchange x) throws IOException {
        String type = x.getRequestHeaders().getFirst("Content-Type");
        if (!x.getRequestMethod().equals("POST") || type == null || !type.contains("boundary=")) {
            json(x, 400, "{\"status\":\"error\"}");
            return;
        }
        String boundary = "--" + type.substring(type.indexOf("boundary=") + 9).replace("\"", "");
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        int f = body.indexOf("filename=\"");
        if (f < 0) { json(x, 400, "{\"status\":\"error\"}"); return; }
        String name = body.substring(f + 10, body.indexOf('"', f + 10));
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
                   .replaceAll("[^A-Za-z0-9._-]", "_");
        int start = body.indexOf("\r\n\r\n", f) + 4;
        int end = body.indexOf("\r\n" + boundary, start);
        String lower = name.toLowerCase();
        if (name.isEmpty() || end < start || !(lower.endsWith(".pdf") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            json(x, 400, "{\"status\":\"error\"}");
            return;
        }
        Files.write(UPLOADS.resolve(name), body.substring(start, end).getBytes(StandardCharsets.ISO_8859_1));
        System.out.println("Saved: " + UPLOADS.resolve(name));
           


        // BACKEND PERSON: call your method here, right after the file is saved, for example
        //                 process(UPLOADS.resolve(name));   (wrap it in try/catch so a failure does not break the upload)




        json(x, 200, "{\"status\":\"ok\"}");
    }





    // BACKEND PERSON: add  static void process(Path file) throws Exception  here.
    //   Job: open the uploaded file from the Uploads folder and read it.
    //        PDF     -> extract the text (for example with a PDF library such as PDFBox).
    //        PNG/JPG -> read the text with OCR (for example with Tess4J).
    //   Then pull out the values we need and give each one to the DB PERSON's save method.



    
    // DB PERSON: add  static void save(String file, String item, String value)  here.
    //   Job: insert ONE row into the table. The BACKEND PERSON calls it from process().
    //   Agree with the BACKEND PERSON on what file, item and value mean.

    // DB PERSON: add  static String query(String sql)  here.
    //   Job: run a SELECT and return the rows as a JSON array of objects (see /api/summary above).
    //   Escape quotes and line breaks in the values so the JSON stays valid.




    // GET /api/files
    static void files(HttpExchange x) throws IOException {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        try (Stream<Path> list = Files.list(UPLOADS)) {
            String rows = list.filter(Files::isRegularFile).sorted().map(p -> {
                try {
                    return "{\"file\":\"" + p.getFileName().toString().replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\",\"uploaded\":\"" + fmt.format(Files.getLastModifiedTime(p).toInstant()) + "\"}";
                } catch (IOException e) { return "{}"; }
            }).collect(Collectors.joining(","));
            json(x, 200, "[" + rows + "]");
        }
    }



    // Shows index.html, graph.html, summary.html, css, js, images, video (BackEnd folder stays private)
    static void site(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = WEBSITE.resolve(path.substring(1)).normalize();
        boolean ok = file.startsWith(WEBSITE) && Files.isRegularFile(file)
                && !WEBSITE.relativize(file).getName(0).toString().equalsIgnoreCase("BackEnd");
        if (!ok) {
            byte[] msg = "Not found".getBytes();
            x.sendResponseHeaders(404, msg.length);
            x.getResponseBody().write(msg);
            x.close();
            return;
        }
        String n = file.getFileName().toString().toLowerCase();
        String t = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css"
                : n.endsWith(".js") ? "application/javascript" : n.endsWith(".webp") ? "image/webp"
                : n.endsWith(".png") ? "image/png" : n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg"
                : n.endsWith(".mp4") ? "video/mp4" : n.endsWith(".webm") ? "video/webm"
                : "application/octet-stream";
        byte[] data = Files.readAllBytes(file);
        x.getResponseHeaders().set("Content-Type", t);
        x.sendResponseHeaders(200, data.length);
        x.getResponseBody().write(data);
        x.close();
    }




    
    static void json(HttpExchange x, int code, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        x.sendResponseHeaders(code, data.length);
        x.getResponseBody().write(data);
        x.close();
    }
}