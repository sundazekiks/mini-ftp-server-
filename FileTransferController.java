
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class FileTransferController {


    final BufferedWriter out;
    ServerSocket pasv;

    public FileTransferController(BufferedWriter o) {
        this.out = o;
    }

    
    static final Path ROOT = Paths.get("root_share").toAbsolutePath().normalize();

    static final COMMAND[] cmds = { new COMMAND("FILE", "Find a file"),
        new COMMAND("LIST", "Lists all files inside server")
    };

    static class COMMAND {
        String name;
        String description;

        public COMMAND(String _name, String desc) {
            name = _name;
            description = desc;
        }

        @Override 
        public String toString(){
            return name + " - " + description;
        }
        
    }

    void reply(String msg) throws IOException {
            out.write(msg + "\r\n");
            out.flush();
        };
    
    Path resolve(String arg) {
            Path p = arg.startsWith("/")
                    ? ROOT.resolve(arg.substring(1))
                    : ROOT.resolve(arg);
            p = p.normalize();
            return p.startsWith(ROOT) ? p : null;
        };

    Socket openData() throws IOException {
            if (pasv == null) { reply("425 Use PASV first"); return null; }
            reply("150 Opening data connection");
            try (ServerSocket ss = pasv) {
                pasv = null;
                return ss.accept();
            }
        }   
    
    private void download(String args) throws IOException {
            if (args == null || args.isBlank()) {
                reply("501 Usage: DWNLD <file>");
                return;
            }

            // Validate BEFORE openData(): it sends the 150 and blocks on accept()
            Path file = resolve(args.trim());
            if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
                reply("550 File not found or not readable");
                return;
            }

            Socket d = openData();                  // sends 150, or 425 if no PASV
            if (d == null) return;

            try (d; InputStream src = Files.newInputStream(file)) {
                src.transferTo(d.getOutputStream());
            } catch (IOException e) {
                reply("426 Transfer aborted: " + e.getMessage());
                return;
            }
            reply("226 Transfer complete");
        }
    
    public void InputHandler(String CMD, String ARGS, Socket ctrl) throws IOException {
        switch(CMD) {
            case "LIST" -> { 
                Socket d = openData();
                if (d == null) break;
                try (d;  Stream<Path> files = Files.list(ROOT);
                        Writer w = new OutputStreamWriter(d.getOutputStream(), StandardCharsets.UTF_8)) {
                            for (Path f: (Iterable<Path>) files::iterator) {
                                w.write(Files.isDirectory(f) ? "/" + f.getFileName() + "\n" : "-" + f.getFileName() + "\n");
                            
                            }
                }
               
                
            }
            case "CMDS" -> {
                for (COMMAND cmd : cmds) {
                    out.write(cmd.toString());
                    out.newLine();
                }
                out.flush();
             
            }
            case "PASV" -> {
                if (pasv != null) pasv.close();
                pasv = new ServerSocket(0);
                int port = pasv.getLocalPort();
                System.out.println(port);
                String ip = ctrl.getLocalAddress().getHostAddress().replace(".", ",");
    
                reply("227 Entering Passive Mode (" + ip + "," + (port >> 8) + "," + (port & 255) + ")");
                
            }
            case "STOR" -> {
                        if (ARGS == null || ARGS.isBlank()) { reply("501 Usage: STOR <file>"); break; }
                        Path p = resolve(ARGS.trim());
                        if (p == null || Files.isDirectory(p)) { reply("550 Not allowed"); break; }

                        Socket d = openData();
                        if (d == null) break;

                        try (d; OutputStream fo = Files.newOutputStream(p)) {
                            d.getInputStream().transferTo(fo);
                        } catch (IOException e) {
                            reply("426 Transfer aborted: " + e.getMessage());
                            break;
                        }
                        reply("226 Transfer complete");
                    }
            case "DWNLD" -> download(ARGS);
            default -> { 
                out.write("ERROR: Please enter a valid command");
                out.newLine();
                out.flush();
            }
        }

    }
}
