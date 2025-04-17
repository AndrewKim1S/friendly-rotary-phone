import java.io.FileInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

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
		} catch (Exception e) {
			System.out.println("Problem with FileInputStream or File");
			e.printStackTrace();
		}

		sendSegment();
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
		ByteBuffer.wrap(TCP_packet).putShort(22, checksum(data));  // checksum (2 bytes)
		System.arraycopy(data, 0, TCP_packet, 24, data.length);    // data 

		return TCP_packet;
	}

	// Create checksum
	private short checksum(byte[] data) {
		// Pad the data with zero if length is odd
		if (data.length % 2 != 0) {
			byte[] paddedData = new byte[data.length + 1];
			System.arraycopy(data, 0, paddedData, 0, data.length);
			paddedData[data.length] = 0x00;
			data = paddedData;
		}
		int sum = 0;
		// Process each 16-bit segment
		for (int i = 0; i < data.length; i += 2) {
			// Combine two bytes into a 16-bit unsigned value
			int segment = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
			// Add to the sum
			sum += segment;
			// Handle carry-over (wraparound)
			while ((sum >> 16) != 0) {
					sum = (sum & 0xFFFF) + (sum >> 16);
			}
		}
		// One's complement (bitwise NOT)
		short checksum = (short) ~(sum & 0xFFFF);
		return checksum;
	}

}




// debugging
// Print byte[] in terms of bits
/*for (byte b : tcp) {
	System.out.print(Integer.toBinaryString((b & 0xFF) + 0x100).substring(1) + " ");
}*/

// Print byte[] in terms of string
// System.out.println(new String(data));


