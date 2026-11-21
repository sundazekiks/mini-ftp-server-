
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;


public class Server {
    

    public static void main(String[] args) throws  Exception{
        try (ServerSocket server = new ServerSocket(9090)) {
            System.out.println("Server running on PORT: " + server.getLocalPort());
            while (true) {
                Socket socket = server.accept();
                new Thread(new Handler(socket)).start();
            }
        }
    }

     

    static class Handler implements  Runnable{
        final Socket ctrl;
        BufferedReader in; 
        BufferedWriter out;

      


        Handler(Socket s) { ctrl = s;}

        @Override 
        public void run(){
            try (ctrl) {
            System.out.println(ctrl.getLocalAddress().getHostAddress());
            in = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8));

            out.write("Welcome to the File Server, type 'Start' to continue: ");
            out.flush();
            String line;
            FileTransferController ctrlr = new FileTransferController(out);


            while ((line = in.readLine()) != null) {
                System.out.println(line);
                String[] raw_cmd = line.split(" ");
                String cmd = raw_cmd[0];
                String arg = raw_cmd.length > 1 ? raw_cmd[1] : "";
                
                ctrlr.InputHandler(cmd, arg, ctrl);

                out.write("CMD: ");
                out.flush();
            }
            }catch (IOException e) {
                System.out.println("Client error: " + e.getMessage());
            }
        }
    }
    
}