import java.io.*; 
import java.net.*; 
import java.util.*; 
import java.util.concurrent.*; 
import java.util.Base64; 

public class UDPServer {
    private static final int MIN_PORT = 50000;
    private static final int MAX_PORT = 51000;
    private static final int MAX_RETRIES = 5;
    private static final int BLOCK_SIZE = 1000;

    private DatagramSocket serverSocket;
    private ExecutorService threadPool;
    private Set <Integer> usedPorts = ConcurrentHashMap.newKeySet();

    public UDPServer(int port) throws SocketException {
        serverSocket = new DatagramSocket(port);
        threadPool = Executors.newCachedThreadPool();
        System.out.println("Server started on port " + port);
    }
punlic void start() {
    while (true) {
        try {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            serverSocket.receive(packet);

            String request = new String(packet.getData(), 0, packet.getLength());
            if (request.startsWith("DOWNLOAD")) {
                threadPool.execute(new ClientHandler(request, packet.getAddress(), packet.getPort()));
            }
        } catch (IOException e) {
            System.err.println ("Server error: " + e.getMessage());
        }
    }
}


