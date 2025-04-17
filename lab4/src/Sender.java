import java.io.FileInputStream;
import java.io.File;

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
			byte[] data = getData();
			
			// debugging
			// System.out.println("should be data");
			// System.out.println(new String(data));

			// Create datagram packet
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


}
