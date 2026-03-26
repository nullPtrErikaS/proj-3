package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;

import java.util.Map;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	

	// Forwarding Table
	private ForwardingTable forwardingTable;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.forwardingTable = new ForwardingTable();
	}

	public ForwardingTable getRForwardingTable(){
		return this.forwardingTable;
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

		MACAddress source = etherPacket.getSourceMAC();
		MACAddress destination = etherPacket.getDestinationMAC();

		this.forwardingTable.learn(source, inIface);

		Iface outIface = this.forwardingTable.lookup(destination);
		if (null != outIface && outIface != inIface)
		{
			sendPacket(etherPacket, outIface);
			return;
		}

		Map<String, Iface> allInterfaces = getInterfaces();
		for (Iface iface : allInterfaces.values())
		{
			if (iface == inIface)
			{
				continue;
			}
			sendPacket(etherPacket, iface);
		}
	}
}
