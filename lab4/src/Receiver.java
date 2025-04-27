import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.io.FileOutputStream;
import java.util.HashMap;


public class Receiver {
	int port;
	int mtu;
	int sws;
	String filename;

	// Whether FIN flag has been raised
	boolean receiving;

	int seq_num;
	InetAddress remote_ip;
	int remote_port;

	int start_window;
	int next_expected_seq;
	HashMap<Integer, byte[]> outOfOrderSeg = new HashMap<>();

	DatagramSocket socket;
	FileOutputStream writer;

	public static final int TCP_PACKET_LEN = 24;

	public Receiver(int port, int mtu, int sws, String filename) {
		this.port = port;
		this.mtu = mtu;
		this.sws = sws;
		this.filename = filename;

		this.receiving = true;
		this.seq_num = 0;

		this.start_window = 1;
		this.next_expected_seq = 1;

		try {
			this.socket = new DatagramSocket(port);
			this.writer = new FileOutputStream(filename);
		} catch(Exception e) { e.printStackTrace(); }

		tcpHandshakeReceiver();
		receiveSegment();
	}

	
	// Receive Segment
	private void receiveSegment() {
		while(true) {
			byte[] data = new byte[TCP_PACKET_LEN + mtu];
			DatagramPacket packet = new DatagramPacket(data, data.length);
			try {	socket.receive(packet); } 
			catch (Exception e) { e.printStackTrace(); }

			// parse packet
			byte[] packetData = packet.getData();
			int seq = Util.TCPGetSeqNum(packetData);
			int ack_num = Util.TCPGetAckNum(packetData);
			long timestamp = Util.TCPGetTime(packetData);
			int length = Util.TCPGetLen(packetData);
			boolean S = Util.TCPGetSYN(packetData);
			boolean F = Util.TCPGetFIN(packetData);
			boolean A = Util.TCPGetACK(packetData);
			short checksum = Util.TCPGetChecksum(packetData);
			byte[] payload = Util.TCPGetData(packetData);

			Util.outputSegmentInfo(false, timestamp, S, F, A, length > 0, seq, length, ack_num);

			// FIN flag is set then start teardown of TCP
			if(F) { 
				tcpTeardownReceiver(seq); 
				return;
			} 

			// verify checksum
			short recalc_check = Util.checksum(payload);
			if(recalc_check != checksum) { continue; }

			// Manage sliding window
			// Packet arrives in order
			if(seq == this.next_expected_seq) {
				this.start_window ++;
				this.next_expected_seq += length;
				// Send ack
				sendAck(this.seq_num, this.next_expected_seq);
				// write the data to the output file
				writeToFile(payload);

				// If window can plug holes with packets that are in buffer 
				while(outOfOrderSeg.containsKey(next_expected_seq)) {
					byte[] payload_outOfOrder = outOfOrderSeg.get(this.next_expected_seq);
					writeToFile(outOfOrderSeg.get(this.next_expected_seq));
					outOfOrderSeg.remove(this.next_expected_seq);

					this.next_expected_seq += payload_outOfOrder.length;
				}
			} 
			// Packet is not expected but within window
			else if(seq > this.next_expected_seq && seq < this.next_expected_seq + (this.mtu * this.sws)) {
				outOfOrderSeg.put(seq, payload);
				sendAck(this.seq_num, this.next_expected_seq); 
			}
			else {
				sendAck(this.seq_num, this.next_expected_seq);
			}
		}
	}


	// send Ack 
	private void sendAck(int seq, int ack_num) {
		byte[] TCP_packet = new byte[TCP_PACKET_LEN];
		int length = 0;
		length = length << 2;
		length = (length << 1) | 1;
		long time = System.nanoTime();
		ByteBuffer.wrap(TCP_packet).putInt(0, seq_num);            // seq num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putInt(4, ack_num);            // ack num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putLong(8, time);              // timestamp (8 bytes)
		ByteBuffer.wrap(TCP_packet).putInt(16, length);            // length of data is 0
		try{
			DatagramPacket UDP_packet = new DatagramPacket(TCP_packet, TCP_packet.length, remote_ip, remote_port);
			socket.send(UDP_packet);
			Util.outputSegmentInfo(true, time, false, false, true, false, seq_num, 0, ack_num);
		} catch (Exception e) { e.printStackTrace(); }
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
				this.remote_ip = InetAddress.getByName(packet.getAddress().getHostAddress());
				this.remote_port = packet.getPort();

				DatagramPacket packet2 = new DatagramPacket(ack_data, ack_data.length, remote_ip, remote_port);
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



	// tcp teardown for receiver
	private void tcpTeardownReceiver(int seq) {
		// send fin ack
		byte[] TCP_packet = new byte[TCP_PACKET_LEN];
		int length = 0;
		length = length << 1;
		length = (length << 1) | 1;
		length = (length << 1) | 1;
		long time = System.nanoTime();
		ByteBuffer.wrap(TCP_packet).putInt(0, seq_num);            // seq num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putInt(4, seq + 1);            // ack num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putLong(8, time);              // timestamp (8 bytes)
		ByteBuffer.wrap(TCP_packet).putInt(16, length);            // length of data is 0
		try{
			DatagramPacket UDP_packet = new DatagramPacket(TCP_packet, TCP_packet.length, remote_ip, remote_port);
			socket.send(UDP_packet);
			Util.outputSegmentInfo(true, time, false, true, true, false, seq_num, 0, seq + 1);
		} catch (Exception e) { e.printStackTrace(); }

		// receive ack
		byte[] TCP_packet2 = new byte[TCP_PACKET_LEN];
		DatagramPacket UDP_packet2 = new DatagramPacket(TCP_packet2, TCP_packet2.length);
		try {	socket.receive(UDP_packet2); } 
		catch (Exception e) { e.printStackTrace(); }
		byte[] packetData = UDP_packet2.getData();
		Util.outputSegmentInfo(false, Util.TCPGetTime(packetData), Util.TCPGetSYN(packetData), 
			Util.TCPGetFIN(packetData), Util.TCPGetACK(packetData), false, Util.TCPGetSeqNum(packetData), 
			Util.TCPGetLen(packetData), Util.TCPGetAckNum(packetData));
	}



	// Write data to output file
	private void writeToFile(byte[] data) {
		try {
			writer.write(data);
		} catch (Exception e) { e.printStackTrace(); }
	}


}


