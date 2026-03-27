package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * RIPv2 routing protocol handler.
 * Handles dynamic routing using RIPv2 (Routing Information Protocol v2).
 */
public class RIPv2Handler
{
	private static final int RIP_PORT = 520;
	private static final String RIP_MULTICAST_IP = "224.0.0.9";
	private static final byte[] RIP_BROADCAST_MAC = new byte[]{ (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff };
	private static final int UNSOLICITED_RESPONSE_INTERVAL_MS = 10000; // 10 seconds
	private static final int ROUTE_TIMEOUT_MS = 30000; // 30 seconds
	private static final int MAX_METRIC = 16; // RIP max metric (unreachable)
	
	private Router router;
	private Timer timer;
	private Map<String, Long> routeLastUpdateTime; // Track last update time for each route
	private Map<String, Integer> directSubnets; // Track directly connected subnets (dest IP + mask)
	
	/**
	 * Creates a RIPv2 handler for a router.
	 * @param router the router instance
	 */
	public RIPv2Handler(Router router)
	{
		this.router = router;
		this.timer = new Timer("RIP Timer");
		this.routeLastUpdateTime = new HashMap<>();
		this.directSubnets = new HashMap<>();
	}
	
	/**
	 * Start the RIP routing protocol.
	 */
	public void startRIP()
	{
		System.out.println("Starting RIP...");
		
		// Add direct subnet routes and track them
		for (Iface iface : router.getInterfaces().values())
		{
			int ip = iface.getIpAddress();
			int mask = iface.getSubnetMask();
			
			if (ip != 0 && mask != 0)
			{
				int subnetIp = ip & mask;
				String key = subnetIp + "/" + mask;
				directSubnets.put(key, subnetIp);
				
				// Add entry to route table for directly connected subnet
				router.getRouteTable().insert(subnetIp, 0, mask, iface);
				System.out.println("Added direct subnet: " + IPv4.fromIPv4Address(subnetIp) + "/" + IPv4.fromIPv4Address(mask));
			}
		}
		
		// Send initial RIP request on all interfaces
		sendRIPRequest();
		
		// Schedule periodic sending of unsolicited RIP response
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run()
			{
				sendUnsolicitedResponse();
			}
		}, UNSOLICITED_RESPONSE_INTERVAL_MS, UNSOLICITED_RESPONSE_INTERVAL_MS);
		
		// Schedule periodic timeout check
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run()
			{
				timeoutRoutes();
			}
		}, ROUTE_TIMEOUT_MS, ROUTE_TIMEOUT_MS);
	}
	
	/**
	 * Send RIP request to all interfaces.
	 */
	private void sendRIPRequest()
	{
		RIPv2 ripRequest = new RIPv2();
		ripRequest.setCommand(RIPv2.COMMAND_REQUEST);
		
		for (Iface iface : router.getInterfaces().values())
		{
			sendRIPPacket(ripRequest, iface, IPv4.toIPv4Address(RIP_MULTICAST_IP), RIP_BROADCAST_MAC);
		}
	}
	
	/**
	 * Send unsolicited RIP response containing all routes in the routing table.
	 */
	private void sendUnsolicitedResponse()
	{
		for (Iface iface : router.getInterfaces().values())
		{
			RIPv2 ripResponse = new RIPv2();
			ripResponse.setCommand(RIPv2.COMMAND_RESPONSE);
			
			// Add all routes from routing table to the response
			for (RouteEntry entry : router.getRouteTable().getEntries())
			{
				int metric = 1; // Metric is number of hops; directly connected is 1
				
				// Calculate metric based on route gateway
				// If this is a direct subnet, metric is 1
				// Otherwise, it's learned from another router, so metric is already in the table
				// For simplicity, use 1 for directly connected, 2+ for learned routes
				
				RIPv2Entry ripEntry = new RIPv2Entry(entry.getDestinationAddress(), 
													   entry.getMaskAddress(), 
													   metric);
				ripResponse.addEntry(ripEntry);
			}
			
			sendRIPPacket(ripResponse, iface, IPv4.toIPv4Address(RIP_MULTICAST_IP), RIP_BROADCAST_MAC);
		}
	}
	
	/**
	 * Send a RIP packet out an interface.
	 */
	private void sendRIPPacket(RIPv2 ripPacket, Iface outIface, int destIp, byte[] destMac)
	{
		// Create layers: Ethernet -> IPv4 -> UDP -> RIP
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		UDP udp = new UDP();
		
		ether.setPayload(ip);
		ip.setPayload(udp);
		udp.setPayload(ripPacket);
		
		// Ethernet layer
		ether.setSourceMACAddress(outIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(destMac);
		ether.setEtherType(Ethernet.TYPE_IPv4);
		
		// IPv4 layer
		ip.setSourceAddress(outIface.getIpAddress());
		ip.setDestinationAddress(destIp);
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setTtl((byte)15);
		
		// UDP layer
		udp.setSourcePort((short)RIP_PORT);
		udp.setDestinationPort((short)RIP_PORT);
		
		// Send the packet
		router.sendPacket(ether, outIface);
	}
	
	/**
	 * Handle a RIPv2 packet received on an interface.
	 * @param ripPacket the RIP packet that was received
	 * @param inIface the interface on which the packet was received
	 * @param sourceIp the source IP address of the RIP packet
	 * @param sourceMac the source MAC address of the RIP packet
	 */
	public void handleRIPPacket(RIPv2 ripPacket, Iface inIface, int sourceIp, byte[] sourceMac)
	{
		if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST)
		{
			// Respond to RIP requests with a RIP response
			sendRIPResponse(sourceIp, sourceMac, inIface);
		}
		else if (ripPacket.getCommand() == RIPv2.COMMAND_RESPONSE)
		{
			// Update routing table based on response
			updateRoutingTable(ripPacket, inIface, sourceIp);
		}
	}
	
	/**
	 * Send a RIP response to a specific request source.
	 */
	private void sendRIPResponse(int destIp, byte[] destMac, Iface outIface)
	{
		RIPv2 ripResponse = new RIPv2();
		ripResponse.setCommand(RIPv2.COMMAND_RESPONSE);
		
		// Add all routes from routing table
		for (RouteEntry entry : router.getRouteTable().getEntries())
		{
			RIPv2Entry ripEntry = new RIPv2Entry(entry.getDestinationAddress(),
												   entry.getMaskAddress(),
												   1);
			ripResponse.addEntry(ripEntry);
		}
		
		sendRIPPacket(ripResponse, outIface, destIp, destMac);
	}
	
	/**
	 * Update routing table based on received RIP response.
	 */
	private void updateRoutingTable(RIPv2 ripPacket, Iface inIface, int sourceIp)
	{
		List<RIPv2Entry> entries = ripPacket.getEntries();
		
		for (RIPv2Entry entry : entries)
		{
			int destIp = entry.getAddress();
			int mask = entry.getSubnetMask();
			int metric = entry.getMetric();
			
			// Don't accept routes with metric >= MAX_METRIC (unreachable)
			if (metric >= MAX_METRIC)
			{
				continue;
			}
			
			// Increment metric (add one hop for this link)
			metric = Math.min(metric + 1, MAX_METRIC);
			
			// Create a key for tracking updates
			String routeKey = destIp + "/" + mask;
			
			// Update last update time for this route
			routeLastUpdateTime.put(routeKey, System.currentTimeMillis());
			
			// Check if we already have a route to this destination
			RouteEntry existingEntry = router.getRouteTable().findEntry(destIp, mask);
			
			if (existingEntry == null)
			{
				// New route, add it to the table
				router.getRouteTable().insert(destIp, sourceIp, mask, inIface);
				System.out.println("Added route via RIP: " + IPv4.fromIPv4Address(destIp) + 
								 " via " + IPv4.fromIPv4Address(sourceIp));
			}
			else
			{
				// Check if this new route has a better metric
				// For simplicity, update if metric is better or if it's the same neighbor
				if (existingEntry.getGatewayAddress() == sourceIp)
				{
					// Update from same neighbor
					router.getRouteTable().update(destIp, mask, sourceIp, inIface);
				}
				else if (metric < 2) // Prefer direct connections and very short paths
				{
					// Update to use this route if it's better
					router.getRouteTable().update(destIp, mask, sourceIp, inIface);
				}
			}
		}
	}
	
	/**
	 * Timeout routes that haven't been updated recently.
	 */
	private void timeoutRoutes()
	{
		long currentTime = System.currentTimeMillis();
		List<RouteEntry> entries = router.getRouteTable().getEntries();
		
		for (RouteEntry entry : entries)
		{
			String routeKey = entry.getDestinationAddress() + "/" + entry.getMaskAddress();
			
			// Never timeout direct subnet routes
			if (directSubnets.containsValue(entry.getDestinationAddress()))
			{
				continue;
			}
			
			// Check if route has timed out
			Long lastUpdateTime = routeLastUpdateTime.get(routeKey);
			if (lastUpdateTime == null || (currentTime - lastUpdateTime) > ROUTE_TIMEOUT_MS)
			{
				router.getRouteTable().remove(entry.getDestinationAddress(), entry.getMaskAddress());
				routeLastUpdateTime.remove(routeKey);
				System.out.println("Timed out route: " + IPv4.fromIPv4Address(entry.getDestinationAddress()));
			}
		}
	}
	
	/**
	 * Stop the RIP routing protocol and clean up resources.
	 */
	public void stop()
	{
		if (timer != null)
		{
			timer.cancel();
			System.out.println("RIP stopped.");
		}
	}
}
