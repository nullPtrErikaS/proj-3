package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.RIPv2;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/** RIPv2 handler for dynamic routing */
	private RIPv2Handler ripHandler;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 * @param logfile file to write log entries to
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.ripHandler = null;
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Start the RIP routing protocol
	 */
	public void startRIP()
	{
		if (this.ripHandler == null)
		{
			this.ripHandler = new RIPv2Handler(this);
			this.ripHandler.startRIP();
		}
	}

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

		if (Ethernet.TYPE_IPv4 != etherPacket.getEtherType())
		{ return; }

		IPv4 ipPacket = (IPv4)etherPacket.getPayload();

		short receivedChecksum = ipPacket.getChecksum();
		ipPacket.setChecksum((short)0);
		ipPacket.serialize();
		short computedChecksum = ipPacket.getChecksum();
		if (receivedChecksum != computedChecksum)
		{ return; }

		int ttl = ipPacket.getTtl() & 0xFF;
		if (ttl <= 1)
		{ return; }
		
		// Check if this is a RIP packet (UDP port 520)
		if (ipPacket.getProtocol() == IPv4.PROTOCOL_UDP)
		{
			UDP udpPacket = (UDP)ipPacket.getPayload();
			if (udpPacket != null && udpPacket.getDestinationPort() == (short)520)
			{
				// This is a RIP packet, pass it to the RIP handler
				if (this.ripHandler != null && udpPacket.getPayload() instanceof RIPv2)
				{
					RIPv2 ripPacket = (RIPv2)udpPacket.getPayload();
					this.ripHandler.handleRIPPacket(ripPacket, inIface, 
						ipPacket.getSourceAddress(), etherPacket.getSourceMACAddress());
				}
				return;
			}
		}

		ipPacket.setTtl((byte)(ttl - 1));
		ipPacket.setChecksum((short)0);

		int destinationIp = ipPacket.getDestinationAddress();
		for (Iface iface : this.interfaces.values())
		{
			if (iface.getIpAddress() == destinationIp)
			{ return; }
		}

		RouteEntry bestMatch = this.routeTable.lookup(destinationIp);
		if (null == bestMatch)
		{ return; }

		/* Never route a packet back out the same interface it arrived on. */
		if (bestMatch.getInterface() == inIface)
		{ return; }

		Iface outIface = bestMatch.getInterface();
		int nextHopIp = bestMatch.getGatewayAddress();
		if (0 == nextHopIp)
		{ nextHopIp = destinationIp; }

		ArpEntry arpEntry = this.arpCache.lookup(nextHopIp);
		if (null == arpEntry)
		{ return; }

		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
		sendPacket(etherPacket, outIface);
	}
	
	/**
	 * Clean up router resources
	 */
	@Override
	public void destroy()
	{
		if (this.ripHandler != null)
		{
			this.ripHandler.stop();
		}
		super.destroy();
	}
}
