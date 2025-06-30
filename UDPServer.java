import java.io.*; 
import java.net.*; 
import java.util.*; 
import java.util.concurrent.*; 
import java.util.Base64; 

// Server: Handles multiple clients using threads
public class UDPServer {
    private static final int MIN_PORT = 50000;
    private static final int MAX_PORT = 51000;
    private static final int MAX_RETRIES = 5;
    private static final int BASE_TIMEOUT = 500;
    private static final int MAX_BLOCK_SIZE = 1000;
    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: java UDPServer <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        try (DatagramSocket mainSocket = new DatagramSocket(port)) {
            System.out.println("Server started on port " + port);
            byte[] buffer = new byte[1024];
            
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                mainSocket.receive(packet);
                threadPool.execute(new FileHandler(mainSocket, packet));
            }
        }
    }
   // File transfer handler (per client)
    static class FileHandler implements Runnable {
        private final DatagramSocket mainSocket;
        private final DatagramPacket initPacket;

        public FileHandler(DatagramSocket mainSocket, DatagramPacket packet) {
            this.mainSocket = mainSocket;
            this.initPacket = packet;
        }
        
        @Override
        public void run() {
            try {
                // Parse DOWNLOAD request
                String request = new String(initPacket.getData(), 0, initPacket.getLength());
                if (!request.startsWith("DOWNLOAD ")) return;
                
                String filename = request.substring(9).trim();
                File file = new File(filename);
                
                // Check if file exists
                if (!file.exists()) {
                    sendResponse("ERR " + filename + " NOT_FOUND");
                    return;
                }
                
                // Select random port for file transfer
                int filePort = selectAvailablePort();
                if (filePort == -1) {
                    sendResponse("ERR " + filename + " NO_PORT_AVAILABLE");
                    return;
                }
                
                // Send OK response with file info
                long fileSize = file.length();
                String response = "OK " + filename + " SIZE " + fileSize + " PORT " + filePort;
                sendResponse(response);
                
                // Start file transfer on new port
                startFileTransfer(file, filePort);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void sendResponse(String message) throws IOException {
            byte[] data = message.getBytes();
            DatagramPacket packet = new DatagramPacket(
                data, data.length, 
                initPacket.getAddress(), initPacket.getPort()
            );
            mainSocket.send(packet);
        }

        private int selectAvailablePort() {
            Random rand = new Random();
            for (int i = 0; i < 10; i++) {
                int port = MIN_PORT + rand.nextInt(MAX_PORT - MIN_PORT);
                try (DatagramSocket test = new DatagramSocket(port)) {
                    return port;
                } catch (IOException ignored) {}
            }
            return -1;
        }

        private void startFileTransfer(File file, int port) throws IOException {
            try (DatagramSocket fileSocket = new DatagramSocket(port);
                 FileInputStream fis = new FileInputStream(file)) {
                System.out.println("File transfer started on port " + port);
                
                byte[] buffer = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    fileSocket.receive(packet);
                    
                    String request = new String(packet.getData(), 0, packet.getLength());
                    String[] parts = request.split(" ");
                    
                    // Handle CLOSE request
                    if (request.startsWith("FILE ") && parts.length > 2 && "CLOSE".equals(parts[2])) {
                        sendFileResponse(fileSocket, packet, "FILE " + parts[1] + " CLOSE_OK");
                        break;
                    }
                    
                    // Handle data reques
                    if (request.startsWith("FILE ") && "GET".equals(parts[2])) {
                        long start = Long.parseLong(parts[4]);
                        long end = Long.parseLong(parts[6]);
                        int blockSize = (int) (end - start + 1);
                        
                        byte[] block = new byte[blockSize];
                        fis.getChannel().position(start);
                        int read = fis.read(block);
                        
                        if (read > 0) {
                            String encoded = Base64.getEncoder().encodeToString(
                                Arrays.copyOf(block, read)
                            );
                            String response = "FILE " + parts[1] + " OK START " + 
                                start + " END " + (start + read - 1) + " DATA " + encoded;
                            sendFileResponse(fileSocket, packet, response);
                        }
                    }
                }
            }
        }

        private void sendFileResponse(DatagramSocket socket, DatagramPacket request, String message) 
            throws IOException {
            byte[] data = message.getBytes();
            DatagramPacket response = new DatagramPacket(
                data, data.length, 
                request.getAddress(), request.getPort()
            );
            socket.send(response);
        }
    }
}

// Client: Downloads files sequentially
public class UDPClient {
    private static final int MAX_RETRIES = 5;
    private static final int BASE_TIMEOUT = 500;

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("Usage: java UDPClient <host> <port> <filelist>");
            return;
        }
        
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        List<String> files = Files.readAllLines(Paths.get(args[2]));
        
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName(host);
            
            for (String filename : files) {
                downloadFile(socket, address, port, filename.trim());
            }
        }
    }

    private static void downloadFile(DatagramSocket socket, InetAddress address, 
                                   int port, String filename) throws IOException {
        // Step 1: Send DOWNLOAD request
        String request = "DOWNLOAD " + filename;
        String response = sendWithRetry(socket, address, port, request, "OK", "ERR");
        
        if (response.startsWith("ERR")) {
            System.out.println("Error: " + response);
            return;
        }
        
        // Parse server response
        String[] parts = response.split(" ");
        long fileSize = Long.parseLong(parts[4]);
        int filePort = Integer.parseInt(parts[6]);
        
        System.out.print("Downloading " + filename + " (" + fileSize + " bytes): ");
        
        // Step 2: Download file content
        try (FileOutputStream fos = new FileOutputStream(filename)) {
            long bytesReceived = 0;
            while (bytesReceived < fileSize) {
                long end = Math.min(bytesReceived + UDPServer.MAX_BLOCK_SIZE - 1, fileSize - 1);
                String dataReq = "FILE " + filename + " GET START " + bytesReceived + " END " + end;
                
                String dataRes = sendWithRetry(socket, address, filePort, dataReq, "FILE " + filename + " OK", null);
                
                // Extract and decode data
                String[] resParts = dataRes.split(" DATA ", 2);
                byte[] block = Base64.getDecoder().decode(resParts[1]);
                fos.write(block);
                bytesReceived += block.length;
                
                // Show progress
                System.out.print("*");
            }
            System.out.println("\nDownload complete: " + filename);
            
            // Step 3: Send CLOSE
            String closeReq = "FILE " + filename + " CLOSE";
            sendWithRetry(socket, address, filePort, closeReq, "FILE " + filename + " CLOSE_OK", null);
        }
    }
    // Reliable send with retry mechanism
    private static String sendWithRetry(DatagramSocket socket, InetAddress address, 
                                      int port, String request, String successPrefix, String errorPrefix) 
        throws IOException {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(
            request.getBytes(), request.getBytes().length, 
            address, port
        );
        
        int timeout = BASE_TIMEOUT;
        for (int i = 0; i <= MAX_RETRIES; i++) {
            try {
                socket.send(packet);
                socket.setSoTimeout(timeout);
                
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                
                String res = new String(response.getData(), 0, response.getLength());
                if ((successPrefix != null && res.startsWith(successPrefix)) ||
                    (errorPrefix != null && res.startsWith(errorPrefix))) {
                    return res;
                }
            } catch (SocketTimeoutException e) {
                timeout *= 2;  // Exponential backoff
                System.out.println("Timeout, retrying: " + request);
            }
        }
        throw new IOException("Max retries exceeded for: " + request);
    }
}
