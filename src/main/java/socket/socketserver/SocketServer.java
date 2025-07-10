package socket.socketserver;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class SocketServer {

    private static final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
    private static final File AUTH_FILE = new File("auth.json");
    private static final File ADMIN_FILE = new File("admin.json");
    private static final File CHAT_LOG = new File("current_chat.log");

    private static final String BUILT_IN_ADMIN_ID = "admin";
    private static final String BUILT_IN_ADMIN_PASS = "admin123";

    private static ClientHandler adminHandler = null;
    private static ClientHandler currentClientHandler = null;

    
    
    public static void main(String[] args) throws IOException {
    if (!AUTH_FILE.exists()) rotateCredentials();
    if (!ADMIN_FILE.exists()) saveAdminCredentials(BUILT_IN_ADMIN_ID, BUILT_IN_ADMIN_PASS);

    // Start chat server
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    ServerSocket serverSocket = new ServerSocket(port);
    System.out.println("Server started on port " + port);

    // Start voice relay server
    VoiceServer.start();

    while (true) {
        Socket socket = serverSocket.accept();
        ClientHandler handler = new ClientHandler(socket);
        new Thread(handler).start();
    }
}


    private static int getPort() {
        String portEnv = System.getenv("PORT");
        return portEnv != null ? Integer.parseInt(portEnv) : 12345;
    }

    public static synchronized void rotateCredentials() {
        try {
            if (CHAT_LOG.exists()) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                File archiveDir = new File("archive");
                if (!archiveDir.exists()) archiveDir.mkdir();
                File archived = new File(archiveDir, "messages_" + timestamp + ".log");
                CHAT_LOG.renameTo(archived);
                CHAT_LOG.createNewFile();
            }

            String userId = UUID.randomUUID().toString().substring(0, 8);
            String password = UUID.randomUUID().toString().substring(0, 8);
            String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());

            JSONObject obj = new JSONObject();
            obj.put("userId", userId);
            obj.put("passwordHash", hash);
            obj.put("created", new Date().toString());

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(AUTH_FILE))) {
                writer.write(obj.toString(2));
            }

            if (adminHandler != null) {
                adminHandler.sendMessage("🆕 New Client Credentials:\nUser ID: " + userId + "\nPassword: " + password);
            }

            if (currentClientHandler != null) {
                currentClientHandler.disconnect();
                currentClientHandler = null;
            }

        } catch (Exception e) {
            System.err.println("Error rotating credentials: " + e.getMessage());
        }
    }

    private static JSONObject readAuthFile() {
        try (BufferedReader reader = new BufferedReader(new FileReader(AUTH_FILE))) {
            return new JSONObject(reader.readLine());
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject readAdminFile() {
        try (BufferedReader reader = new BufferedReader(new FileReader(ADMIN_FILE))) {
            return new JSONObject(reader.readLine());
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveAdminCredentials(String id, String password) {
        String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
        JSONObject obj = new JSONObject();
        obj.put("adminId", id);
        obj.put("passwordHash", hash);
        obj.put("created", new Date().toString());
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(ADMIN_FILE))) {
            writer.write(obj.toString(2));
        } catch (IOException e) {
            System.err.println("Error saving admin credentials.");
        }
    }

    static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private boolean isAdmin = false;
        private String userId;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                out.println("Enter User ID:");
                userId = in.readLine();
                out.println("Enter Password:");
                String password = in.readLine();

                JSONObject auth = readAuthFile();
                JSONObject admin = readAdminFile();

                boolean builtInMatch = BUILT_IN_ADMIN_ID.equals(userId) && BUILT_IN_ADMIN_PASS.equals(password);
                boolean dynamicAdminMatch = admin != null && admin.getString("adminId").equals(userId) &&
                        BCrypt.verifyer().verify(password.toCharArray(), admin.getString("passwordHash")).verified;

                if (builtInMatch || dynamicAdminMatch) {
                    isAdmin = true;
                    adminHandler = this;
                    out.println("✅ Authentication successful (admin).");
                } else if (auth != null && auth.getString("userId").equals(userId)) {
                    if (BCrypt.verifyer().verify(password.toCharArray(), auth.getString("passwordHash")).verified) {
                        synchronized (SocketServer.class) {
                            if (currentClientHandler != null) {
                                out.println("❌ Only one client allowed at a time.");
                                socket.close();
                                return;
                            } else {
                                currentClientHandler = this;
                                out.println("✅ Authentication successful.");
                            }
                        }
                    } else {
                        out.println("❌ Authentication failed.");
                        socket.close();
                        return;
                    }
                } else {
                    out.println("❌ Authentication failed.");
                    socket.close();
                    return;
                }

                clients.add(this);

                String message;
                while ((message = in.readLine()) != null) {
                    if (isAdmin && message.equalsIgnoreCase("/adduser")) {
                        rotateCredentials();
                        continue;
                    }

                    if (isAdmin && message.equalsIgnoreCase("/archives")) {
                        File[] files = new File("archive").listFiles((dir, name) -> name.endsWith(".log"));
                        if (files != null && files.length > 0) {
                            out.println("📂 Available Archives:");
                            for (File f : files) out.println("- " + f.getName());
                        } else {
                            out.println("⚠️ No archives found.");
                        }
                        continue;
                    }

                    if (isAdmin && message.startsWith("/load ")) {
                        String archiveName = message.substring(6).trim();
                        File archiveFile = new File("archive", archiveName);
                        if (archiveFile.exists()) {
                            out.println("📜 Loading archive...");
                            sendArchive(archiveFile);

                            String userIdFromArchive = archiveName.contains("_") ? archiveName.split("_")[0] : "revived";
                            String newPassword = UUID.randomUUID().toString().substring(0, 8);
                            String hash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray());

                            JSONObject obj = new JSONObject();
                            obj.put("userId", userIdFromArchive);
                            obj.put("passwordHash", hash);
                            obj.put("revived", new Date().toString());

                            try (BufferedWriter writer = new BufferedWriter(new FileWriter(AUTH_FILE))) {
                                writer.write(obj.toString(2));
                            }

                            sendMessage("🔄 Revived credentials:\nUser ID: " + userIdFromArchive + "\nPassword: " + newPassword);
                        } else {
                            out.println("❌ Archive not found.");
                        }
                        continue;
                    }

                    logMessage(userId, message);
                    broadcast(userId + ": " + message);
                }
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
            } finally {
                try {
                    clients.remove(this);
                    socket.close();
                    if (this == adminHandler) adminHandler = null;
                    if (this == currentClientHandler) currentClientHandler = null;
                } catch (IOException e) {
                    System.err.println("Error closing socket: " + e.getMessage());
                }
            }
        }

        private void sendArchive(File file) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) out.println(line);
            } catch (IOException e) {
                out.println("❌ Failed to load archive.");
            }
        }

        private void logMessage(String userId, String message) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(CHAT_LOG, true))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                writer.write("[" + timestamp + "] " + userId + ": " + message);
                writer.newLine();
            } catch (IOException e) {
                System.err.println("Error logging message: " + e.getMessage());
            }
        }

        private void broadcast(String message) {
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    if (client != this) {
                        client.out.println(message);
                    }
                }
            }
        }

        public void sendMessage(String message) {
            out.println(message);
        }

        public void disconnect() {
            try {
                out.println("🔒 Your session has been closed.");
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
