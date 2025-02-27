
package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.HashMap;
import net.floodlightcontroller.packet.MACAddress;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	
	private HashMap<MACAddress, Iface> MAC_Inter;
	private HashMap<MACAddress, Long> MAC_Time;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		MAC_Inter = new HashMap<MACAddress, Iface>();
		MAC_Time = new HashMap<MACAddress, Long>();
	}


	// Check if there is a timeout
	private boolean timeout(MACAddress m) {
		long last_updated = MAC_Time.get(m);
		long curr_time = System.currentTimeMillis();
		return (curr_time - last_updated) >= 15000;
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
		
		MACAddress src_mac = etherPacket.getSourceMAC();
		MACAddress dst_mac = etherPacket.getDestinationMAC();
		
		// src mac is in table
		if(!MAC_Inter.containsKey(src_mac) || timeout(src_mac)) {
			MAC_Inter.put(src_mac, inIface);
			MAC_Time.put(src_mac, System.currentTimeMillis());
		}

		// dst mac is in table
		if(MAC_Inter.containsKey(dst_mac) && !timeout(dst_mac)) {
			sendPacket(etherPacket, MAC_Inter.get(dst_mac));
		}
		// flood/broadcast
		else {
			for(Iface iface : interfaces.values()) {
				if(!iface.equals(inIface)) {
					sendPacket(etherPacket, iface);
				}
			}
		}

		// System.out.println("------------------------------------------------");
	}
}
