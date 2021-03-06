package vproxyapp.app;

import vfd.IPPort;
import vproxy.component.secure.SecurityGroup;
import vproxybase.component.elgroup.EventLoopGroup;
import vproxybase.util.exception.AlreadyExistException;
import vproxybase.util.exception.ClosedException;
import vproxybase.util.exception.NotFoundException;
import vswitch.Switch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SwitchHolder {
    private final Map<String, Switch> map = new HashMap<>();

    public List<String> names() {
        return new ArrayList<>(map.keySet());
    }

    public Switch add(String alias,
                      IPPort vxlanBindingAddress,
                      EventLoopGroup eventLoopGroup,
                      int macTableTimeout,
                      int arpTableTimeout,
                      SecurityGroup bareVXLanAccess) throws AlreadyExistException, ClosedException, IOException {
        if (map.containsKey(alias))
            throw new AlreadyExistException("switch", alias);

        Switch sw = new Switch(alias, vxlanBindingAddress, eventLoopGroup, macTableTimeout, arpTableTimeout, bareVXLanAccess);
        try {
            sw.start();
        } catch (IOException e) {
            sw.destroy();
            throw e;
        }
        map.put(alias, sw);
        return sw;
    }

    public Switch get(String alias) throws NotFoundException {
        Switch sw = map.get(alias);
        if (sw == null)
            throw new NotFoundException("switch", alias);
        return sw;
    }

    public void removeAndStop(String alias) throws NotFoundException {
        Switch sw = map.remove(alias);
        if (sw == null)
            throw new NotFoundException("switch", alias);
        sw.destroy();
    }
}
