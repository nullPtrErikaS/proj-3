# Assignment 3 RIPv2 Implementation Summary

## Overview
I've successfully implemented RIPv2 (Routing Information Protocol v2) distance vector routing for Assignment 3. The implementation allows routers to dynamically build and maintain routing tables without static configuration files.

## Key Components Added/Modified

### 1. **RIPv2Handler.java** (New)
A dedicated RIP protocol handler that manages:
- **Initialization**: Adds direct routes for all router interfaces on startup
- **RIP Requests**: Sends RIP requests on all interfaces at startup
- **Periodic Updates**: Sends unsolicited RIP responses every 10 seconds
- **Route Updates**: Processes incoming RIP responses and updates routing table
- **Timeout Management**: Times out routes after 30 seconds of no updates (never removes direct routes)

### 2. **Router.java** (Modified)
Key changes:
- Added `RIPv2Handler` field and `startRIP()` method
- Updated `handlePacket()` to detect RIP packets (UDP port 520) and route them to the handler
- Added the "no-route-back" check to prevent packets being sent back on the incoming interface
- Added `destroy()` override to properly clean up RIP handler on shutdown
- Added UDP and RIPv2 imports

### 3. **Main.java** (Modified)
- Modified to start RIP when no static routing table file is provided
- Existing behavior unchanged when routing table file is specified with `-r` argument

### 4. **RouteTable.java** (Enhanced)
Added two public methods:
- `findEntry(int dstIp, int maskIp)`: Find a route by exact destination/mask match
- `getEntries()`: Get a copy of all route entries (for RIP updates)

## How RIPv2 Works in This Implementation

### Initialization:
1. Router creates a `RIPv2Handler` when started without a static routing table
2. Handler adds direct routes (metric 1) for all router interfaces
3. Sends RIP requests on all interfaces

### Periodic Operation (every 10 seconds):
1. Sends unsolicited RIP responses advertising all known routes
2. Times out routes not updated in the last 30 seconds
3. Never removes direct routes (gateway = 0)

### Route Updates:
When receiving RIP responses:
1. Increment advertised metric by 1 (hop count)
2. If metric ≥ 16 (RIP max), treat as unreachable and remove route
3. For new routes: add to routing table
4. For existing routes: update only if new metric is better or from same gateway
5. Track timestamp for each route for timeout management

## Testing Instructions

### Test 1: Basic Multi-Router Setup (pair_rt.topo)
```bash
# Terminal 1: POX Controller
pox/pox.py cs640.ofhandler

# Terminal 2: Router r1
java -jar VirtualNetwork.jar -v r1 -a arp_cache

# Terminal 3: Router r2
java -jar VirtualNetwork.jar -v r2 -a arp_cache

# Terminal 4: Mininet (from assign2 directory)
python run_mininet.py topos/pair_rt.topo
```

### Test 2: Verify Route Discovery
In mininet terminal:
```
mininet> h1 ping h2
mininet> route -n  # on h1 and h2 to see learned routes
```

### Test 3: Failover Test (triangle_rt.topo)
```bash
# Run same setup but with triangle_rt.topo
# This tests:
# - Learning alternate routes
# - Handling timeouts when router goes down
# - Link redundancy
```

### Test 4: Monitor RIP Messages with tcpdump
```bash
mininet> xterm h1
# In h1 xterm:
sudo tcpdump -n -vv -e -i h1-eth0 udp port 520
```

## Implementation Details

### RIP Packet Structure
- **RIP Request**: Command=1, empty entries (requests routing table)
- **RIP Response**: Command=2, contains route entries with:
  - Address (destination network)
  - Subnet Mask
  - Metric (hop count, 1-15, with 16 meaning unreachable)

### Metric Calculation
- Direct routes: Metric = 1 (no gateway)
- Learned routes: Updated on each hop with current metric + 1

### Split Horizon
Implemented to prevent routing loops:
- For direct routes: advertised on all interfaces
- For learned routes: not advertised back on the interface they were learned from

### Timeout Behavior
- Routes without updates for 30 seconds are removed
- Exception: direct routes (gateway=0) are never removed
- Useful for detecting link failures

## Notes for Debugging

1. **Verbose Output**: The handler prints initialization and timeout messages
2. **tcpdump**: Use to inspect RIP packet exchanges
3. **Route Table**: Check with `Router.toString()` output
4. **TTL**: RIP packets use TTL=64
5. **Ports**: RIP always uses UDP port 520

## Common Issues & Solutions

### Issue: Routes not converging
**Solution**: Ensure all routers are started before hosts attempt communication. RIP sends requests on startup which triggers responses.

### Issue: Slow failover
**Solution**: This is normal. RIP waits 30 seconds before timing out a route. You can manually trigger rediscovery by stopping/restarting routers.

### Issue: Routes not updating  
**Solution**: Verify UDP port 520 is being used and packet checksums are correct. Check tcpdump output.

## Submission Checklist

- [x] RIPv2Handler.java created with full implementation
- [x] Router.java modified for RIP support and no-route-back check
- [x] Main.java modified to start RIP when needed
- [x] RouteTable.java enhanced with helper methods
- [x] All code compiles successfully
- [x] Comments and documentation included
- [x] Thread-safe synchronization on route table access

## Files Modified
1. `src/edu/wisc/cs/sdn/vnet/rt/Router.java`
2. `src/edu/wisc/cs/sdn/vnet/rt/RIPv2Handler.java` (new)
3. `src/edu/wisc/cs/sdn/vnet/rt/RouteTable.java`
4. `src/edu/wisc/cs/sdn/vnet/Main.java`
