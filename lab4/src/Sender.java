import java.io.FileInputStream;

public class Sender {
	int port;
	String remote_ip;
	int remote_port;
	String filename;
	int mtu;
	int sws;

	int seq_num;
	int start_window; // last byte sent also 
	int file_byte_pos; // position in file we are getting segments from

	FileInputStream fis; // will read input file per byte 

	public Sender(int port, String remote_ip, int remote_port, String filname, int mtu, int sws) {
		this.port = port;
		this.remote_ip = remote_ip;
		this.remote_port = remote_port;
		this.filename = filename;
		this.mtu = mtu;
		this.sws = sws;

		this.seq_num = 0;
		this.start_window = 0;

		try {
			fis = new FileInputStream(filename);
		} catch (Exception e) {
			System.out.println("Problem with FileInputStream");
		}
	}

	// Send Segments
	private void sendSegment() {

	}

	// Get 1 segment from file to send
	private byte[] getData() {
		byte[] data = new byte[mtu];
		try {
			int bytesRead = fis.read(data);
			if (bytesRead == -1) {
				System.out.println("Reached EOF");
			} else if (bytesRead < mtu) {
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
