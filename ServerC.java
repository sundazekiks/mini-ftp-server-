import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class ServerC implements Closeable {

    static final String PROMPT = "CMD: ";
    static final String WELCOME_END = "continue: ";
    static final int TIMEOUT_MS = 15_000;

    private final String host;
    private final Socket ctrl;
    private final BufferedReader in;
    private final BufferedWriter out;

    ServerC(String host, int port) throws IOException {
        this.host = host;
        ctrl = new Socket();
        ctrl.connect(new InetSocketAddress(host, port), 5_000);
        ctrl.setSoTimeout(TIMEOUT_MS);
        in = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(ctrl.getOutputStream(), UTF_8));
    }

    // ---------- low-level control-connection helpers ----------

    /** The server's prompts have no trailing newline, so read char by char until we see the suffix. */
    String readUntil(String suffix) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            int start = sb.length() - suffix.length();
            if (start >= 0 && sb.indexOf(suffix, start) == start) {
                sb.setLength(start);
                return sb.toString();
            }
        }
        throw new EOFException("Server closed the connection");
    }

    String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) throw new EOFException("Server closed the connection");
        return sb.toString();
    }

    void send(String line) throws IOException {
        out.write(line + "\n");
        out.flush();
    }

    // ---------- protocol commands ----------

    String greeting() throws IOException {
        return readUntil(WELCOME_END) + WELCOME_END;
    }

    void cmds() throws IOException {
        send("CMDS");
        System.out.print(readUntil(PROMPT));
    }

    /** Asks the server to open a passive port and returns that port. */
    int pasv() throws IOException {
        send("PASV");
        String line = readLine();   // 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
        readUntil(PROMPT);

        int open = line.indexOf('(');
        int close = line.indexOf(')');
        if (!line.startsWith("227") || open < 0 || close < open) {
            throw new IOException("Unexpected PASV reply: " + line);
        }
        String[] p = line.substring(open + 1, close).split(",");
        
        return Integer.parseInt(p[4].trim()) * 256 + Integer.parseInt(p[5].trim());
    }

    Socket openData(int port) throws IOException {
        Socket d = new Socket();
        d.connect(new InetSocketAddress(host, port), 5_000);
        d.setSoTimeout(TIMEOUT_MS);
        return d;
    }

    void list() throws IOException {
        int port = pasv();
        send("LIST");
        String reply = readLine();
        if (!reply.startsWith("150")) {           // e.g. "425 Use PASV first"
            System.out.println(reply);
            readUntil(PROMPT);
            return;
        }
        try (Socket d = openData(port);
             BufferedReader r = new BufferedReader(new InputStreamReader(d.getInputStream(), UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);         // "/dir" or "-file"
            }
        }
        readUntil(PROMPT);
    }

    void store(Path local, String remoteName) throws IOException {
        if (!Files.isRegularFile(local)) {
            System.out.println("Local file not found: " + local);
            return;
        }
        int port = pasv();
        send("STOR " + remoteName);
        String reply = readLine();
        if (!reply.startsWith("150")) {
            System.out.println(reply);
            readUntil(PROMPT);
            return;
        }
        try (Socket d = openData(port); InputStream file = Files.newInputStream(local)) {
            file.transferTo(d.getOutputStream());
            d.shutdownOutput();                  
        }
        System.out.println(readLine());         
        readUntil(PROMPT);
    }

    void download(String remoteName, Path local) throws IOException {
            if (Files.exists(local)) {
                System.out.println("Local file already exists: " + local);
                return;
            }
            Path part = local.resolveSibling(local.getFileName() + ".part");

            int port = pasv();
            send("DWNLD " + remoteName);
            String reply = readLine();
            if (!reply.startsWith("150")) {          
                System.out.println(reply);
                readUntil(PROMPT);
                return;
            }

            try (Socket d = openData(port);
                InputStream data = d.getInputStream()) {
                Files.copy(data, part, StandardCopyOption.REPLACE_EXISTING);  
            } catch (IOException e) {
                Files.deleteIfExists(part);
                throw e;
            }

            String done = readLine();                
            if (done.startsWith("226")) {
                Files.move(part, local);
                System.out.println(done + " -> " + local + " (" + Files.size(local) + " bytes)");
            } else {
                Files.deleteIfExists(part);
                System.out.println(done);
            }
            readUntil(PROMPT);
        }

    @Override
    public void close() throws IOException {
        ctrl.close();
    }

    // ---------- interactive shell ----------

    static void help() {
        System.out.println("Commands:");
        System.out.println("  list                       list files on the server");
        System.out.println("  cmds                       show commands the server supports");
        System.out.println("  stor <local> [remoteName]  upload a file (no spaces in names)");
        System.out.println("  help                       show this help");
        System.out.println("  quit                       disconnect");
        System.out.println("  dwnld <remote> [local]     download a file from the server");
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "192.168.8.106";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;

        try (ServerC client = new ServerC(host, port);
             BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {

            System.out.println(client.greeting());
            System.out.println("(No need to type 'Start' with this client.)");
            help();

            String line;
            while (true) {
                System.out.print("> ");
                if ((line = console.readLine()) == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                switch (parts[0].toLowerCase()) {
                    case "list" -> client.list();
                    case "cmds" -> client.cmds();
                    case "stor" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: stor <local> [remoteName]");
                            break;
                        }
                        Path local = Paths.get(parts[1]);
                        String remote = parts.length > 2 ? parts[2] : local.getFileName().toString();
                        client.store(local, remote);
                    }
                    case "help" -> help();
                    case "dwnld" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: dwnld <remote> [local]");
                            break;
                        }
                        String remote = parts[1];
                        Path local = parts.length > 2
                                ? Paths.get(parts[2])
                                : Paths.get(remote).getFileName();
                        client.download(remote, local);
                    }
                    case "quit", "exit" -> {
                        System.out.println("Bye.");
                        return;
                    }
                    default -> System.out.println("Unknown command. Type 'help'.");
                }
            }
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        }
    }
}