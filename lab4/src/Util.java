import java.nio.ByteBuffer;


public final class Util {

	// Utility Methods for parsing TCP packet 
	public static int TCPGetSeqNum(byte[] data) { return ByteBuffer.wrap(data).getInt(0); }
	public static int TCPGetAckNum(byte[] data) { return ByteBuffer.wrap(data).getInt(4); }
	public static long TCPGetTime(byte[] data) { return ByteBuffer.wrap(data).getLong(8); }
	public static int TCPGetLen(byte[] data) { 
		int len = ByteBuffer.wrap(data).getInt(16); 
		len = len >>> 3;
		return len;
	}
	public static boolean TCPGetSYN(byte[] data) {
		int len = ByteBuffer.wrap(data).getInt(16); 
		return (len & 1) != 0;
	}
	public static boolean TCPGetFIN(byte[] data) {
		int len = ByteBuffer.wrap(data).getInt(16); 
		len = len >>> 1;
		return (len & 1) != 0;
	}
	public static boolean TCPGetACK(byte[] data) {
		int len = ByteBuffer.wrap(data).getInt(16); 
		len = len >>> 1;
		len = len >>> 1;
		return (len & 1) != 0;
	}
	public static short TCPGetChecksum(byte[] data) {
		short checksum = ByteBuffer.wrap(data).getShort(22);
		return checksum;
	}
	public static byte[] TCPGetData(byte[] data) {
		int length = TCPGetLen(data);
		byte[] payload = new byte[length];
		System.arraycopy(data, 24, payload, 0, length);
		return payload;
	}


	// Printing packets snd and rcv
	public static void outputSegmentInfo(boolean send, long time, boolean S, boolean F, boolean A, boolean D,
		int seq_num, int num_bytes, int ack_num) {
		String output = "";
		if(send) { output += "snd "; }
		else { output += "rcv "; }
		output += time + " ";
		if(S) { output += "S "; }
		else { output += "- "; }
		if(A) { output += "A "; }
		else { output += "- "; }
		if(F) { output += "F "; }
		else { output += "- "; }
		if(D) { output += "D "; }
		else { output += "- "; }
		output += seq_num + " ";
		output += num_bytes + " ";
		output += ack_num;
		System.out.println(output);
	}

	// Function to calculate checksum
	public static short checksum(byte[] data) {
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


