import java.io.FileInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;


public class Sender {
	int port;
	//String remote_ip;
	InetAddress remote_ip;
	int remote_port;
	String filename;
	int mtu;
	int sws;

	int seq_num;
	int file_pos; // position in file we are getting segments from (over bytes)
	
	// Sliding window 
	AtomicInteger start_window; 
	AtomicInteger seg_send_ind;
	// Map next sequence num from Receiver to segment number
	ConcurrentHashMap<Integer, Integer> seq_seg = new ConcurrentHashMap<>();
	// Map sequence num to bool - ie if it is in the map then it was acked out of order
	HashMap<Integer, Boolean> outOfOrderAck = new HashMap<>();

	FileInputStream fis; // will read input file per byte 
	File file;

	DatagramSocket socket;

	AtomicBoolean teardownStarted;

	public static final int MAX_RETRANSMISSIONS = 16;
	public static final int TCP_PACKET_LEN = 24;


	public Sender(int port, String remote_ip, int remote_port, String filename, int mtu, int sws) {
		this.port = port;
		this.remote_port = remote_port;
		this.filename = filename;
		this.mtu = mtu;
		this.sws = sws;

		this.seq_num = 0;
		this.file_pos = 0;
		this.start_window = new AtomicInteger(1);
		this.seg_send_ind = new AtomicInteger(1);

		this.teardownStarted = new AtomicBoolean(false);

		try {
			this.remote_ip = InetAddress.getByName(remote_ip);
		} catch(Exception e) { e.printStackTrace(); }

		try {
			fis = new FileInputStream(this.filename);
			file = new File(this.filename);
			socket = new DatagramSocket(port);
		} catch (Exception e) { e.printStackTrace(); }

		tcpHandshakeSender();

		// Create threads
		Thread threadSend = new Thread(() -> sendSegment());
		Thread threadRec = new Thread(() -> recAck());
		threadSend.start();
		threadRec.start();
		try {
			threadSend.join();
			threadRec.join();
		} catch (Exception e) {	e.printStackTrace(); }

	}


	// Send Segments
	private void sendSegment() {
		// As long as there is still info to be sent, continue sending
		while(file_pos < file.length()) {
			// Sender waits 
			while(seg_send_ind.get() >= start_window.get() + sws) {}
			// Once window has space to send more

			byte[] data = getData();          // get segment (parse from input file)
			byte[] tcp = createGenTCP(data);  // create tcp packet
			// create udp (datagram packet)
			try{
				DatagramPacket UDP_packet = new DatagramPacket(tcp, tcp.length, remote_ip, remote_port);
				socket.send(UDP_packet);
			} catch (Exception e) { e.printStackTrace(); }
			Util.outputSegmentInfo(true, Util.TCPGetTime(tcp), false, false, false, true, seq_num, data.length, 0);

			// Map seq_num of packet to seg_num
			seq_seg.put(seq_num + data.length, seg_send_ind.get());
			// increment to the next sequence number and segment number
			seq_num += data.length;
			seg_send_ind.incrementAndGet();
		}

		// Send Fin contingent on sliding window algorithm
		teardownStarted.set(true);
		tcpTeardownSender();
	}


	// Receive acks from the receiver
	private void recAck() {
		while(!teardownStarted.get()) {
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
			Util.outputSegmentInfo(false, timestamp, S, F, A, length > 0, seq, length, ack_num);

			// increment start_window if packet received is start_window th segment acked
			int seg_num = 0; // the seq_num the receiver is acknowledging
			if(seq_seg.containsKey(ack_num)) {
				seg_num = seq_seg.get(ack_num);
			} else {
				System.out.println("\nERROR: ack_num: " + ack_num + "\n");
			}

			if(seg_num == this.start_window.get()) {
				this.start_window.incrementAndGet();
				seq_seg.remove(ack_num);

				// Increment start window as much as possible to remove holes
				while(outOfOrderAck.get(this.start_window.get()) != null) {
					outOfOrderAck.remove(this.start_window);
					this.start_window.incrementAndGet();
					seq_seg.remove(ack_num);
				}
			}
			// handle out of order acks
			else {
				// check that the segment number is within the window other wise drop
				if(seg_num > this.start_window.get() && seg_num <= this.start_window.get() + sws) {
					outOfOrderAck.put(seg_num, true);
				}
			}
		}
	}


	// Sender's tcp handshake
	private void tcpHandshakeSender() {
		// Sender Initiate 3 Way Handshake
		byte[] TCP_packet = new byte[TCP_PACKET_LEN];
		int len_flag = 0;
		len_flag = (len_flag << 1) | 1;    // set S = 1
		len_flag = len_flag << 1;          // set F = 0
		len_flag = len_flag << 1;          // set A = 0
		ByteBuffer.wrap(TCP_packet).putInt(0, this.seq_num);
		ByteBuffer.wrap(TCP_packet).putLong(8, System.nanoTime());
		ByteBuffer.wrap(TCP_packet).putInt(16, len_flag);
		
		try {
			DatagramPacket UDP_packet = new DatagramPacket(TCP_packet, TCP_packet.length,
				remote_ip, remote_port);
			socket.send(UDP_packet);
			Util.outputSegmentInfo(true, Util.TCPGetTime(TCP_packet), true, false, false, false, this.seq_num, 0, 0);
			this.seq_num ++;
		} catch (Exception e) { e.printStackTrace(); }

		// sender receives TCP initial ack
		try {
			while(true) {
				byte[] data = new byte[TCP_PACKET_LEN];
				DatagramPacket packet = new DatagramPacket(data, data.length);
				socket.receive(packet);
				byte[] packet_data = packet.getData();
				Util.outputSegmentInfo(false, Util.TCPGetTime(packet_data), 
					Util.TCPGetSYN(packet_data), Util.TCPGetFIN(packet_data), Util.TCPGetACK(packet_data),
					false, Util.TCPGetSeqNum(packet_data), 0, Util.TCPGetAckNum(packet_data));
				
				if(Util.TCPGetSYN(packet_data) && Util.TCPGetACK(packet_data)) { 
					// Sender Concludes 3 Way handshake 
					byte[] ack_data = new byte[TCP_PACKET_LEN];
					int seq_num_rec = Util.TCPGetSeqNum(packet_data) + 1;
					int len_flag1 = 0;
					len_flag1 = len_flag1 << 2;
					len_flag1 = (len_flag1 << 1) | 1;
					ByteBuffer.wrap(ack_data).putInt(0, this.seq_num);
					ByteBuffer.wrap(ack_data).putInt(4, seq_num_rec);    // ack num
					ByteBuffer.wrap(ack_data).putLong(8, System.nanoTime());
					ByteBuffer.wrap(ack_data).putInt(16, len_flag1);
					DatagramPacket UDP_packet = new DatagramPacket(ack_data, ack_data.length,
						remote_ip, remote_port);
					socket.send(UDP_packet);
					Util.outputSegmentInfo(true, Util.TCPGetTime(ack_data), false, false, true, false, this.seq_num, 0, seq_num_rec);
					// this.seq_num ++;
					break;
				}
			}
		} catch (Exception e) { e.printStackTrace(); }
	}


	// Sender's tcp teardown
	private void tcpTeardownSender() {
		// sender sends fin to receiver
		byte[] Fin_TCP = new byte[TCP_PACKET_LEN];
		int len_flag = 0;
		len_flag = len_flag << 1;
		len_flag = (len_flag << 1) | 1;
		len_flag = len_flag << 1;
		long time = System.nanoTime();
		ByteBuffer.wrap(Fin_TCP).putInt(0, seq_num);
		ByteBuffer.wrap(Fin_TCP).putInt(4, 1);
		ByteBuffer.wrap(Fin_TCP).putLong(8, time);
		ByteBuffer.wrap(Fin_TCP).putInt(16, len_flag);
		try {
			DatagramPacket UDP_packet = new DatagramPacket(Fin_TCP, Fin_TCP.length, remote_ip, remote_port);
			socket.send(UDP_packet);
			Util.outputSegmentInfo(true, time, false, true, false, false, seq_num, 0, 1);
		} catch (Exception e) { e.printStackTrace(); }
		
		// sender receive fin ack from receiver
		while(true) {
			byte[] Fin_Ack_TCP = new byte[TCP_PACKET_LEN];
			DatagramPacket packet = new DatagramPacket(Fin_Ack_TCP, Fin_Ack_TCP.length);
			byte[] packetData = packet.getData();

			try {	socket.receive(packet); } 
			catch (Exception e) { e.printStackTrace(); }
			boolean A = Util.TCPGetACK(packetData);
			boolean F = Util.TCPGetFIN(packetData);

			Util.outputSegmentInfo(false, Util.TCPGetTime(packetData), Util.TCPGetSYN(packetData), 
				Util.TCPGetFIN(packetData), Util.TCPGetACK(packetData), false, Util.TCPGetSeqNum(packetData),
				0, Util.TCPGetAckNum(packetData));
		
			if(A && F) {
				int seq = Util.TCPGetSeqNum(packet.getData());
				int ack = Util.TCPGetAckNum(packet.getData());

				// sender sends final ack to receiver
				byte[] Ack_TCP = new byte[TCP_PACKET_LEN];
				int len_flag2 = 0;
				len_flag2 = (len_flag2 << 1) | 1;
				time = System.nanoTime();
				ByteBuffer.wrap(Ack_TCP).putInt(0, ack);
				ByteBuffer.wrap(Ack_TCP).putInt(4, seq + 1);
				ByteBuffer.wrap(Ack_TCP).putLong(8, time);
				ByteBuffer.wrap(Ack_TCP).putInt(16, len_flag2);
				try {
					DatagramPacket UDP_packet2 = new DatagramPacket(Ack_TCP, Ack_TCP.length, remote_ip, remote_port);
					socket.send(UDP_packet2);
					Util.outputSegmentInfo(true, time, false, false, true, false, ack, 0, seq + 1);
				} catch (Exception e) { e.printStackTrace(); }
				return;
			}
		}

		//Util.outputSegmentInfo(false, Util.TCPGetTime(packetData), Util.TCPGetSYN(packetData), 
		//	Util.TCPGetFIN(packetData), Util.TCPGetACK(packetData), false, Util.TCPGetSeqNum(packetData),
		//	0, Util.TCPGetAckNum(packetData));
	}


	// Get 1 segment from file to send
	private byte[] getData() {
		byte[] data = new byte[mtu];
		try {
			int bytesRead = fis.read(data);
			if (bytesRead == -1) {
				System.out.println("Reached EOF");
			} 
			file_pos += bytesRead;
			if (bytesRead < mtu) {
				byte[] trimmed = new byte[bytesRead];
				System.arraycopy(data, 0, trimmed, 0, bytesRead);
				return trimmed;
			}
		} catch (Exception e) {
			System.out.println("Problem with reading byte");
		}
		return data;
	}


	// Create generic TCP byte[] with data
	private byte[] createGenTCP(byte[] data) {
		int data_length = data.length;
		
		byte[] TCP_packet = new byte[data.length + TCP_PACKET_LEN];
		ByteBuffer.wrap(TCP_packet).putInt(0, seq_num);            // seq num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putInt(4, 0);                  // ack num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putLong(8, System.nanoTime()); // timestamp (8 bytes)

		data_length = (data_length << 3);
		ByteBuffer.wrap(TCP_packet).putInt(16, data_length);

		TCP_packet[20] = 0;                                        // All zeroes (2 bytes)
		TCP_packet[21] = 0;
		ByteBuffer.wrap(TCP_packet).putShort(22, Util.checksum(data));  // checksum (2 bytes)
		System.arraycopy(data, 0, TCP_packet, 24, data.length);    // data 

		return TCP_packet;
	}

}




// debugging
// Print byte[] in terms of bits
/*for (byte b : tcp) {
	System.out.print(Integer.toBinaryString((b & 0xFF) + 0x100).substring(1) + " ");
}*/


