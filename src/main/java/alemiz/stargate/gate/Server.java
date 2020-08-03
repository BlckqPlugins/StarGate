package alemiz.stargate.gate;

import alemiz.stargate.StarGate;
import alemiz.stargate.docker.DockerPacketHandler;
import alemiz.stargate.gate.client.ClientData;
import alemiz.stargate.gate.client.Handler;
import alemiz.stargate.gate.events.CustomPacketEvent;
import alemiz.stargate.gate.events.PacketPreHandleEvent;
import alemiz.stargate.gate.packets.*;
import alemiz.stargate.gate.tasks.PingTask;
import alemiz.stargate.untils.gateprotocol.Convertor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class Server {
    protected StarGate plugin;

    private static Server instance;
    private static GateAPI gateAPI;

    /**
     * Settings of StarGate protocol, communication services and more
     * These services are running on separated threads, so performance will not get DOWN
     */
    protected static int port = 47007;
    protected static int maxConn = 50;
    public static String password = "123456789";

    /**
     * Ping delay in seconds
     */
    public static final long PING_DELAY = 25;

    protected final Map<String, Handler> clients = new ConcurrentHashMap<>();
    protected Map<String, Long> pingHistory = new HashMap<>();
    protected Map<Integer, StarGatePacket> packets = new HashMap<>();

    private final AtomicLong threadIndex = new AtomicLong(0);
    protected ExecutorService clientPool;
    protected Thread serverThread;

    protected final Map<String, ClientData> clientDataMap = new ConcurrentHashMap<>();

    public Server(StarGate plugin){
        instance = this;
        this.plugin = plugin;

        gateAPI = new GateAPI(this);

        this.initConfig();
        this.initPackets();
        this.start();
    }

    public static Server getInstance(){
        return instance;
    }

    public void start(){
        plugin.getLogger().info("§aStarting StarGate Protocol on Port: §2"+port);

        ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable);
                thread.setName("StarGate client-" + threadIndex.getAndIncrement());
                return thread;
            }
        };
        this.clientPool = Executors.newFixedThreadPool(maxConn, threadFactory);


        Runnable serverTask = new Runnable(){
            @Override
            public void run() {
                try (ServerSocket listener = new ServerSocket(port)) {
                    plugin.getLogger().info("§cDone! §aStarGate Protocol is successfully running. Waiting for clients...");
                    while (true) {
                        Handler client = new Handler(listener.accept());
                        clientPool.execute(client);

                        /* There is no need to check for delay. Just sleep*/
                        Thread.sleep(50);
                    }
                }catch (Exception e) {
                    //ignore
                }
            }
        };

        /* Here we are creating new Thread for Server only
        * Every client has its own Thread*/
        serverThread = new Thread(serverTask, "StarGate Server");
        serverThread.start();

        /* Launching PingTask is very easy
        * Just set delay (in seconds) and launch task*/
        this.plugin.getProxy().getScheduler().schedule(this.plugin, new PingTask(), 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Here we are registering new Packets, may be useful for DEV
     * Every packet Extends @class StarGatePacket*/

    private void initPackets(){
        GateAPI.RegisterPacket(new WelcomePacket());
        GateAPI.RegisterPacket(new PingPacket());
        GateAPI.RegisterPacket(new PlayerTransferPacket());
        GateAPI.RegisterPacket(new KickPacket());
        GateAPI.RegisterPacket(new PlayerOnlinePacket());
        GateAPI.RegisterPacket(new ForwardPacket());
        GateAPI.RegisterPacket(new ConnectionInfoPacket());

        if (plugin.cfg.getBoolean("dynamicServers")){
            GateAPI.RegisterPacket(new ServerManagePacket());
        }
    }

    private void initConfig(){
        port = plugin.cfg.getInt("port");
        maxConn = plugin.cfg.getInt("maxConnections");
        password = plugin.cfg.getString("password");
    }

    /* This function we use to send packet to Clients
     * You must specify Client name or Chevron and packet that will be sent*/

    protected String gatePacket(String client, StarGatePacket packet){
        if (!clients.containsKey(client) || clients.get(client) == null) return null;

        Handler clientHandler = clients.get(client);
        return clientHandler.gatePacket(packet);
    }

    /* Using these function we can process packet from string to data
    *  After packet is successfully created we can handle that Packet*/

    public StarGatePacket processPacket(String client, String packetString) throws InstantiationException, IllegalAccessException{
        String[] data = Convertor.getPacketStringData(packetString);
        int PacketId = Integer.decode(data[0]);


        if (!packets.containsKey(PacketId) || packets.get(PacketId) == null) return null;

        /* Here we decode Packet. Create from String Data*/
        StarGatePacket packet = packets.get(PacketId).getClass().newInstance();
        String uuid = data[data.length - 1];


        /*
        * Preprocessing official packets if its needed.
        * TODO: maybe pre-process event?
        */
        switch (packet.getID()){
            default:
                packet.uuid = uuid;
                packet.encoded = packetString;
                break;
        }

        try {
            packet.decode();
        }catch (Exception e){
            plugin.getLogger().warning("§eUnable to decode packet with ID "+packet.getID());
            plugin.getLogger().warning("§c"+e.getMessage());
            return packet;
        }

        PacketPreHandleEvent event = plugin.getProxy().getPluginManager().callEvent(new PacketPreHandleEvent(client, packet));
        if (event.isCancelled()){
            return packet;
        }

        if (!(packet instanceof ConnectionInfoPacket)){
            this.handlePacket(client, packet);
        }

        return packet;
    }

    private void handlePacket(String client, StarGatePacket packet){
        int type = packet.getID();

        ProxiedPlayer player;

        switch (type){
            case Packets.WELCOME_PACKET:
                WelcomePacket welcomePacket = (WelcomePacket) packet;
                plugin.getLogger().info("§bReceiving first data from §e"+welcomePacket.server);
                plugin.getLogger().info("§bUSAGE: §e"+welcomePacket.usage+"%§b TPS: §e"+welcomePacket.tps+" §bPLAYERS: §e"+welcomePacket.players+"/§6"+welcomePacket.maxPlayers);
                this.updateClientData(welcomePacket);
                break;
            case Packets.PING_PACKET:
                long delay = TimeUnit.SECONDS.toMillis(PING_DELAY);
                long now = System.currentTimeMillis();
                Long received = pingHistory.remove(client);

                if (received == null) break;
                long ping = (now - received);

                if (ping <= delay){
                    Handler handler = clients.get(client);
                    handler.setStable(true);
                    break;
                }

                plugin.getLogger().info("§bConnection with §e"+client+" §b is slow! Ping: §e"+ping+"ms");
                try {
                    Handler handler = clients.remove(client);
                    if (!handler.reconnect()){
                        plugin.getLogger().info("§cERROR: Reconnecting with §6"+client+"§cwas interrupted!");
                        plugin.getLogger().info("§cTrying to establish new connection with §6"+client);
                    }
                }catch (NullPointerException e){
                    plugin.getLogger().info("§cLooks like client §6"+client +"§c keeps already disconnected!");
                }

                break;
            case Packets.PLAYER_TRANSFER_PACKET:
                PlayerTransferPacket transferPacket = (PlayerTransferPacket) packet;
                player = ProxyServer.getInstance().getPlayer(transferPacket.getPlayer());

                if (player == null){
                    plugin.getLogger().info("§cWARNING: §bTransfer Packet => Player not found!");
                }else {
                    ServerInfo server = plugin.getProxy().getServerInfo(transferPacket.getDestination());

                    /*Prevent disconnecting client if server doesnt exist*/
                    if (server == null){
                        player.sendMessage(new TextComponent("§cCant connect to server §6"+transferPacket.getDestination()+"§c!"));
                        plugin.getLogger().info("§cWARNING: Player "+player.getName()+" was supposed to connect server that is unreachable!");
                        return;
                    }
                    player.connect(server);
                }

                break;
            case Packets.KICK_PACKET:
                KickPacket kickPacket = (KickPacket) packet;
                player = ProxyServer.getInstance().getPlayer(kickPacket.getPlayer());

                if (player == null){
                    plugin.getLogger().info("§cWARNING: §bKick Packet => Player not found!");
                }else {
                    String reason = StarGate.getInstance().colorText(kickPacket.getReason());
                    player.disconnect(new TextComponent(reason));
                }
                break;
            case Packets.PLAYER_ONLINE_PACKET:
                PlayerOnlinePacket onlinePacket = (PlayerOnlinePacket) packet;

                ProxiedPlayer guest = null;
                if (onlinePacket.getCustomPlayer() != null){
                    guest = ProxyServer.getInstance().getPlayer(onlinePacket.getCustomPlayer());
                }

                if (onlinePacket.getPlayer() != null && onlinePacket.getPlayer().isConnected()){
                    guest = onlinePacket.getPlayer();
                }

                if (guest == null){
                    GateAPI.setResponse(client, onlinePacket.getUuid(), "false");
                }else {
                    GateAPI.setResponse(client, onlinePacket.getUuid(), "true!"+guest.getServer().getInfo().getName());
                }

                break;
            case Packets.FORWARD_PACKET:
                ForwardPacket forwardPacket = (ForwardPacket) packet;
                String sendto = forwardPacket.getClient();

                if (!clients.containsKey(sendto) || (clients.get(sendto) == null)){
                    plugin.getLogger().info("§cWARNING: ForwardPacket => Client §6"+sendto+"§c isnt connected!");
                    return;
                }

                Handler handler = clients.get(sendto);
                String data = forwardPacket.getEncodedPacket();

                handler.getOut().println(data);
                break;
            case Packets.SERVER_MANAGE_PACKET:
                switch (((ServerManagePacket) packet).getPacketType()){
                    case ServerManagePacket.SERVER_ADD:
                        gateAPI.addServer((ServerManagePacket) packet, client);
                        break;
                    case ServerManagePacket.SERVER_REMOVE:
                        gateAPI.removeServer((ServerManagePacket) packet, client);
                        break;
                    default:
                        if (this.plugin.cfg.getBoolean("handleDockerizedPackets")){
                            DockerPacketHandler.handle((ServerManagePacket) packet, client);
                        }
                        break;
                }
                break;
            default:
                /** Here we call Event that will send packet to DEVs plugin*/
                plugin.getProxy().getPluginManager().callEvent(new CustomPacketEvent(client, packet));
                break;
        }
    }

    /* Simple method to check if client is alive*/
    public boolean isConnected(String client){
        try {
            Handler handler = clients.get(client);
            handler.getOut().println("GATE_STATUS:" +System.nanoTime());
            return true;
        }catch (Exception e){
            return false;
        }
    }

    /* Server Data*/
    public Handler getClient(String clientName){
        return this.clients.get(clientName);
    }

    public boolean registerClient(String clientName, Handler handler){
        Handler oldHandler = this.clients.get(clientName);
        if (oldHandler != null){
            return false;
        }

        this.clients.put(clientName, handler);
        return true;
    }

    public boolean unregisterClient(Handler handler){
        if (handler == null) return false;
        boolean success = this.clients.remove(handler.getName()) != null;

        if (success){
            this.clientDataMap.remove(handler.getName());
            this.plugin.getLogger().info("§cWARNING: Connection with §6"+handler.getName()+"§c has been closed!");
            this.plugin.getLogger().info("§cReason: §4"+handler.getCloseReason());
        }
        return success;
    }

    public Map<String, Handler> getClients() {
        return this.clients;
    }

    public ClientData getClientData(String clientName){
        return this.clientDataMap.get(clientName);
    }

    public void updateClientData(WelcomePacket packet){
        this.updateClientData(packet.server, packet.maxPlayers);
    }

    public void updateClientData(String clientName, int onlinePlayers){
        ClientData clientData = this.getClientData(clientName);
        if (clientData == null){
            clientData = new ClientData(clientName, onlinePlayers);
        }else {
            clientData.setMaxPlayers(onlinePlayers);
        }

        this.clientDataMap.put(clientName, clientData);
    }

    public Map<String, Long> getPingHistory() {
        return this.pingHistory;
    }

    public Long shiftPing(String client){
        return this.pingHistory.remove(client);
    }
}
