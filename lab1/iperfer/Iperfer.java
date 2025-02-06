import java.util.*;
import java.io.*;
import java.net.*;

public class Iperfer {

	public static void client_side(String hostname, int port, int time) {
		// packet to send
		byte[] byteArray = new byte[1000];
		try (
			Socket iperfersock = new Socket(hostname, port);
			OutputStream out = iperfersock.getOutputStream();
		) {
		
			// set start and end time 
			long startTime = System.currentTimeMillis();
			long endTime = startTime + 1000 * time;
			int KBsent = 0;

			// keep sending data
			while(System.currentTimeMillis() < endTime) {
				out.write(byteArray);
				KBsent++;
			}

			// Calculate the rate
			double rate = (KBsent/125) / time;

			// Print statistics
			System.out.println("sent=" + KBsent + " KB rate=" + rate + " Mbps");
		} catch (Exception e) {
			System.out.println("Exception thrown");
			return;
		}
	}

	public static void server_side(int port) {
		try (
			ServerSocket serversock = new ServerSocket(port);
			Socket clientsock = serversock.accept();
			InputStream in = clientsock.getInputStream();
		) {
			
			// start time
			long startTime = System.currentTimeMillis();
			// packet to read
			byte[] byteArray = new byte[1000];
			
			// data
			int KBreceived = 0;
			int received = 0;
			int bytesRead = 0;

			// Keep reading while data is being sent
			while(bytesRead != -1) {
				bytesRead = in.read(byteArray, 0, 1000);
				received += bytesRead;
			}
			KBreceived = received/1000;

			// Print statistics
			long endTime = System.currentTimeMillis();
			double rate = (KBreceived/125) / ((endTime - startTime) / 1000);
			System.out.println("received=" + KBreceived + " KB rate=" + rate + " Mbps");

		} catch (Exception e) {
			System.out.println("Exception thrown");
			return;
		}
	}

	public static boolean argNumCheck(int num, int length) {
		if(length != num) {
			System.out.println("Error: missing or additional arguments");
			return false;
		}
		return true;
	}

	public static boolean portCheck(int port) {
		if(port < 1024 || port > 65535) { 
			System.out.println("Error: port number must be in the range 1024 to 65535"); 
			return false;
		}
		return true;
	}

	public static void main(String[] args) {
		// Check that server or client is specified
		if(args.length == 0) { System.out.println("Error: missing or additional arguments"); }

		// check server or client
		boolean client = false;
		if(args[0].equals("-c")) {
			client = true;
		} else if(args[0].equals("-s")) {
			client = false;
		}

		// Client
		if(client) {
			// Check Args
			if(!argNumCheck(7, args.length)) { return; }

			// Set Args
			String hostname = args[2];
			int port = Integer.parseInt(args[4]);
			if(!portCheck(port)) { return; }
			int time = Integer.parseInt(args[6]);
		
			client_side(hostname, port, time);
		}

		// Server
		else {
			if(!argNumCheck(3, args.length)) { return; }

			int port = Integer.parseInt(args[2]);
			if(!portCheck(port)) { return; }

			server_side(port);
		}

	}
}
