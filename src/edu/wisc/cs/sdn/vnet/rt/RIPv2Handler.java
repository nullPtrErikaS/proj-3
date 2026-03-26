package edu.wisc.cs.sdn.vnet.rt;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

/**
 * RIPv2 distance vector routing handler
 * @author CS640 Student
 */
public class RIPv2Handler 
{
	/** The router that this RIP handler belongs to */
	private Router router;
	
	/** Map to track the last time each route was updated (in milliseconds) */
	private Map<RouteKey, Long> routeTimestamps;
	
	/** Timer for periodic RIP responses */
	private Timer timer;
	
	/** RIP multicast IP address */
	private static final int RIP_MULTICAST_IP = IPv4.toIPv4Address("224.0.0.9");
	
	/** RIP broadcast MAC address */
	private static final byte[] RIP_BROADCAST_MAC = new byte[] {
		(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF
	};
	
	/** RIP request timeout in milliseconds (30 seconds) */
	private static final long RIP_TIMEOUT = 30000;
	
	/** RIP unsolicited response interval in milliseconds (10 seconds) */
	private static final long RIP_RESPONSE_INTERVAL = 10000;
	
	/** Maximum metric for RIP (metric = 16 means unreachable) */
	private static final int RIP_MAX_METRIC = 16;
	
	/**
	 * Create a new RIP handler for the given router
	 * @param router the router this handler is associated with
	 */
	public RIPv2Handler(Router router)
	{
		this.router = router;
		this.routeTimestamps = new HashMap<RouteKey, Long>();
		this.timer = new Timer();
	}
	
	/**
	 * Initialize RIP by adding direct routes and sending RIP requests
	 */
	public void startRIP()
	{
		System.out.println("Starting RIP...");
		
		// Add direct routes for each interface
		for (Iface iface : this.router.getInterfaces().values())
		{
			int destIp = iface.getIpAddress() & iface.getSubnetMask();
			int gwIp = 0; // No gateway for direct routes
			int mask = iface.getSubnetMask();
			
			this.router.getRouteTable().insert(destIp, gwIp, mask, iface);
			
			// Record the timestamp for this route
			RouteKey key = new RouteKey(destIp, mask);
			this.routeTimestamps.put(key, System.currentTimeMillis());
			
			System.out.println(String.format("Added direct route: %s/%s via %s",
					IPv4.fromIPv4Address(destIp),
					IPv4.fromIPv4Address(mask),
					iface.getName()));
		}
		
		// Send RIP requests out all interfaces
		sendRIPRequest();
		
		// Schedule periodic RIP responses every 10 seconds
		this.timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				sendUnsolicitedResponse();
				timeoutRoutes();
			}
		}, RIP_RESPONSE_INTERVAL, RIP_RESPONSE_INTERVAL);
	}
	
	/**
	 * Send a RIP request out all interfaces
	 */
	private void sendRIPRequest()
	{
		System.out.println("Sending RIP requests");
		for (Iface iface : this.router.getInterfaces().values())
		{
			sendRIPRequest(iface);
		}
	}
	
	/**
	 * Send a RIP request out a specific interface
	 * @param iface the interface to send the request on
	 */
	private void sendRIPRequest(Iface iface)
	{
		// Create RIP request packet
		RIPv2 rip = new RIPv2();
		rip.setCommand(RIPv2.COMMAND_REQUEST);
		
		// Create UDP packet
		UDP udp = new UDP();
		udp.setSourcePort((short)520);
		udp.setDestinationPort((short)520);
		udp.setPayload(rip);
		
		// Create IPv4 packet
		IPv4 ipv4 = new IPv4();
		ipv4.setProtocol(IPv4.PROTOCOL_UDP);
		ipv4.setSourceAddress(iface.getIpAddress());
		ipv4.setDestinationAddress(RIP_MULTICAST_IP);
		ipv4.setTtl((byte)64);
		ipv4.setPayload(udp);
		
		// Create Ethernet packet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(iface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(RIP_BROADCAST_MAC);
		ether.setPayload(ipv4);
		
		// Send the packet
		this.router.sendPacket(ether, iface);
	}
	
	/**
	 * Send an unsolicited RIP response out all interfaces
	 */
	private void sendUnsolicitedResponse()
	{
		for (Iface iface : this.router.getInterfaces().values())
		{
			sendRIPResponse(iface, RIP_MULTICAST_IP, RIP_BROADCAST_MAC);
		}
	}
	
	/**
	 * Send a RIP response to a specific interface
	 * @param iface the interface to send the response through
	 * @param destIp the destination IP address
	 * @param destMac the destination MAC address
	 */
	private void sendRIPResponse(Iface iface, int destIp, byte[] destMac)
	{
		// Create RIP response packet with all routes
		RIPv2 rip = new RIPv2();
		rip.setCommand(RIPv2.COMMAND_RESPONSE);
		
		// Add all routes from the routing table, except the one we're sending on
		for (RouteEntry entry : getAllRoutes())
		{
			// Apply split horizon: don't send routes learned from an interface back out that interface
			// For direct routes, we send them out all interfaces  
			if (entry.getGatewayAddress() == 0 || entry.getInterface() != iface)
			{
				int metric = calculateMetric(entry);
				RIPv2Entry ripEntry = new RIPv2Entry(
					entry.getDestinationAddress(),
					entry.getMaskAddress(),
					metric
				);
				rip.addEntry(ripEntry);
			}
		}
		
		// Create UDP packet
		UDP udp = new UDP();
		udp.setSourcePort((short)520);
		udp.setDestinationPort((short)520);
		udp.setPayload(rip);
		
		// Create IPv4 packet
		IPv4 ipv4 = new IPv4();
		ipv4.setProtocol(IPv4.PROTOCOL_UDP);
		ipv4.setSourceAddress(iface.getIpAddress());
		ipv4.setDestinationAddress(destIp);
		ipv4.setTtl((byte)64);
		ipv4.setPayload(udp);
		
		// Create Ethernet packet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(iface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(destMac);
		ether.setPayload(ipv4);
		
		// Send the packet
		this.router.sendPacket(ether, iface);
	}
	
	/**
	 * Handle an incoming RIP packet
	 * @param rip the RIP packet to process
	 * @param inIface the interface on which the packet was received
	 * @param sourceIp the source IP address of the packet
	 * @param sourceMac the source MAC address of the packet
	 */
	public void handleRIPPacket(RIPv2 rip, Iface inIface, int sourceIp, byte[] sourceMac)
	{
		if (rip.getCommand() == RIPv2.COMMAND_REQUEST)
		{
			// Send a response to a RIP request
			sendRIPResponse(inIface, sourceIp, sourceMac);
		}
		else if (rip.getCommand() == RIPv2.COMMAND_RESPONSE)
		{
			// Process the response and update the routing table
			processRIPResponse(rip, inIface, sourceIp);
		}
	}
	
	/**
	 * Process a RIP response and update the routing table
	 * @param rip the RIP response packet
	 * @param inIface the interface on which the response was received
	 * @param sourceIp the source IP address of the response
	 */
	private void processRIPResponse(RIPv2 rip, Iface inIface, int sourceIp)
	{
		for (RIPv2Entry entry : rip.getEntries())
		{
			int destIp = entry.getAddress();
			int mask = entry.getSubnetMask();
			int metric = entry.getMetric();
			
			// Ignore invalid metric values
			if (metric < 1 || metric > RIP_MAX_METRIC)
			{
				continue;
			}
			
			// Increment the metric by 1 (hop count increases)
			metric++;
			
			// If metric is >= RIP_MAX_METRIC, treat as unreachable
			if (metric >= RIP_MAX_METRIC)
			{
				// Remove the route if it exists
				this.router.getRouteTable().remove(destIp, mask);
				continue;
			}
			
			RouteKey key = new RouteKey(destIp, mask);
			RouteEntry existingEntry = this.router.getRouteTable().findEntry(destIp, mask);
			
			if (existingEntry != null)
			{
				// We have an existing route with the same destination and mask
				int existingMetric = calculateMetric(existingEntry);
				
				if (sourceIp == existingEntry.getGatewayAddress())
				{
					// This is an update from the same gateway, so replace it
					this.router.getRouteTable().update(destIp, mask, sourceIp, inIface);
					this.routeTimestamps.put(key, System.currentTimeMillis());
				}
				else if (metric < existingMetric)
				{
					// We found a better route
					this.router.getRouteTable().update(destIp, mask, sourceIp, inIface);
					this.routeTimestamps.put(key, System.currentTimeMillis());
				}
			}
			else
			{
				// New route, add it to the routing table
				this.router.getRouteTable().insert(destIp, sourceIp, mask, inIface);
				this.routeTimestamps.put(key, System.currentTimeMillis());
			}
		}
	}
	
	/**
	 * Calculate the metric for a route entry (hop count = 1 for direct routes)
	 * @param entry the route entry
	 * @return the metric value
	 */
	private int calculateMetric(RouteEntry entry)
	{
		// For this simplified implementation, metric = 1 for direct routes, 2+ for indirect
		if (entry.getGatewayAddress() == 0)
		{
			return 1; // Direct route
		}
		else
		{
			// For indirect routes, we estimate as 2 (this could be enhanced)
			// In a real implementation, we would track actual hop counts
			return 2;
		}
	}
	
	/**
	 * Get all route entries from the routing table
	 * @return a list of all route entries
	 */
	private List<RouteEntry> getAllRoutes()
	{
		return this.router.getRouteTable().getEntries();
	}
	
	/**
	 * Time out routes that haven't been updated for more than 30 seconds
	 * Never removes direct routes (gateway address = 0)
	 */
	private void timeoutRoutes()
	{
		long currentTime = System.currentTimeMillis();
		List<RouteKey> toRemove = new LinkedList<RouteKey>();
		
		// Find all routes that have timed out
		for (Map.Entry<RouteKey, Long> entry : this.routeTimestamps.entrySet())
		{
			long elapsed = currentTime - entry.getValue();
			if (elapsed > RIP_TIMEOUT)
			{
				toRemove.add(entry.getKey());
			}
		}
		
		// Remove the timed-out routes
		for (RouteKey key : toRemove)
		{
			// Check if this is a direct route (gateway = 0)
			RouteEntry entry = this.router.getRouteTable().findEntry(key.destIp, key.mask);
			if (entry != null && entry.getGatewayAddress() != 0)
			{
				this.router.getRouteTable().remove(key.destIp, key.mask);
				this.routeTimestamps.remove(key);
				System.out.println(String.format("Route timeout: %s/%s",
						IPv4.fromIPv4Address(key.destIp),
						IPv4.fromIPv4Address(key.mask)));
			}
		}
	}
	
	/**
	 * Stop the RIP handler and clean up resources
	 */
	public void stop()
	{
		if (this.timer != null)
		{
			this.timer.cancel();
		}
	}
	
	/**
	 * Helper class to represent a route key (destination + mask)
	 */
	private static class RouteKey
	{
		int destIp;
		int mask;
		
		RouteKey(int destIp, int mask)
		{
			this.destIp = destIp;
			this.mask = mask;
		}
		
		@Override
		public boolean equals(Object obj)
		{
			if (!(obj instanceof RouteKey))
				return false;
			RouteKey other = (RouteKey) obj;
			return this.destIp == other.destIp && this.mask == other.mask;
		}
		
		@Override
		public int hashCode()
		{
			return (destIp * 31) + mask;
		}
	}
}
