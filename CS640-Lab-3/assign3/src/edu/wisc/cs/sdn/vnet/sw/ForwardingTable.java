package edu.wisc.cs.sdn.vnet.sw;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.MACAddress;

public class ForwardingTable {

    private static final long ENTRY_TIMEOUT_MS = 15_000;

    private Map<MACAddress, ForwardEntry> table;

    // create an empty Forwarding Table
    public ForwardingTable(){
        this.table = new ConcurrentHashMap<MACAddress, ForwardEntry>();
    }

    private void removeExpiredEntries()
    {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<MACAddress, ForwardEntry>> iterator = this.table.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<MACAddress, ForwardEntry> entry = iterator.next();
            if (now - entry.getValue().getLastSeen() >= ENTRY_TIMEOUT_MS)
            {
                iterator.remove();
            }
        }
    }

    // add/update in Forwarding Table
    public void learn(MACAddress addr, Iface port)
    {
        removeExpiredEntries();

        ForwardEntry existing = this.table.get(addr);
        if (null == existing)
        {
            this.table.put(addr, new ForwardEntry(addr, port));
            return;
        }

        existing.setPort(port);
        existing.touch();
    }

    // Look up in Forwarding Table (input MAC, return port, otherwise null)
    public Iface lookup(MACAddress addr){
        removeExpiredEntries();
        ForwardEntry entry = this.table.get(addr);
        if (null != entry)
        {
            return entry.getPort();
        }
        return null;
    }
}
