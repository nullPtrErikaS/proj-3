package edu.wisc.cs.sdn.vnet.sw;

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;

import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.MACAddress;

public class ForwardingTable {

    private static final long ENTRY_TIMEOUT_MS = 15000;

    private Map<MACAddress, ForwardEntry> table;

    public ForwardingTable(){
        this.table = new HashMap<MACAddress, ForwardEntry>();
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
