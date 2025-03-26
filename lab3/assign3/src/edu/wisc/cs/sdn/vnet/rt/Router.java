package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		if(etherPacket.getEtherType() != Ethernet.TYPE_IPv4) { return; }

		// Verify Checksum
		IPv4 packet = (IPv4) (etherPacket.getPayload());
		short checksum = packet.getChecksum();
		packet.resetChecksum();
		byte[] serialized = packet.serialize();
		packet.deserialize(serialized, 0, serialized.length);
		if(checksum != packet.getChecksum()) { return; }

		// Verify TTL
		byte ttl = packet.getTtl();
		int new_ttl = ttl - 1;
		packet.setTtl((byte) new_ttl);
		if(new_ttl == 0) { return; }

		packet.resetChecksum();
		packet.serialize();

		// Check if packet matches exactly
		int dst_ip = packet.getDestinationAddress();
		for(Iface inter_ip : interfaces.values()) {
			if(inter_ip.getIpAddress() == dst_ip) { return; }
		}
		
		// find which interface to send from
		RouteEntry e = this.routeTable.lookup(dst_ip);
		if(e == null) { return; }


		// find next hop (ip addr for where e will send to)
		int nextHop;
		// directly reachable
		if(e.getGatewayAddress() == 0) { nextHop = dst_ip; }
		else { nextHop = e.getGatewayAddress(); }

		ArpEntry a = this.arpCache.lookup(nextHop);
		if(a == null) { return; }

		/*System.out.println(e.getInterface());
		System.out.println(e.getInterface().getMacAddress());
		System.out.println(e.getInterface().getMacAddress().toBytes());
		*/
		// random nullptr exception gaurd
		if(e.getInterface() == null || e.getInterface().getMacAddress() == null || e.getInterface().getMacAddress().toBytes() == null) { return; }
		etherPacket.setDestinationMACAddress(a.getMac().toBytes());
		etherPacket.setSourceMACAddress(e.getInterface().getMacAddress().toBytes());

		// System.out.println("src: " + a.getMac() + " dst: " + e.getInterface().getMacAddress());

		this.sendPacket(etherPacket, e.getInterface());
		/********************************************************************/
	}

	// Function to get directly reachable subnets via rotuer interfaces
	public void startRIP() {
		for(Iface iface : interfaces.values()) {
			int dst_ip = iface.getIpAddress();
			int mask = iface.getSubnetMask();
			int subnet = mask & dst_ip;
			this.routeTable.insert(subnet, 0, mask, iface);
		}

		// Debug
		System.out.println("Start RIP Route Table: ");
		System.out.println(this.routeTable);

		sendRIPRequest();

		// Set Timer 10s
		Timer timer = new Timer();
		TimerTask task = new TimerTask() {
			public void run() {
				sendRIPResponse();
			}
		};
		timer.scheduleAtFixedRate(task, 0, 10000);

	}

	// Function to send RIP request out of all router interfaces after initialization
	public void sendRIPRequest() {
		for(Iface iface : interfaces.values()) {
			// Setup the RIPv2 packet - Application Layer
			RIPv2 rip_pack = new RIPv2();
			int addr = iface.getIpAddress();
			int mask = iface.getSubnetMask();
			RIPv2Entry e = new RIPv2Entry(addr, mask, 16); // ?
			e.setNextHopAddress(0);
			rip_pack.addEntry(e);
			byte request = 1;
			rip_pack.setCommand(request);

			// Encapsulate into UDP packet - Transport Layer
			UDP udp_pack = new UDP();
			udp_pack.setPayload(rip_pack);
			udp_pack.setSourcePort(UDP.RIP_PORT);
			udp_pack.setDestinationPort(UDP.RIP_PORT);

			// IP Packet - Network Layer
			IPv4 ip_pack = new IPv4();
			ip_pack.setProtocol(IPv4.PROTOCOL_UDP);
			ip_pack.setDestinationAddress("244.0.0.9");
			ip_pack.setSourceAddress(addr);
			ip_pack.setPayload(udp_pack);

			// Ethernet Packet - Link Layer
			Ethernet eth_pack = new Ethernet();
			eth_pack.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
			eth_pack.setSourceMACAddress(iface.getMacAddress().toBytes());
			eth_pack.setPayload(ip_pack);

			// send packet
			System.out.println("Should send packet out of interface: " + iface);
			this.sendPacket(eth_pack, iface);
		}
	}

	public void sendRIPResponse() {
		for(Iface iface : interfaces.values()) {
			// Setup the RIPv2 packet - Application Layer
			RIPv2 rip_pack = new RIPv2();
			int addr = iface.getIpAddress();
			int mask = iface.getSubnetMask();

			// Loop through all entries in Routing Table
			// Add RIPv2Entry for each one
			for(RouteEntry e : routeTable.getEntries()) {
				RIPv2Entry entry = new RIPv2Entry(e.getDestinationAddress(), 
					e.getMaskAddress(), e.getMetric()); // ?
				entry.setNextHop(addr); // ?
				rip_pack.addEntry(entry);
			}

			byte response = 2;
			rip_pack.setCommand(response);

			// Encapsulate into UDP packet - Transport Layer
			UDP udp_pack = new UDP();
			udp_pack.setPayload(rip_pack);
			udp_pack.setSourcePort(UDP.RIP_PORT);
			udp_pack.setDestinationPort(UDP.RIP_PORT);

			// IP Packet - Network Layer
			IPv4 ip_pack = new IPv4();
			ip_pack.setProtocol(IPv4.PROTOCOL_UDP);
			ip_pack.setDestinationAddress("244.0.0.9");
			ip_pack.setSourceAddress(addr);
			ip_pack.setPayload(udp_pack);

			// Ethernet Packet - Link Layer
			Ethernet eth_pack = new Ethernet();
			eth_pack.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
			eth_pack.setSourceMACAddress(iface.getMacAddress().toBytes());
			eth_pack.setPayload(ip_pack);

			// send packet
			System.out.println("Should send packet out of interface: " + iface);
			this.sendPacket(eth_pack, iface);
		}
	}

}

