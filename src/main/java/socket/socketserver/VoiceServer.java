package socket.socketserver;

import java.io.*;
import java.net.*;
import java.util.*;

public class VoiceServer {

    private static final List<Socket> clients = Collections.synchronizedList(new ArrayList<>());

    public static void handleClient(Socket socket) {
        try {
            clients.add(socket);
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                broadcast(buffer, bytesRead, socket);
            }
        } catch (IOException e) {
            System.err.println("Voice client disconnected: " + e.getMessage());
        } finally {
            clients.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void broadcast(byte[] data, int length, Socket sender) {
        synchronized (clients) {
            for (Socket client : clients) {
                if (client != sender) {
                    try {
                        OutputStream out = client.getOutputStream();
                        out.write(data, 0, length);
                        out.flush();
                    } catch (IOException e) {
                        System.err.println("Failed to send voice data: " + e.getMessage());
                    }
                }
            }
        }
    }
}
