

public class TCPend {
	public static void main(String[] args) {
		// 12 args = sender
		// 8 args = receiver
		if(args.length == 12) {
			senderSetup(args);
		} else if(args.length == 8) {
			receiverSetup(args);
		} else {
			System.out.println("Incorrect Number of Args");
		}
	}

	public static void senderSetup(String[] args) {
		int port = Integer.parseInt(args[1]);
		String remote_ip = args[3];
		int remote_port = Integer.parseInt(args[5]);
		String filename = args[7];
		int mtu = Integer.parseInt(args[9]);
		int sws = Integer.parseInt(args[11]);

		Sender _sender = new Sender(port, remote_ip, remote_port, filename, mtu, sws);
	}

	public static void receiverSetup(String[] args) {
		int port = Integer.parseInt(args[1]);
		int mtu = Integer.parseInt(args[3]);
		int sws = Integer.parseInt(args[5]);
		String filename = args[7];

		Receiver _receiver = new Receiver(port, mtu, sws, filename);
	}
}
