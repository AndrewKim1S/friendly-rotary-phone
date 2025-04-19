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

		// handle 3 way handshake 
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
			byte[] packetData = packet.getData();
			int seq_num = Util.TCPGetSeqNum(packetData);
			int ack_num = Util.TCPGetAckNum(packetData);
			long timestamp = Util.TCPGetTime(packetData);
			int length = Util.TCPGetLen(packetData);
			boolean S = Util.TCPGetSYN(packetData);
			boolean F = Util.TCPGetFIN(packetData);
			boolean A = Util.TCPGetACK(packetData);
			short checksum = Util.TCPGetChecksum(packetData);
			byte[] payload = Util.TCPGetData(packetData);

			Util.outputSegmentInfo(false, timestamp, S, F, A, false, seq_num, length, ack_num);

			// verify checksum

			// FIN flag is set then start teardown of TCP

			// Send ack
			sendAck(seq_num, ack_num);
		}
	}


	// send Ack 
	private void sendAck(int seq_num, int ack_num) {
		byte[] TCP_packet = new byte[TCP_PACKET_LEN];
		ByteBuffer.wrap(TCP_packet).putInt(0, seq_num);            // seq num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putInt(4, ack_num);                  // ack num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putLong(8, System.nanoTime()); // timestamp (8 bytes)
		// Set length of data - 0, and flags A to true
		int length = 0;
		length = length << 0;
		length = length << 0;
		length = length << 1;
		ByteBuffer.wrap(TCP_packet).putInt(16, length); // length of data is 0

		/*try{
			DatagramPacket UDP_packet = new DatagramPacket(TCP_packet, TCP_packet.length, 
				InetAddress.getByName(remote_ip), remote_port);
			socket.send(UDP_packet);
		} catch (Exception e) { e.printStackTrace(); } */
	}
}


