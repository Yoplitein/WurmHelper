package net.ildar.wurm.bot;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;

import com.wurmonline.client.game.World;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.TilePicker;
import com.wurmonline.client.renderer.cave.CaveWallPicker;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.shared.constants.PlayerAction;

import net.ildar.wurm.BotController;
import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;
import net.ildar.wurm.bot.MinerBot.Direction;

@BotInfo(description = "Remotely control other clients", abbreviation = "rmi")
public class RMIBot extends Bot implements BotServer, BotClient, Executor
{
    final HeadsUpDisplay hud = WurmHelper.hud;
    final World world = hud.getWorld();
    
    static final String registryPrefix = "RMIBot";
    String registryHost = "127.0.43.7";
    int registryPort = 0x2B07;
    
    ArrayBlockingQueue<Runnable> oneshotTasks = new ArrayBlockingQueue<>(32, false);
    /*Concurrent?*/HashMap<Runnable, Long> scheduledTasks = new HashMap<>();
    
    // server fields
    ClientSet clients;
    Registry serverRegistry;
    boolean syncPosition;
    
    // client fields
    Registry clientRegistry;
    long shovelID = -10;
    long pickaxeID = -10;
    
    boolean isServer()
    {
        return serverRegistry != null;
    }
    
    boolean isClient()
    {
        return clientRegistry != null;
    }
    
    boolean printExceptions(ThrowingRunnable fn, String fmt, Object... args)
    {
        try
        {
            fn.run();
            return true;
        }
        catch(Exception err)
        {
            Utils.consolePrint(fmt, err.getClass().getName(), err.getMessage(), args);
            return false;
        }
    }
    
    public RMIBot()
    {
        registerInputHandler(
            Inputs.s,
            input -> printExceptions(
                () -> toggleServerMode(),
                "Got %s when toggling server mode: %s"
            )
        );
        registerInputHandler(
            Inputs.c,
            input -> printExceptions(
                () -> toggleClientMode(),
                "Got %s when toggling client mode: %s"
            )
        );
        registerInputHandler(
            Inputs.sl,
            input -> printExceptions(
                () -> serverListClients(),
                "Got %s when listing clients: %s"
            )
        );
        registerInputHandler(
            Inputs.slr,
            input -> printExceptions(
                () -> serverRefreshClients(),
                "Got %s when refreshing clients: %s"
            )
        );
        registerInputHandler(
            Inputs.sr,
            input -> printExceptions(
                () -> serverRun(input),
                "Got %s when running server command: %s"
            )
        );
        registerInputHandler(Inputs.regaddr, this::setRegistryAddress);
    }
    
    @Override
    public void setPaused()
    {
        Utils.consolePrint("RMI bot cannot be paused");
    }
    
    @Override
    public synchronized void setResumed()
    {
        Utils.consolePrint("RMI bot cannot be paused");
    }
    
    @Override
    public synchronized void execute(Runnable task)
    {
        oneshotTasks.add(task);
        notify();
    }
    
    synchronized void schedule(Runnable task, long msecs)
    {
        final long now = System.currentTimeMillis();
        scheduledTasks.put(task, now + msecs);
        notify();
    }
    
    @Override
    void work() throws Exception
    {
        try
        {
            ArrayList<Entry<Runnable, Long>> expiredTasks = new ArrayList<>();
            
            while(isActive())
            {
                try
                {
                    synchronized(this)
                    {
                        while(scheduledTasks.isEmpty() && oneshotTasks.isEmpty())
                            wait();
                    
                        long now = System.currentTimeMillis();
                        expiredTasks.clear();
                        for(Entry<Runnable, Long> pair: scheduledTasks.entrySet())
                            if(pair.getValue() <= now)
                                expiredTasks.add(pair);
                        
                        for(Entry<Runnable, Long> pair: expiredTasks)
                        {
                            scheduledTasks.remove(pair.getKey());
                            pair.getKey().run();
                        }
                        
                        int oneshotsToRun = oneshotTasks.size(); // process new tasks next iteration
                        while(oneshotsToRun-- > 0)
                            oneshotTasks.remove().run();
                        
                        long nextTaskTime = scheduledTasks
                            .values()
                            .stream()
                            .min(Comparator.comparingLong(x -> x))
                            .orElse(Long.MAX_VALUE)
                        ;
                        now = System.currentTimeMillis();
                        while(oneshotTasks.size() == 0 && nextTaskTime - now > 0)
                        {
                            wait(Math.max(0, nextTaskTime - now));
                            now = System.currentTimeMillis();
                        }
                    }
                }
                catch(InterruptedException err)
                {
                    break;
                }
                catch(Exception err)
                {
                    Utils.consolePrint(
                        "RMI: Got %s when running tasks: %s",
                        err.getClass().getName(),
                        err.getMessage()
                    );
                    err.printStackTrace();
                }
            }
        }
        finally
        {
            printExceptions(() -> {
                if(isClient()) toggleClientMode();
                if(isServer()) toggleServerMode();
            }, "Got %s when deactivating RMIBot: %s");
        }
    }
    
    void toggleServerMode() throws Exception
    {
        if(isClient())
        {
            Utils.consolePrint("Can't enable server mode while client mode is enabled");
            return;
        }
        
        if(isServer())
        {
            for(String name: serverRegistry.list())
                serverRegistry.unbind(name);
            
            clients = null;
            
            serverRegistry.unbind("BotServer");
            UnicastRemoteObject.unexportObject(this, true);
            
            UnicastRemoteObject.unexportObject(serverRegistry, true);
            serverRegistry = null;
            
            Utils.consolePrint("Server mode disabled, RMI registry shut down");
            return;
        }
        
        serverRegistry = LocateRegistry.createRegistry(
            registryPort,
            RMISocketFactory.getDefaultSocketFactory(),
            port -> new ServerSocket(port, 16, InetAddress.getByName(registryHost))
        );
        
        Remote stub = UnicastRemoteObject.exportObject(this, 0);
        serverRegistry.bind("BotServer", stub);
        
        clients = new ClientSet();
        
        Utils.consolePrint("Server mode active, RMI registry running at %s:%d", registryHost, registryPort);
    }
    
    void toggleClientMode() throws Exception
    {
        if(isServer())
        {
            Utils.consolePrint("Can't enable client mode while server mode is enabled");
            return;
        }
        
        final String regName = String.format("%s_%s", registryPrefix, getPlayerName());
        
        if(isClient())
        {
            clientRegistry.unbind(regName);
            UnicastRemoteObject.unexportObject(this, true);
            ((BotServer)clientRegistry.lookup("BotServer")).onClientLeave(getPlayerName());
            clientRegistry = null;
            
            Utils.consolePrint("Client mode disabled");
            return;
        }
        
        clientRegistry = LocateRegistry.getRegistry(registryHost, registryPort);
        Remote stub = UnicastRemoteObject.exportObject(this, 0);
        clientRegistry.bind(regName, stub);
        Utils.consolePrint("Client mode enabled");
        ((BotServer)clientRegistry.lookup("BotServer")).onClientJoin(getPlayerName());
    }
    
    void serverRefreshClients() throws Exception
    {
        if(!isServer())
        {
            Utils.consolePrint("This command can only be used in server mode");
            return;
        }
        assert clients != null;
        
        clients.refreshRemotes(serverRegistry);
    }
    
    void serverListClients() throws Exception
    {
        if(!isServer())
        {
            Utils.consolePrint("This command can only be used in server mode");
            return;
        }
        assert clients != null;
        
        String allNames = clients.getPlayerName();
        if(allNames.length() > 0)
            Utils.consolePrint("Clients: %s", allNames);
        else
            Utils.consolePrint("No clients");
    }
    
    void serverRun(String[] args) throws Exception
    {
        if(!isServer())
        {
            Utils.consolePrint("This command can only be used in server mode");
            return;
        }
        assert clients != null;
        
        if(args.length == 0)
        {
            Utils.consolePrint("Must specify a subcommand");
            return;
        }
        
        switch(args[0].toLowerCase())
        {
            case "help":
            {
                final String botKeyword = BotController.getInstance().getBotRegistration(this.getClass()).getAbbreviation();
                final String cmdKeyword = Inputs.sr.getName();
                
                Utils.consolePrint(
                    "bot %s %s attack -- clients (+ master) target hovered creature",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s syncpos -- clients have position and heading continuously synced with master",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s embark -- clients (+ master as driver) embark onto hovered vehicle",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s disembark -- clients (+ master) disembark their current vehicle",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s dig -- clients shovel up resources",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s level -- clients level out hovered tile",
                    botKeyword, cmdKeyword
                );
                Utils.consolePrint(
                    "bot %s %s mine <f/u/d/v> -- clients mine hovered wall in specified direction " +
                    "(as in MinerBot, or floor/ceiling with `v`)",
                    botKeyword, cmdKeyword
                );
                break;
            }
            case "attack":
            {
                PickableUnit unit = world.getCurrentHoveredObject();
                if(unit == null || !(unit instanceof CreatureCellRenderable))
                {
                    Utils.consolePrint("Not hovering over creature");
                    break;
                }
                
                attack(unit.getId());
                clients.attack(unit.getId());
                break;
            }
            case "syncpos":
            {
                syncPosition = !syncPosition;
                if(syncPosition)
                    execute(this::syncPositionTask);
                
                Utils.consolePrint(
                    "Position synchronization %s",
                    syncPosition ? "on" : "off"
                );
                break;
            }
            case "embark":
            {
                PickableUnit unit = world.getCurrentHoveredObject();
                if(unit == null || !(unit instanceof CreatureCellRenderable))
                {
                    Utils.consolePrint("Not hovering over vehicle");
                    break;
                }
                
                long id = unit.getId();
                hud.sendAction(PlayerAction.EMBARK_DRIVER, id);
                clients.embark(id);
                break;
            }
            case "disembark":
            {
                disembark();
                clients.disembark();
                break;
            }
            case "dig":
            {
                dig();
                clients.dig();
                break;
            }
            case "level":
            {
                PickableUnit unit = world.getCurrentHoveredObject();
                if(unit == null || !(unit instanceof TilePicker))
                {
                    Utils.consolePrint("Not hovering over any tile");
                    return;
                }
                
                final long tileID = unit.getId();
                level(tileID);
                clients.level(tileID);
                
                break;
            }
            case "mine":
            {
                final String strDir = args.length >= 2 ? args[1].toLowerCase() : "f";
                final Direction direction = Direction.getByAbbreviation(strDir);
                final boolean targetingVertical = strDir.equalsIgnoreCase("v");
                
                if(direction == Direction.UNKNOWN && !targetingVertical)
                {
                    Utils.consolePrint("Unkown mining direction `%s` (expecting f/u/d/v)", args[1]);
                    return;
                }
                
                PickableUnit unit = world.getCurrentHoveredObject();
                if(unit == null || !(unit instanceof CaveWallPicker))
                {
                    Utils.consolePrint("Not hovering over any cave tile");
                    return;
                }
                
                // vertical mining has its own "direction" to guard against wayward mining actions mucking up e.g. smooth floors
                final boolean targetedVertical = ((CaveWallPicker)unit).getWallId() < 2; // ids 0,1 are floor/ceiling
                if(targetingVertical != targetedVertical)
                {
                    Utils.consolePrint(
                        "Direction mismatch: direction is %s but hovering over %s%s",
                        targetingVertical ? "floor/ceiling" : "walls",
                        targetedVertical ? "floor/ceiling" : "a wall",
                        targetedVertical ? " (did you mean to use `v` as the direction?)" : ""
                    );
                    return;
                }
                
                final long wallID = unit.getId();
                mine(wallID, direction);
                clients.mine(wallID, direction);
                break;
            }
            default:
                Utils.consolePrint("Unknown subcommand `%s`", args[0]);
        }
    }
    
    void setRegistryAddress(String[] args)
    {
        if(isServer() || isClient())
        {
            Utils.consolePrint(
                "Can't change registry address while it/client mode are enabled"
            );
            return;
        }
        
        if(args.length != 1)
        {
            printInputKeyUsageString(Inputs.regaddr);
            return;
        }
        
        String[] pair = args[0].split(":", 1);
        if(pair.length != 2)
        {
            printInputKeyUsageString(Inputs.regaddr);
            return;
        }
        
        try
        {
            registryPort = Integer.parseInt(pair[1]);
            registryHost = pair[0];
        }
        catch(NumberFormatException err)
        {
            Utils.consolePrint("Couldn't parse port: %s", err.getMessage());
        }
    }

    private enum Inputs implements Bot.InputKey
    {
        s("Toggle server mode, allowing sending commands to other characters", ""),
        c("Toggle client mode, allowing remote control of this character", ""),
        
        sl("Server: list known clients", ""),
        slr("Server: refresh list of clients", ""),
        sr("Server: dispatch commands", "..."),
        
        regaddr("Set hostname and port of RMI registry", "host:port"),
        ;
        
        
        String description;
        String usage;
        Inputs(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getUsage() {
            return usage;
        }
    }
    
    void syncPositionTask()
    {
        final float px = world.getPlayerPosX();
        final float py = world.getPlayerPosY();
        final float rx = world.getPlayerRotX();
        final float ry = world.getPlayerRotY();
        printExceptions(
            () -> clients.setPosAndHeading(px, py, rx, ry),
            "Got %s when setting clients' position: %s"
        );
        
        if(syncPosition)
            schedule(this::syncPositionTask, 100);
    }
    
    @Override
    public void onClientJoin(String name)
    {
        Utils.consolePrint("Got new client: %s", name);
        printExceptions(
            () -> clients.refreshRemotes(serverRegistry),
            "Got %s when registering that client: %s"
        );
    }
    
    @Override
    public void onClientLeave(String name)
    {
        Utils.consolePrint("Client `%s` disconnected", name);
        printExceptions(
            () -> clients.refreshRemotes(serverRegistry),
            "Got %s when unregistering that client: %s"
        );
    }
    
    @Override
    public String getPlayerName()
    {
        return world.getPlayer().getPlayerName();
    }

    @Override
    public void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException
    {
        execute(() -> {
            Utils.movePlayer(x, y);
            Utils.turnPlayer(rx, ry);
        });
    }
    
    @Override
    public void embark(long vehicleID) throws RemoteException
    {
        hud.sendAction(PlayerAction.EMBARK_PASSENGER, vehicleID);
    }
    
    @Override
    public void disembark() throws RemoteException
    {
        CreatureCellRenderable vehicle = world.getPlayer().getCarrierCreature();
        if(vehicle == null)
        {
            Utils.consolePrint("Got disembark request but not riding anything");
            return;
        }
        
        hud.sendAction(PlayerAction.DISEMBARK, vehicle.getId());
    }

    @Override
    public void attack(long creatureID)
    {
        execute(() -> {
            hud.sendAction(PlayerAction.TARGET, creatureID);
            Utils.consolePrint("Attacking creature %s", creatureID);
        });
    }
    
    @Override
    public void dig() throws RemoteException
    {
        if(shovelID == -10)
        {
            InventoryMetaItem tool = Utils.locateToolItem("shovel");
            if(tool == null)
                return;
            else
                shovelID = tool.getId();
        }
        
        final int tx = Math.round(WurmHelper.hud.getWorld().getPlayerPosX() / 4);
        final int ty = Math.round(WurmHelper.hud.getWorld().getPlayerPosY() / 4);
        final long tileID = Tiles.getTileId(tx, ty, 0);
        world.getServerConnection().sendAction(shovelID, new long[]{tileID}, PlayerAction.DIG);
    }
    
    @Override
    public void level(long tileID) throws RemoteException
    {
        if(shovelID == -10)
        {
            InventoryMetaItem tool = Utils.locateToolItem("shovel");
            if(tool == null)
                return;
            else
                shovelID = tool.getId();
        }
        
        world.getServerConnection().sendAction(shovelID, new long[]{tileID}, PlayerAction.LEVEL);
    }
    
    @Override
    public void mine(long wallID, Direction direction)
    {
        execute(() -> {
            if(pickaxeID == -10)
            {
                InventoryMetaItem tool = Utils.locateToolItem("pickaxe");
                if(tool == null)
                    return;
                else
                    pickaxeID = tool.getId();
            }
            
            world.getServerConnection().sendAction(
                pickaxeID,
                new long[]{wallID},
                direction.action
            );
        });
    }
}

interface BotServer extends Remote
{
    void onClientJoin(String name) throws RemoteException;
    void onClientLeave(String name) throws RemoteException;
}

interface BotClient extends Remote
{
    String getPlayerName() throws RemoteException;
    
    void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException;
    void embark(long vehicleID) throws RemoteException;
    void disembark(/* tile ID? */) throws RemoteException;
    void attack(long creatureID) throws RemoteException;
    void dig() throws RemoteException;
    void level(long tileID) throws RemoteException;
    void mine(long wallID, MinerBot.Direction direction) throws RemoteException;
}


final class ClientSet implements BotClient
{
    ArrayList<BotClient> remotes = new ArrayList<>();
    
    public void refreshRemotes(Registry registry) throws Exception
    {
        remotes.clear();
        
        String[] names = registry.list();
        for(String name: names)
        {
            if(!name.startsWith(RMIBot.registryPrefix))
                continue;
            remotes.add((BotClient)registry.lookup(name));
        }
    }
    
    @Override
    public String getPlayerName() throws RemoteException
    {
        String[] names = new String[remotes.size()];
        
        int index = 0;
        for(BotClient remote: remotes)
            names[index++] = remote.getPlayerName();
        
        return String.join(", ", names);
    }

    @Override
    public void setPosAndHeading(float x, float y, float rx, float ry) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.setPosAndHeading(x, y, rx, ry);
    }
    
    @Override
    public void embark(long vehicleID) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.embark(vehicleID);
    }
    
    @Override
    public void disembark() throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.disembark();
    }
    
    @Override
    public void attack(long creatureID) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.attack(creatureID);
    }
    
    @Override
    public void dig() throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.dig();
    }
    
    @Override
    public void level(long tileID) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.level(tileID);
    }
    
    @Override
    public void mine(long wallID, Direction direction) throws RemoteException
    {
        for(BotClient remote: remotes)
            remote.mine(wallID, direction);
    }
}

@FunctionalInterface
interface ThrowingRunnable
{
    void run() throws Exception;
}
