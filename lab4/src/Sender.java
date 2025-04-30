import java.io.FileInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.Comparator;
import java.util.Iterator;


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

	// HashMap<Integer, Integer> seg_numAck = new HashMap<>();

	FileInputStream fis; // will read input file per byte 
	File file;

	DatagramSocket socket;

	AtomicBoolean teardownStarted;

	long timeout;  // nano
	long ERTT;
	long EDEV;

	int bytesTransferred;
	int packetsSent;
	int packetsReceived;
	int numRetransmissions;
	int numDupAcks;


	// Retransmission variables
	public class RetransmitTask {
		public long scheduledTime;
		public int numberOfRetransmissions;
		public int seq_num_retransmit;
		public int seg_num_retransmit;
		private byte[] data;

		public RetransmitTask(long scheduledTime, int seq_num_retransmit, int seg_num_retransmit, byte[] data) {
			this.scheduledTime = scheduledTime;
			this.numberOfRetransmissions = 0;
			this.seq_num_retransmit = seq_num_retransmit;
			this.seg_num_retransmit = seg_num_retransmit;
			this.data = data;
		}

		public void run() {
			byte[] tcp = createGenTCP(data, this.seq_num_retransmit);  // create tcp packet
			try{
				DatagramPacket UDP_packet = new DatagramPacket(tcp, tcp.length, remote_ip, remote_port);
				socket.send(UDP_packet);
			} catch (Exception e) { e.printStackTrace(); }
			Util.outputSegmentInfo(true, Util.TCPGetTime(tcp), false, false, false, true, this.seq_num_retransmit, data.length, 1);
		}
	}
	// used by threadsend and threadretran
	ConcurrentSkipListSet<RetransmitTask> toRetransmitSet = new ConcurrentSkipListSet<RetransmitTask>(
		Comparator.comparingLong(t -> t.scheduledTime)
	);


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

		this.timeout = 5;

		this.bytesTransferred = 0;
		this.packetsSent = 0;
		this.packetsReceived = 0;
		this.numRetransmissions = 0;
		this.numDupAcks = 0;

		try {
			this.remote_ip = InetAddress.getByName(remote_ip);
		} catch(Exception e) { e.printStackTrace(); }

		try {
			fis = new FileInputStream(this.filename);
			file = new File(this.filename);
			socket = new DatagramSocket(port);
			socket.setSoTimeout(5000);
		} catch (Exception e) {} //e.printStackTrace(); }

		tcpHandshakeSender();

		// Create threads
		Thread threadSend = new Thread(() -> sendSegment());
		Thread threadRec = new Thread(() -> recAck());
		Thread threadRetransmit = new Thread(() -> retransmitFunction());

		threadRetransmit.start();
		threadSend.start();
		threadRec.start();
		try {
			threadSend.join();
			threadRec.join();
			threadRetransmit.join();
		} catch (Exception e) {	e.printStackTrace(); }

		// System.out.println("\nALL INFORMATION EXCHANGED\n");
		tcpTeardownSender();
		printMetrics();
	}


	// Send Segments
	private void sendSegment() {
		// As long as there is still info to be sent, continue sending
		while(this.file_pos < this.file.length()) {
			// Sender waits 
			while(this.seg_send_ind.get() >= this.start_window.get() + this.sws) {  }
			byte[] data = getData();          // get segment (parse from input file)
			byte[] tcp = createGenTCP(data, this.seq_num);  // create tcp packet
			
			// Set retransmission
			toRetransmitSet.add(new RetransmitTask(System.nanoTime() + this.timeout, this.seq_num, this.seg_send_ind.get(), data));

			// Map seq_num of packet to seg_num
			this.seq_seg.put(this.seq_num + data.length, this.seg_send_ind.get());
	
			// create udp (datagram packet) and send
			try{
				DatagramPacket UDP_packet = new DatagramPacket(tcp, tcp.length, this.remote_ip, this.remote_port);
				socket.send(UDP_packet);
				this.packetsSent++;
			} catch (Exception e) { e.printStackTrace(); }
			Util.outputSegmentInfo(true, Util.TCPGetTime(tcp), false, false, false, true, this.seq_num, data.length, 1);

			// increment to the next sequence number and segment number
			this.seq_num += data.length;
			this.seg_send_ind.incrementAndGet();
		}
		teardownStarted.set(true);
		// System.out.println("\nSENDER SEN THREAD DONE!\n");
	}


	// Receive acks from the receiver
	private void recAck() {
		boolean all_received = false;
		byte[] data = new byte[TCP_PACKET_LEN + mtu];
		DatagramPacket packet = new DatagramPacket(data, data.length);
		while(!all_received) { // sender has sent everything 
			try {	
				socket.receive(packet);
				this.packetsReceived++;
			} 
			catch (Exception e) { 
				if(teardownStarted.get() && this.start_window.get() == this.seg_send_ind.get()) { all_received = true; }
				continue;
			}

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

			// calculate timeout
			long SRTT = System.nanoTime() - timestamp;
			this.ERTT = (long)(0.875 * this.ERTT) + (long)((1-0.875) * SRTT);
			this.EDEV = (long)(0.75 * this.EDEV) + (long)((1 - 0.75) * Math.abs(SRTT - this.ERTT));
			this.timeout = this.ERTT + 4 * this.EDEV;

			Util.outputSegmentInfo(false, timestamp, S, F, A, length > 0, seq, length, ack_num);

			// TODO keep track of same acknowledgements 
			int seg_num = 0; // the seq_num the receiver is acknowledging
			if(seq_seg.containsKey(ack_num)) {
				seg_num = seq_seg.get(ack_num);
			} 
			// This means that we received multiple acks or seq_seg does not contain ack_num
			else {
				this.numDupAcks++;
			}

			this.bytesTransferred = Math.max(this.bytesTransferred, ack_num-1);

			// Handle in order ACKs
			if(seg_num == this.start_window.get()) {
				this.start_window.incrementAndGet();
				seq_seg.remove(ack_num);
				// Cancel retransmission tasks
				Iterator<RetransmitTask> it = toRetransmitSet.iterator();
				while(it.hasNext()) {
					RetransmitTask t = it.next();
					if(t.seg_num_retransmit <= seg_num) {
						it.remove();
					}
				}
			}
			
			// Handle out of order Acks
			else if(seg_num > this.start_window.get()) {
				// System.out.println("------OUT OF ORDER ACK RECEIVED!---------");
				this.start_window.set(seg_num+1);
				// Cancel retransmission tasks
				Iterator<RetransmitTask> it = toRetransmitSet.iterator();
				while(it.hasNext()) {
					RetransmitTask t = it.next();
					if(t.seg_num_retransmit <= seg_num) {
						it.remove();
					}
				}
			}

			// TODO Handle 3 duplicate acks then retransmit

			if(teardownStarted.get() && this.start_window.get() == this.seg_send_ind.get()) { all_received = true; }
			// System.out.println("start window = " + this.start_window.get());
		}
		// Clear the buffer for retransmissions to address concurrency issues
		toRetransmitSet.clear();
		// System.out.println("\nSENDER REC THREAD DONE!\n");
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
		
		DatagramPacket UDP_packet = new DatagramPacket(TCP_packet, TCP_packet.length,
			remote_ip, remote_port);
		try {
			socket.send(UDP_packet);
			this.packetsSent++;
			Util.outputSegmentInfo(true, Util.TCPGetTime(TCP_packet), true, false, false, false, this.seq_num, 0, 0);
			this.seq_num ++;
		} catch (Exception e) { e.printStackTrace(); }

		// sender receives TCP initial ack
		try {
			while(true) {
				byte[] data = new byte[TCP_PACKET_LEN];
				DatagramPacket packet = new DatagramPacket(data, data.length);

				try {
					socket.receive(packet);
					this.packetsReceived++;
				} catch (SocketTimeoutException e) {
					socket.send(UDP_packet);
					this.packetsSent++;
					Util.outputSegmentInfo(true, Util.TCPGetTime(TCP_packet), true, false, false, false, this.seq_num-1, 0, 0);
					continue;
				}

				byte[] packet_data = packet.getData();

				// Calculate timeout
				this.ERTT = (System.nanoTime() - Util.TCPGetTime(packet_data));
				this.EDEV = 0;
				this.timeout = 2 * this.ERTT;

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
					DatagramPacket UDP_packet2 = new DatagramPacket(ack_data, ack_data.length,
						remote_ip, remote_port);
					socket.send(UDP_packet2);
					this.packetsSent++;
					Util.outputSegmentInfo(true, Util.TCPGetTime(ack_data), false, false, true, false, this.seq_num, 0, seq_num_rec);
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
		DatagramPacket UDP_packet = new DatagramPacket(Fin_TCP, Fin_TCP.length, remote_ip, remote_port);
		try {
			socket.send(UDP_packet);
			this.packetsSent++;
			Util.outputSegmentInfo(true, time, false, true, false, false, seq_num, 0, 1);
		} catch (Exception e) { e.printStackTrace(); }
		
		// sender receive fin ack from receiver
		int hardlimit = 0;
		while(hardlimit < 8) {
			byte[] Fin_Ack_TCP = new byte[TCP_PACKET_LEN];
			DatagramPacket packet = new DatagramPacket(Fin_Ack_TCP, Fin_Ack_TCP.length);
			byte[] packetData = packet.getData();

			try {
				socket.receive(packet); 
				this.packetsReceived++;
			} 
			catch(SocketTimeoutException e) {
				try {
					socket.send(UDP_packet);
					this.packetsSent++;
					hardlimit++;
					continue;
				} catch (Exception ee) { ee.printStackTrace(); }
			} catch (Exception e) { e.printStackTrace(); }
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
					this.packetsSent++;
					Util.outputSegmentInfo(true, time, false, false, true, false, ack, 0, seq + 1);
				} catch (Exception e) { e.printStackTrace(); }
				return;
			}
		}
	}


	// Retransmission thread function
	private void retransmitFunction() {
		// Get the next task to retransmit from toRetransmitSet 
		while(!teardownStarted.get() || !toRetransmitSet.isEmpty()) {
			if(toRetransmitSet.isEmpty()) { continue; }
			if(teardownStarted.get() && 
				this.start_window.get() == this.seg_send_ind.get()) { 
				toRetransmitSet.clear(); 
				continue;
			}
			RetransmitTask t = toRetransmitSet.pollFirst();
			long currTime = System.nanoTime();

			if(t == null) { continue; }
			if(t.scheduledTime <= currTime) {
				// TODO tell the Sender rec thread to forget about it if max number of retransmissions
				/*if(t.numberOfRetransmissions == MAX_RETRANSMISSIONS) {
					System.out.println("\nMAX RETRANSMISSIONS!\n");
					this.start_window.incrementAndGet();
					this.seg_send_ind.set(this.seg_send_ind.get()-1);
					continue;
				}*/
				t.run();
				t.numberOfRetransmissions++;
				this.numRetransmissions++;
				this.packetsSent++;

				// After retransmission, reschedule the scheduledTime
				t.scheduledTime += this.timeout;
				toRetransmitSet.add(t);
			} 
			// If we not timeout yet, don't forget to add it back into the buffer
			else {
				toRetransmitSet.add(t);
			}
		}
		// System.out.println("RETRANSMISSION THREAD COMPLETE!");
	}


	// Get 1 segment from file to send
	private byte[] getData() {
		byte[] data = new byte[mtu];
		try {
			int bytesRead = fis.read(data);
			if (bytesRead == -1) {
				// System.out.println("Reached EOF");
			} 
			file_pos += bytesRead;
			if (bytesRead < mtu) {
				byte[] trimmed = new byte[bytesRead];
				System.arraycopy(data, 0, trimmed, 0, bytesRead);
				return trimmed;
			}
		} catch (Exception e) {
			// System.out.println("Problem with reading byte");
		}
		return data;
	}


	// Create generic TCP byte[] with data
	private byte[] createGenTCP(byte[] data, int seq) {
		int data_length = data.length;
		
		byte[] TCP_packet = new byte[data.length + TCP_PACKET_LEN];
		ByteBuffer.wrap(TCP_packet).putInt(0, seq);            // seq num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putInt(4, 1);                  // ack num (4 bytes)
		ByteBuffer.wrap(TCP_packet).putLong(8, System.nanoTime()); // timestamp (8 bytes)

		data_length = (data_length << 3);
		ByteBuffer.wrap(TCP_packet).putInt(16, data_length);

		TCP_packet[20] = 0;                                        // All zeroes (2 bytes)
		TCP_packet[21] = 0;
		ByteBuffer.wrap(TCP_packet).putShort(22, Util.checksum(data));  // checksum (2 bytes)
		System.arraycopy(data, 0, TCP_packet, 24, data.length);    // data 

		return TCP_packet;
	}

	// Print metrics
	private void printMetrics() {
		System.out.println("\nAmount of Data Transferred: " + this.bytesTransferred);
		System.out.println("Number of Packets Sent: " + this.packetsSent);
		System.out.println("Number of Packets Received: " + this.packetsReceived);
		System.out.println("Number of Out-Of-Sequence Packets discarded: 0");
		System.out.println("Number of Packets discarded due to incorrect checksum: 0");
		System.out.println("Number of Retransmissions: " + this.numRetransmissions);
		System.out.println("Number of Duplicate Acks: " + this.numDupAcks);
	}
}


