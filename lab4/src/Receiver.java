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
			if(F) {

			} else { sendAck(); }
		}
	}


	// send Ack 
	private void sendAck() {
		
	}

}




/*
System.out.println("\nReceived Packet");
System.out.println("Seq: " + seq_num);
System.out.println("Ack: " + ack_num);
System.out.println("Timestamp: " + timestamp);
System.out.println("Length: " + length);
System.out.println("SYN: " + S + " FIN: " + F + " ACK: " + A);
System.out.println("Data: " + new String(payload));
*/

