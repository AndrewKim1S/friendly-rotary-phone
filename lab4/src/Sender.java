import java.io.FileInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.DatagramSocket;


public class Sender {
	int port;
	String remote_ip;
	int remote_port;
	String filename;
	int mtu;
	int sws;

	int seq_num;
	int start_window; // last byte sent (over segments)
	int file_pos; // position in file we are getting segments from (over bytes)

	FileInputStream fis; // will read input file per byte 
	File file;

	DatagramSocket socket;

	public static final int MAX_RETRANSMISSIONS = 16;
	public static final int TCP_PACKET_LEN = 24;


	public Sender(int port, String remote_ip, int remote_port, String filename, int mtu, int sws) {
		this.port = port;
		this.remote_ip = remote_ip;
		this.remote_port = remote_port;
		this.filename = filename;
		this.mtu = mtu;
		this.sws = sws;

		this.seq_num = 0;
		this.start_window = 0;
		this.file_pos = 0;

		try {
			fis = new FileInputStream(this.filename);
			file = new File(this.filename);
			socket = new DatagramSocket(port);
		} catch (Exception e) { e.printStackTrace(); }

		tcpHandshakeSender();
		sendSegment();
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
				InetAddress.getByName(remote_ip), remote_port);
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
						InetAddress.getByName(remote_ip), remote_port);
					socket.send(UDP_packet);
					Util.outputSegmentInfo(true, Util.TCPGetTime(ack_data), false, false, true, false, this.seq_num, 0, seq_num_rec);
					this.seq_num ++;
					break;
				}
			}
		} catch (Exception e) { e.printStackTrace(); }
	}


	// Send Segments
	private void sendSegment() {
		// As long as there is still info to be sent, continue sending
		while(file_pos < file.length()) {
			// Sender waits 
			while(file_pos == start_window + sws) {}
			// Once window has space to send more

			byte[] data = getData();          // get segment (parse from input file)
			byte[] tcp = createGenTCP(data);  // create tcp packet
			// create udp (datagram packet)
			try{
				DatagramPacket UDP_packet = new DatagramPacket(tcp, tcp.length, InetAddress.getByName(remote_ip), remote_port);
				socket.send(UDP_packet);
			} catch (Exception e) { e.printStackTrace(); }

			Util.outputSegmentInfo(true, Util.TCPGetTime(tcp), false, false, false, true, seq_num, data.length, 0);

			seq_num++;
		}
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


