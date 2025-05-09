package component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import common.pattern.LeaderFollower;
import java.util.concurrent.TimeUnit;

/**
 * Implementação do Componente B - serviço de processamento de eventos com alta disponibilidade.
 */
public class ComponentB extends BaseComponent {
    private static final Logger LOGGER = Logger.getLogger(ComponentB.class.getName());
    
    // Armazenamento de eventos em memória
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Gson gson = new Gson();
    
    /**
     * Construtor para o Componente B.
     */
    public ComponentB(String host, int httpPort, int tcpPort, int udpPort,
                      String gatewayHost, int gatewayRegistrationPort) {
        super("componentB", host, httpPort, tcpPort, udpPort, 
              gatewayHost, gatewayRegistrationPort);
    }
    
    @Override
    protected void onBecomeLeader() {
        // LOGGER.info("ComponentB[" + instanceId + "] tornou-se líder");
        
        // Adiciona evento registrando a nova liderança
        String leaderEvent = System.currentTimeMillis() + ": LEADERSHIP_CHANGE - " + instanceId + " tornou-se líder";
        events.add(leaderEvent);
        
        // Agenda replicação periódica de estado
        scheduler.scheduleAtFixedRate(
            this::replicateState,
            1000, 5000, TimeUnit.MILLISECONDS
        );
    }
    
    @Override
    protected void onBecomeFollower() {
        // LOGGER.info("ComponentB[" + instanceId + "] tornou-se seguidor");
        
        // Adiciona evento registrando a mudança para seguidor
        String followerEvent = System.currentTimeMillis() + ": LEADERSHIP_CHANGE - " + instanceId + " tornou-se seguidor";
        events.add(followerEvent);
    }
    
    @Override
    protected String serializeState() {
        return gson.toJson(events);
    }
    
    @Override
    protected void processStateUpdate(String stateData) {
        try {
            Type listType = new TypeToken<List<String>>(){}.getType();
            List<String> leaderEvents = gson.fromJson(stateData, listType);
            
            // Atualiza os eventos locais com os do líder
            events.clear();
            events.addAll(leaderEvents);
            
            // LOGGER.info("ComponentB[" + instanceId + "] atualizou eventos do líder. Total: " + events.size());
        } catch (Exception e) {
            // LOGGER.log(Level.SEVERE, "Erro ao processar atualização de estado", e);
        }
    }
    
    @Override
    protected void handleHTTPRequest(Socket clientSocket) {
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            OutputStream output = clientSocket.getOutputStream()
        ) {
            // Lê a requisição HTTP
            StringBuilder requestBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                requestBuilder.append(line).append("\r\n");
            }
            
            // Analisa a requisição
            String request = requestBuilder.toString();
            String firstLine = request.substring(0, request.indexOf("\r\n"));
            String[] parts = firstLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            
            // Processa a requisição
            String response;
            if (path.equals("/events") && method.equals("GET")) {
                // Retorna todos os eventos
                StringBuilder eventsStr = new StringBuilder();
                for (String event : events) {
                    eventsStr.append(event).append("\n");
                }
                response = buildHTTPResponse("200 OK", "text/plain", eventsStr.toString());
            } else if (path.equals("/events") && method.equals("POST")) {
                // Adiciona um novo evento a partir do corpo da requisição
                int contentLength = 0;
                if (request.contains("Content-Length:")) {
                    String contentLengthStr = request.substring(
                        request.indexOf("Content-Length:") + 16,
                        request.indexOf("\r\n", request.indexOf("Content-Length:"))
                    );
                    contentLength = Integer.parseInt(contentLengthStr.trim());
                }
                
                StringBuilder eventBuilder = new StringBuilder();
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    reader.read(buffer, 0, contentLength);
                    eventBuilder.append(buffer);
                }
                
                // Armazena o evento com timestamp
                String timestamp = String.valueOf(System.currentTimeMillis());
                String event = timestamp + ": " + eventBuilder.toString();
                events.add(event);
                
                response = buildHTTPResponse("201 Created", "text/plain", "Evento adicionado: " + event);
            } else if (path.equals("/count")) {
                // Retorna a contagem de eventos
                response = buildHTTPResponse("200 OK", "text/plain", "Quantidade de eventos: " + events.size());
            } else if (path.equals("/info")) {
                // Retorna informações do componente
                String info = "Instância do Componente B " + instanceId + "\n" +
                              "Quantidade de eventos: " + events.size() + "\n" +
                              "Executando em: " + host + "\n" +
                              "Porta HTTP: " + httpPort;
                response = buildHTTPResponse("200 OK", "text/plain", info);
            } else {
                // Endpoint desconhecido
                response = buildHTTPResponse("404 Not Found", "text/plain", "Endpoint desconhecido");
            }
            
            // Envia a resposta
            output.write(response.getBytes());
            output.flush();
            
            // LOGGER.info("Componente B[" + instanceId + "] processou requisição HTTP: " + firstLine);
        } catch (IOException e) {
            // LOGGER.log(Level.WARNING, "Erro ao processar requisição HTTP no Componente B", e);
        }
    }
    
    @Override
    protected void handleTCPRequest(Socket clientSocket) {
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            String request = reader.readLine();
            
            if (request != null && !request.isEmpty()) {
                // Analisa o formato da requisição: ACTION|DATA (DATA é opcional)
                String[] parts = request.split("\\|", 2);
                String action = parts[0].toUpperCase();
                
                String response;
                switch (action) {
                    case "ADD_EVENT":
                        if (parts.length >= 2) {
                            String eventData = parts[1];
                            
                            // Se for seguidor, redireciona para o líder
                            if (!isLeader && leaderFollower != null && leaderFollower.getLeaderId() != null) {
                                response = "REDIRECT|" + leaderFollower.getLeaderId() + "|" +
                                           "Operação de escrita deve ser enviada ao líder";
                            } else {
                                String timestamp = String.valueOf(System.currentTimeMillis());
                                String event = timestamp + ": " + eventData;
                                events.add(event);
                                response = "SUCCESS|Evento adicionado com ID: " + (events.size() - 1);
                                
                                // Se for líder, replica o estado para os seguidores
                                if (isLeader) {
                                    replicateState();
                                }
                            }
                        } else {
                            response = "ERROR|Formato ADD_EVENT inválido, esperado: ADD_EVENT|DATA";
                        }
                        break;
                    case "GET_EVENTS":
                        StringBuilder eventsStr = new StringBuilder();
                        for (String event : events) {
                            eventsStr.append(event).append("|");
                        }
                        response = "EVENTS|" + (eventsStr.length() > 0 ? 
                                    eventsStr.substring(0, eventsStr.length() - 1) : "");
                        break;
                    case "COUNT":
                        response = "COUNT|" + events.size();
                        break;
                    case "INFO":
                        response = "INFO|Componente B|" + instanceId + "|" + events.size() + "|" +
                                   (isLeader ? "LEADER" : "FOLLOWER");
                        break;
                    case "LEADER":
                        if (isLeader) {
                            response = "LEADER|" + instanceId + "|" + host + "|" + leaderPort;
                        } else if (leaderFollower != null && leaderFollower.getLeaderId() != null) {
                            response = "LEADER|" + leaderFollower.getLeaderId();
                        } else {
                            response = "UNKNOWN_LEADER";
                        }
                        break;
                    default:
                        response = "ERROR|Ação desconhecida: " + action;
                }
                
                writer.println(response);
                // LOGGER.info("Componente B[" + instanceId + "] processou requisição TCP: " + action);
            }
        } catch (IOException e) {
            // LOGGER.log(Level.WARNING, "Erro ao processar requisição TCP no Componente B", e);
        }
    }
    
    @Override
    protected void handleUDPRequest(byte[] data, InetAddress clientAddress, int clientPort) {
        try {
            String request = new String(data);
            
            // Analisa o formato da requisição: ACTION|DATA (DATA é opcional)
            String[] parts = request.split("\\|", 2);
            String action = parts[0].toUpperCase();
            
            String response;
            switch (action) {
                case "ADD_EVENT":
                    if (parts.length >= 2) {
                        String eventData = parts[1];
                        String timestamp = String.valueOf(System.currentTimeMillis());
                        String event = timestamp + ": " + eventData;
                        events.add(event);
                        response = "SUCCESS|Evento adicionado com ID: " + (events.size() - 1);
                    } else {
                        response = "ERROR|Formato ADD_EVENT inválido, esperado: ADD_EVENT|DATA";
                    }
                    break;
                case "COUNT":
                    response = "COUNT|" + events.size();
                    break;
                case "INFO":
                    response = "INFO|Componente B|" + instanceId + "|" + events.size();
                    break;
                default:
                    response = "ERROR|Ação desconhecida: " + action;
            }
            
            // Envia a resposta
            byte[] responseData = response.getBytes();
            DatagramPacket responsePacket = new DatagramPacket(
                responseData, responseData.length, clientAddress, clientPort
            );
            udpServer.send(responsePacket);
            
            // LOGGER.info("Componente B[" + instanceId + "] processou requisição UDP: " + action);
        } catch (IOException e) {
            // LOGGER.log(Level.WARNING, "Erro ao processar requisição UDP no Componente B", e);
        }
    }
    
    /**
     * Constrói uma resposta HTTP.
     */
    private String buildHTTPResponse(String status, String contentType, String body) {
        return "HTTP/1.1 " + status + "\r\n" +
               "Content-Type: " + contentType + "\r\n" +
               "Content-Length: " + body.length() + "\r\n" +
               "Connection: close\r\n" +
               "\r\n" +
               body;
    }
    
    /**
     * Método principal para executar o Componente B.
     */
    public static void main(String[] args) {
        // Valores padrão
        String host = "localhost";
        int httpPort = 8091;
        int tcpPort = 8092;
        int udpPort = 8093;
        String gatewayHost = "localhost";
        int gatewayRegistrationPort = 8000;
        
        // Analisa argumentos da linha de comando, se fornecidos
        if (args.length >= 6) {
            host = args[0];
            httpPort = Integer.parseInt(args[1]);
            tcpPort = Integer.parseInt(args[2]);
            udpPort = Integer.parseInt(args[3]);
            gatewayHost = args[4];
            gatewayRegistrationPort = Integer.parseInt(args[5]);
        }
        
        // Para iniciar como seguidor, adicione parâmetros de liderança:
        // java ComponentB host httpPort tcpPort udpPort gatewayHost gatewayPort leaderHost leaderPort
        
        if (args.length >= 9) {
            // Código para iniciar como seguidor
            String leaderHost = args[7];
            int leaderLFPort = Integer.parseInt(args[8]);
            
            ComponentB follower = new ComponentB(
                host, httpPort, tcpPort, udpPort, gatewayHost, gatewayRegistrationPort
            );
            
            // Configure o componente como seguidor
            follower.leaderFollower = LeaderFollower.createFollower(
                "componentB", follower.instanceId, host, follower.leaderPort,
                "leader-componentB", leaderHost, leaderLFPort
            );
            
            follower.start();
            
            // LOGGER.info("ComponentB iniciado como seguidor, conectando-se ao líder em " + 
            //            leaderHost + ":" + leaderLFPort);
        } else {
            // Código para iniciar normalmente (potencialmente como líder)
            ComponentB component = new ComponentB(
                host, httpPort, tcpPort, udpPort, gatewayHost, gatewayRegistrationPort
            );
            component.start();
            
            // LOGGER.info("ComponentB iniciado com as portas - HTTP: " + httpPort + 
            //            ", TCP: " + tcpPort + ", UDP: " + udpPort);
        }
        
        // Adiciona um hook para desligamento
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Shutdown code
        }));
    }
}
