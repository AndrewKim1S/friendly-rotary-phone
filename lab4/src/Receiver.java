import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;


public class Receiver {
	int port;
	int mtu;
	int sws;
	String filename;

	// Whether FIN flag has been raised
	boolean receiving;

	int seq_num;
	String remote_ip;
	int remote_port;

	DatagramSocket socket;

	public static final int TCP_PACKET_LEN = 24;

	public Receiver(int port, int mtu, int sws, String filename) {
		this.port = port;
		this.mtu = mtu;
		this.sws = sws;
		this.filename = filename;

		this.receiving = true;
		this.seq_num = 0;

		try {
			socket = new DatagramSocket(port);
		} catch(Exception e) { e.printStackTrace(); }

		tcpHandshakeReceiver();
		receiveSegment();
	}


	// Receiver's tcp handshake
	private void tcpHandshakeReceiver() {
		try {
			// Receiver waits for Sender to initiate TCP handshake
			byte[] data = new byte[TCP_PACKET_LEN];
			DatagramPacket packet = new DatagramPacket(data, data.length);
			socket.receive(packet);
			byte[] packet_data = packet.getData();
			Util.outputSegmentInfo(false, Util.TCPGetTime(packet_data), 
				Util.TCPGetSYN(packet_data), Util.TCPGetFIN(packet_data), Util.TCPGetACK(packet_data),
				false, Util.TCPGetSeqNum(packet_data), 0, Util.TCPGetAckNum(packet_data));

			if(Util.TCPGetSYN(packet_data)) {
				// Receiver sends ACK
				int len_flag1 = 0;
				int seq_num_rec = Util.TCPGetSeqNum(packet_data) + 1;
				len_flag1 = (len_flag1 << 1) | 1;
				len_flag1 = len_flag1 << 1;
				len_flag1 = (len_flag1 << 1) | 1;
				byte[] ack_data = new byte[TCP_PACKET_LEN];
				ByteBuffer.wrap(ack_data).putInt(0, this.seq_num);
				ByteBuffer.wrap(ack_data).putInt(4, seq_num_rec);
				ByteBuffer.wrap(ack_data).putLong(8, System.nanoTime());
				ByteBuffer.wrap(ack_data).putInt(16, len_flag1);

				// Get the sender's ip and port 
				this.remote_ip = packet.getAddress().getHostAddress();
				this.remote_port = packet.getPort();

				DatagramPacket packet2 = new DatagramPacket(ack_data, ack_data.length, InetAddress.getByName(remote_ip), remote_port);
				socket.send(packet2);
				Util.outputSegmentInfo(true, Util.TCPGetTime(ack_data), true, false, true, false, this.seq_num, 0, seq_num_rec);
				this.seq_num ++;

				// Receive the last part of the 3 way handshake
				byte[] data3 = new byte[TCP_PACKET_LEN];
				DatagramPacket packet3 = new DatagramPacket(data, data.length);
				socket.receive(packet3);
				byte[] packet_data3 = packet.getData();
				Util.outputSegmentInfo(false, Util.TCPGetTime(packet_data3), 
					Util.TCPGetSYN(packet_data3), Util.TCPGetFIN(packet_data3), Util.TCPGetACK(packet_data3),
					false, Util.TCPGetSeqNum(packet_data3), 0, Util.TCPGetAckNum(packet_data3));
			}
		} catch (Exception e) { e.printStackTrace(); }
	}

	
	// Receive Segment
	private void receiveSegment() {
		while(true) {
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

		try{
			DatagramPacket UDP_packet = new DatagramPacket(TCP_packet, TCP_packet.length, 
				InetAddress.getByName(remote_ip), remote_port);
			socket.send(UDP_packet);
		} catch (Exception e) { e.printStackTrace(); }
	}
}


