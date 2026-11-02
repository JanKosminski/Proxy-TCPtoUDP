import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class Proxy {

    public static void main(String[] args) {
        int listenPort = 0;
        List<ServerNode> targets = new ArrayList<>();
        boolean running = true;

        for (int i = 0; i < args.length; ) {
            switch (args[i]) {
                case "-port":
                    listenPort = Integer.parseInt(args[i + 1]);
                    i += 2;
                    break;
                case "-server":
                    String host = args[i + 1];
                    int port = Integer.parseInt(args[i + 2]);
                    targets.add(new ServerNode(host, port));
                    i += 3;
                    break;
                default:
                    i++;
            }
        }

        if (listenPort == 0 || targets.isEmpty()) {
            System.err.println("Użycie: java ProxyNode -port <port> -server <host> <port> ...");
            System.exit(1);
        }

        log("Weryfikacja dostępności serwerów...");

        for (ServerNode t : targets) {
            if (checkProtocolTcp(t)) {
                t.setType(ServerType.TCP);
                log("Wykryto węzeł TCP: " + t.address);
            } else if (checkProtocolUdp(t)) {
                t.setType(ServerType.UDP);
                log("Wykryto węzeł UDP: " + t.address);
            } else {
                log("WARN: Serwer " + t.address + " nie odpowiada (pominięto).");
            }
        }

        log("Proxy uruchomione na porcie " + listenPort);

        //
        ExecutorService pool = Executors.newCachedThreadPool();

        try {
            // Otwarcie socketów
            ServerSocket tcpServerSocket = new ServerSocket(listenPort);
            DatagramSocket udpServerSocket = new DatagramSocket(listenPort);
            // nasłuch UDP
            pool.submit(() -> runUdpLoop(udpServerSocket, targets));
            //Ciągły nasłuch TCP
            while (running) {
                Socket clientSocket = tcpServerSocket.accept();
                pool.submit(() -> handleTcpClient(clientSocket, targets));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // TCP czyta i odpowiada
    private static void handleTcpClient(Socket client, List<ServerNode> targets) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(client.getOutputStream(), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) continue;

                if ("QUIT".equalsIgnoreCase(line)) {
                    broadcastQuit(targets);
                    out.println("Terminating Proxy");
                    break;
                }

                String response = pickCommand(line, targets);
                log(response);
                out.println(response);
            }
        } catch (IOException e) {
            log("TCP Client Error: " + e.getMessage());
        }
    }

    // UDP odbiera sobie w pętli
    private static void runUdpLoop(DatagramSocket serverSocket, List<ServerNode> targets) {
        byte[] buf = new byte[256];
        while (true) {
            try {
                Arrays.fill(buf, (byte)0);
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                serverSocket.receive(packet);
                // obcinamy bo wychodzą głupoty bez .trim()
                String request = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                // Jeśli quit
                if ("QUIT".equalsIgnoreCase(request)) {
                    // quit do calej reszty
                    broadcastQuit(targets);

                    // odpowiedz do clienta zeby tez się zamknal
                    byte[] respData = "QUIT".getBytes(StandardCharsets.UTF_8);
                    serverSocket.send(new DatagramPacket(respData, respData.length, packet.getAddress(), packet.getPort()));
                    continue;
                }

                String response = pickCommand(request, targets);

                byte[] respData = response.getBytes(StandardCharsets.UTF_8);
                serverSocket.send(new DatagramPacket(respData, respData.length, packet.getAddress(), packet.getPort()));

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //Kontroler zbiera co przyszlo
    private static String pickCommand(String commandLine, List<ServerNode> targets) {
        //do spacjach na liste
        String[] commandList = commandLine.split("\\s+");

        if (commandList.length == 0) return "NA";

        String firstPart = commandList[0].toUpperCase();
        String secondPart = commandList[1].toUpperCase();

        switch (firstPart){
            case "GET":
                switch (secondPart){
                    case "NAMES":
                        return sendGetNames(targets);
                    case "VALUES":
                        return relayCommand(targets, commandLine);
                }
            case "SET":
                return relayCommand(targets, commandLine);
        }
        // jak odpowiadamy NA, to inne Proxy nas potraktuje jako serwer
        return "NA";
    }


    // Pyta wszystkich o klucze i skleja wyniki
    private static String sendGetNames(List<ServerNode> targets) {
        Set<String> aggregatedKeys = new TreeSet<>();
        // Pętla po serwerach
        for (ServerNode node : targets) {
            String response = forwardAndPickProtocol(node, "GET NAMES");
            if (response.startsWith("OK")) {
                // Parsowanie odpowiedzi "OK <count> <k1> <k2>..."
                List<String> keys = parseKeysFromResponse(response);
                aggregatedKeys.addAll(keys);
            }
        }
        if (aggregatedKeys.isEmpty()) {
            return "NA";
        }else {
            return "OK " + aggregatedKeys.size() + " " + String.join(" ", aggregatedKeys);
        }
    }

    // Pyta serwery po kolei, zwraca pierwszy sukces ("OK") - GET VAL i SET VAL
    private static String relayCommand(List<ServerNode> targets, String command) {
        for (ServerNode t : targets) {
            String response = forwardAndPickProtocol(t, command);
            if (response.startsWith("OK")) {
                return response;
            }
        }
        // Przeszliśmy wszystkie serwery i nikt nie miał tego klucza
        return "NA";
    }

    // wybiera jaki protokol dać
    private static String forwardAndPickProtocol(ServerNode target, String msg) {
        // Sprawdza co zapamietalismy w ServerNode i wybiera
        if (target.getType() == ServerType.TCP) {
            log("Przekazano zapytanie: " + msg + " do servera: " + target);
            return sendTcp(target, msg);
        } else {
            log("Przekazano zapytanie: " + msg + " do servera: " + target);
            return sendUdp(target, msg);
        }
    }

    // zbiera klucze z odpowiedzi
    private static List<String> parseKeysFromResponse(String response) {
        List<String> found = new ArrayList<>();
        // odpowiedz na kawalki po spacjach
        String[] tokens = response.split("\\s+");
        // token[0] - OK, token[1] to liczba kluczy, zaczynamy pętlę od token[2]

        for (int i = 2; i < tokens.length; i++) {
            found.add(tokens[i]);
        }
        return found;
    }

    // wysylanie i odpoweidz w protokole TCP
    private static String sendTcp(ServerNode target, String msg) {
        try {
            Socket socket = new Socket(target.host, target.port);
            // zrywa połączenie po 2000ms jak się utka coś np. przy QUIT
            socket.setSoTimeout(2000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // doklejenie końcówki, .trim() na wszelki wypadek
            out.println(msg.trim().concat("\r\n"));

            String resp = in.readLine();
            return (resp != null) ? resp : "NA";
        } catch (IOException e) {
            return "NA"; // Błąd traktujemy jak brak danych
        }
    }

    private static String sendUdp(ServerNode target, String msg) {
        try {
            DatagramSocket socketUdp = new DatagramSocket();
            socketUdp.setSoTimeout(2000);
            // doklejamy koncowke, a bez .trim() jest ogon dziwny
            //wysylam
            byte[] data = msg.trim().concat("\r\n").getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkg = new DatagramPacket(data, data.length, target.address);
            socketUdp.send(pkg);
            //odbieram
            byte[] buf = new byte[256];
            DatagramPacket respPkg = new DatagramPacket(buf, buf.length);
            socketUdp.receive(respPkg);

            return new String(respPkg.getData(), 0, respPkg.getLength(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "NA";
        }
    }

    // wysylamy cokolwiek nieprawidłowego i sprawdzam czy dopowie "NA"
    private static boolean checkProtocolTcp(ServerNode target) {
        try {
            Socket socket = new Socket(target.host, target.port);
            socket.setSoTimeout(2000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println("testOfConnection\r\n");
            in.readLine();
            return true;
        } catch (IOException e) { return false; }
    }


    // wysylam wadliwą komende i czekam na zwrot "NA" zeby się nie zaptętlił na jakiejś komendzie znanej
    private static boolean checkProtocolUdp(ServerNode target) {
        try {
            DatagramSocket socketUdp = new DatagramSocket();
            socketUdp.setSoTimeout(2000);
            byte[] data = "testOfConnection\r\n".getBytes();
            socketUdp.send(new DatagramPacket(data, data.length, target.address));
            byte[] buf = new byte[256];
            socketUdp.receive(new DatagramPacket(buf, buf.length));
            return true;
        } catch (IOException e) { return false; }
    }

    // pętelka po liście serwerów i zamknięcie Proxy
    private static void broadcastQuit(List<ServerNode> targets) {
        log("Broadcasting QUIT...");
        for (ServerNode t : targets) {
            forwardAndPickProtocol(t, "QUIT");
        }
        log("Wyłaczanie proxy");
        System.exit(0);
    }

    public static void log(String msg) {
        System.out.println("[Proxy] " + msg);
    }
}