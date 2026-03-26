package edu.wisc.cs.sdn.vnet.sw;

import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.MACAddress;

public class ForwardEntry {
    
    private MACAddress destMACAddr;
    private Iface port;
    private long lastSeen;

    public ForwardEntry(MACAddress destinationAddress, Iface iface){
        this.destMACAddr = destinationAddress;
        this.port = iface;
        this.lastSeen = System.currentTimeMillis();
    }

    public MACAddress getMACDestination(){
        return this.destMACAddr;
    }

    public Iface getPort(){
        return this.port;
    }

    public void setPort(Iface port)
    {
        this.port = port;
    }

    public long getLastSeen()
    {
        return this.lastSeen;
    }

    public void touch()
    {
        this.lastSeen = System.currentTimeMillis();
    }
    
}
