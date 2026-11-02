import java.net.InetSocketAddress;

class ServerNode {
    String host;
    int port;
    InetSocketAddress address;
    ServerType type;

    public ServerNode(String host, int port) {
        this.host = host;
        this.port = port;
        this.address = new InetSocketAddress(host, port);
        this.type = ServerType.TCP;
    }

    public void setType(ServerType type) { this.type = type; }
    public ServerType getType() { return type; }

    @Override
    public String toString() {
        return address + " [" + type + "]";
    }
}