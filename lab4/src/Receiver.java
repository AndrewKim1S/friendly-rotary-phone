import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;


public class Receiver {
	int port;
	int mtu;
	int sws;
	String filename;

	// Whether FIN flag has been raised
	boolean receiving;

	DatagramSocket socket;

	public static final int TCP_PACKET_LEN = 24;

	public Receiver(int port, int mtu, int sws, String filename) {
		this.port = port;
		this.mtu = mtu;
		this.sws = sws;
		this.filename = filename;

		this.receiving = true;

		try {
			socket = new DatagramSocket(port);
		} catch(Exception e) { e.printStackTrace(); }

		receiveSegment();
	}

	// Receive Segment
	private void receiveSegment() {
		while(receiving) {
			byte[] data = new byte[TCP_PACKET_LEN + mtu];
			DatagramPacket packet = new DatagramPacket(data, data.length);
			try {
				socket.receive(packet);
			} catch (Exception e) { e.printStackTrace(); }

			// parse packet
			parseData(packet.getData());
		}
	}

	private void parseData(byte[] data) {
		int seq_num = ByteBuffer.wrap(data).getInt();
		int ack_num = ByteBuffer.wrap(data).getInt();
		long timestamp = ByteBuffer.wrap(data).getLong();
		int length = ByteBuffer.wrap(data).getInt();
		boolean SYN = (length & 1) != 0;
		length = length >>> 1;
		boolean FIN = (length & 1) != 0;
		length = length >>> 1;
		boolean ACK = (length & 1) != 0;
		length = length >>> 1;
		short zeroes = ByteBuffer.wrap(data).getShort();
		short checksum = ByteBuffer.wrap(data).getShort();
		byte[] payload = new byte[length];
		ByteBuffer.wrap(data).get(payload);


		System.out.println("Received: " + new String(payload));
	}

}
