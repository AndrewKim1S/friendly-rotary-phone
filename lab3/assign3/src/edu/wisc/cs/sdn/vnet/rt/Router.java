package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;

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
}
