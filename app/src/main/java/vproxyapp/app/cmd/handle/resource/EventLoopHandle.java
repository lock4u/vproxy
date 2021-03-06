package vproxyapp.app.cmd.handle.resource;

import vproxyapp.app.Application;
import vproxyapp.app.cmd.Command;
import vproxyapp.app.cmd.Resource;
import vproxyapp.app.cmd.ResourceType;
import vproxybase.component.elgroup.EventLoopGroup;
import vproxybase.component.elgroup.EventLoopWrapper;
import vproxybase.util.exception.XException;

import java.util.List;

public class EventLoopHandle {
    private EventLoopHandle() {
    }

    public static EventLoopWrapper get(Resource resource) throws Exception {
        String groupName = resource.parentResource.alias;
        return Application.get().eventLoopGroupHolder.get(groupName).get(resource.alias);
    }

    public static void checkEventLoop(Resource eventLoop) throws Exception {
        if (eventLoop.parentResource == null)
            throw new Exception("cannot find " + eventLoop.type.fullname + " on top level");
        if (eventLoop.parentResource.type != ResourceType.elg)
            throw new Exception(eventLoop.parentResource.type.fullname + " does not contain " + eventLoop.type.fullname);
        EventLoopGroupHandle.checkEventLoopGroup(eventLoop.parentResource);
    }

    public static List<String> names(Resource targetResource) throws Exception {
        EventLoopGroup g = EventLoopGroupHandle.get(targetResource);
        return g.names();
    }

    public static void add(Command cmd) throws Exception {
        EventLoopGroup g = EventLoopGroupHandle.get(cmd.prepositionResource);
        if (Application.isDefaultEventLoopGroupName(g.alias))
            throw new XException("cannot modify the default event loop group " + g.alias);
        g.add(cmd.resource.alias);
    }

    public static void forceRemove(Command cmd) throws Exception {
        EventLoopGroup g = EventLoopGroupHandle.get(cmd.prepositionResource);
        if (Application.isDefaultEventLoopGroupName(g.alias))
            throw new XException("cannot modify the default event loop group " + g.alias);
        g.remove(cmd.resource.alias);
    }
}
