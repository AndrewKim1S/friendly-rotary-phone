package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.HashMap;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	

	// Class to hold Mac Addr to Interface & time Map 
	protected class MacInterMap {
		private HashMap<MACAddress, NameTimePair> map = new HashMap<MACAddress, NameTimePair>();
		
		public void insert(MACAddress m, String n, long t) {
			map.put(m, new NameTimePair(n,t));
		}

		public boolean containsMAC(MACAddress m) {
			return map.containsKey(m);
		}

		public String getName(MACAddress m) {
			return map.get(m).name;
		}

		public long getTime(MACAddress m) {
			return map.get(m).time;
		}

		// Helper pair class 
		private class NameTimePair {
			public String name;		// name of interface
			public long time;			// last updated

			public NameTimePair(String n, long t) {
				this.name = n;
				this.time = t;
			}
		}
	}
	
	// Define table for MAC learning
	public MacInterMap table;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		table = new MacInterMap();
	}


	// Custom function to check for timeout in table
	private boolean timeout(MACAddress m) {
		long last_updated = table.getTime(src_mac);
		long curr_time = System.currentTimeMillis();

		// Check Timeout && maybe inter == logged
		return (curr_time - last_updated) / 1000.0 >= 15;
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
		
		//Check if mac addr src is in table 
			// if yes check time - timeout && check if inter == to the one logged
			// else do learning
		// Check if mac addr dst is in table 
			// if yes Check timeout send packet
			// else broadcast/flood 
		
		MACAddress src_mac = etherPacket.getSourceMAC();
		MACAddress dst_mac = etherPacket.getDestinationMAC();
		String src_inter = inIface.getName();

		// Check if the MAC Addr src is in table 
		if(table.containsMAC(src_mac)) {
			// Check Timeout && maybe inter == logged
			if(timeout(src_mac)) {
				table.insert(src_mac, src_inter, curr_time);
			}
		} 
		// do learning
		else {
			table.insert(src_mac, src_inter, System.currentTimeMillis());
		}

		// Check if mac dst is in table && timeout
		if(table.containsMAC(dst_mac) && !timeout(dst_mac)) {
			// send packet
			Iface toIface = interfaces.getInterface(table.getName(dst_mac));
			sendPacket(etherPacket, toIface);
		}
		// broadcast/flood the packet
		else {
			for(String inter_name : interfaces.keySet()) {
				Iface toIface = interfaces.getInterface(inter_name);
				sendPacket(etherPacket, toIface);
			}
		}
	}
}
