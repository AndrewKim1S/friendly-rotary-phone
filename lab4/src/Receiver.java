
public class Receiver {
	int port;
	int mtu;
	int sws;
	String filename;

	public Receiver(int port, int mtu, int sws, String filename) {
		this.port = port;
		this.mtu = mtu;
		this.sws = sws;
		this.filename = filename;
	}
}
